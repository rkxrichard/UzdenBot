package ru.uzden.uzdenbot.bots;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import ru.uzden.uzdenbot.entities.Subscription;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.services.*;

import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.util.Optional;

@Slf4j
@Component
public class MainBot extends TelegramLongPollingBot {

    private final BotMenuService botMenuService;
    private final AdminService adminService;
    private final AdminStateService adminStateService;
    private final UserService userService;
    private final SubscriptionService subscriptionService;
    private final VpnKeyService vpnKeyService;
    private final RateLimiterService rateLimiterService;
    private final IdempotencyService idempotencyService;

    private final String token;
    private final String username;

    @Autowired
    public MainBot(
            BotMenuService botMenuService,
            AdminService adminService,
            AdminStateService adminStateService,
            UserService userService,
            SubscriptionService subscriptionService,
            VpnKeyService vpnKeyService,
            RateLimiterService rateLimiterService,
            IdempotencyService idempotencyService,
            @Value("${telegram.bot.token}") String token,
            @Value("${telegram.bot.username}")String username) {
        this.botMenuService = botMenuService;
        this.adminService = adminService;
        this.adminStateService = adminStateService;
        this.userService = userService;
        this.subscriptionService = subscriptionService;
        this.vpnKeyService = vpnKeyService;
        this.rateLimiterService = rateLimiterService;
        this.idempotencyService = idempotencyService;
        this.token = token;
        this.username = username;
    }

    @Override
    public String getBotUsername() {
        return username;
    }

    @Override
    public String getBotToken() {
        return token;
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            Long rateChatId = null;
            Long rateUserId = null;
            String rateCallbackId = null;
            if (update.hasMessage() && update.getMessage().getFrom() != null) {
                rateChatId = update.getMessage().getChatId();
                rateUserId = update.getMessage().getFrom().getId();
            } else if (update.hasCallbackQuery() && update.getCallbackQuery().getFrom() != null) {
                rateChatId = update.getCallbackQuery().getMessage().getChatId();
                rateUserId = update.getCallbackQuery().getFrom().getId();
                rateCallbackId = update.getCallbackQuery().getId();
            }

            if (rateUserId != null && rateChatId != null) {
                try {
                    String key = "rl:user:" + rateUserId;
                    if (!rateLimiterService.allow(key)) {
                        denyRateLimit(rateChatId, rateCallbackId);
                        return;
                    }
                } catch (Exception e) {
                    log.warn("Rate limit check failed: {}", e.getMessage());
                }
            }

            if(update.hasMessage() && update.getMessage().hasText()) {
                String text = update.getMessage().getText();
                Long chatId = update.getMessage().getChatId();
                var from = update.getMessage().getFrom();
                boolean isAdmin = adminService.isAdmin(from.getId());

                User user = userService.registerOrUpdate(from);

                if (isAdmin) {
                    if ("/admin".equalsIgnoreCase(text.trim())) {
                        adminStateService.clear(chatId);
                        execute(botMenuService.adminMenu(chatId));
                        return;
                    }
                    Optional<AdminAction> pending = adminStateService.get(chatId);
                    if (pending.isPresent() && !"/start".equals(text)) {
                        if ("/cancel".equalsIgnoreCase(text.trim())) {
                            adminStateService.clear(chatId);
                            execute(simpleMessage(chatId, "✅ Действие отменено."));
                            return;
                        }
                        handleAdminInput(chatId, text, pending.get());
                        return;
                    }
                }

                if (user.isDisabled() && !isAdmin) {
                    execute(simpleMessage(chatId, "🚫 Ваш доступ отключён. Обратитесь к администратору."));
                    return;
                }

                if ("/start".equals(text)) {
                    execute(botMenuService.mainMenu(chatId, isAdmin));
                    return;
                }
            }

            if (update.hasCallbackQuery()) {
                var cq = update.getCallbackQuery();
                String data = update.getCallbackQuery().getData();
                Long chatId = cq.getMessage().getChatId();
                Integer messageId = cq.getMessage().getMessageId();
                boolean isAdmin = adminService.isAdmin(cq.getFrom().getId());

                User user = userService.registerOrUpdate(cq.getFrom());
                if (user.isDisabled() && !isAdmin) {
                    execute(simpleMessage(chatId, "🚫 Ваш доступ отключён. Обратитесь к администратору."));
                    execute(AnswerCallbackQuery.builder().callbackQueryId(cq.getId()).build());
                    return;
                }

                switch (data) {
                    case "MENU_SUBSCRIPTION" -> editFromSendMessage(botMenuService.subscriptionMenu(chatId), chatId, messageId);
//                    case "MENU_HELP" -> editFromSendMessage(simpleMessage(chatId,"Help menu"),chatId,messageId);
                    case "MENU_BACK" -> {
                        adminStateService.clear(chatId);
                        editFromSendMessage(botMenuService.mainMenu(chatId, isAdmin), chatId, messageId);
                    }
                    case "MENU_ADMIN" -> {
                        if (isAdmin) {
                            adminStateService.clear(chatId);
                            editFromSendMessage(botMenuService.adminMenu(chatId), chatId, messageId);
                        }
                    }

                    case "MENU_BUY" -> {
                        // ПОТОМ ДОБАВИТЬ РЕАЛИЗАЦИЮ СМЕНЫ (КУПИТЬ/ПРОДЛИТЬ)
                        // да и впринципе добавить реаллизацию оплаты через Юкассу
                        user = userService.registerOrUpdate(cq.getFrom());
                        if (!guardIdempotency(chatId, "buy:" + user.getId(), Duration.ofSeconds(10), cq.getId())) {
                            return;
                        }
                        subscriptionService.extendSubscription(user, 30);
                        execute(simpleMessage(chatId,"✅ Подписка продлена на 30 дней"));
                        execute(botMenuService.subscriptionMenu(chatId));
                    }
                    case "MENU_GET_KEY" -> {
                        user = userService.registerOrUpdate(cq.getFrom());

                        if (!subscriptionService.hasActiveSubscription(user)) {
                            // Можно сделать alert, но проще — сообщением в чат
                            execute(simpleMessage(chatId, "❌ У вас нет активной подписки. Сначала купите/продлите подписку."));
                            execute(botMenuService.subscriptionMenu(chatId));
                            break;
                        }

                        try {
                            if (!guardIdempotency(chatId, "get_key:" + user.getId(), Duration.ofSeconds(10), cq.getId())) {
                                return;
                            }
                            var key = vpnKeyService.issueKey(user);
                            String msg = "🔑 Ваш VPN-ключ:\n\n" +
                                    "<code>" + escapeHtml(key.getKeyValue()) + "</code>\n\n" +
                                    "📌 Скопируйте ссылку и импортируйте в клиент (Hiddify / v2rayNG / Shadowrocket и т.д.).";

                            SendMessage sm = SendMessage.builder()
                                    .chatId(chatId.toString())
                                    .text(msg)
                                    .parseMode("HTML")
                                    .build();
                            execute(sm);
                        } catch (Exception e) {
                            execute(simpleMessage(chatId, "❌ Не удалось выдать ключ: " + e.getMessage()));
                        }

                        // Обновим меню подписки (например, чтобы юзер сразу видел статус)
                        execute(botMenuService.subscriptionMenu(chatId));
                    }

                    case "MENU_REPLACE_KEY" -> {
                        user = userService.registerOrUpdate(cq.getFrom());

                        if (!subscriptionService.hasActiveSubscription(user)) {
                            execute(simpleMessage(chatId, "❌ У вас нет активной подписки. Сначала купите/продлите подписку."));
                            execute(botMenuService.subscriptionMenu(chatId));
                            break;
                        }

                        try {
                            if (!guardIdempotency(chatId, "replace_key:" + user.getId(), Duration.ofSeconds(10), cq.getId())) {
                                return;
                            }
                            var key = vpnKeyService.replaceKey(user);
                            String msg = "🔄 Ваш VPN-ключ заменён. Новый ключ:\n\n" +
                                    "<code>" + escapeHtml(key.getKeyValue()) + "</code>\n\n" +
                                    "📌 Старый ключ отключён.";

                            SendMessage sm = SendMessage.builder()
                                    .chatId(chatId.toString())
                                    .text(msg)
                                    .parseMode("HTML")
                                    .build();
                            execute(sm);
                        } catch (Exception e) {
                            execute(simpleMessage(chatId, "❌ Не удалось заменить ключ: " + e.getMessage()));
                        }

                        execute(botMenuService.subscriptionMenu(chatId));
                    }

                    case "ADMIN_ADD_SUB" -> {
                        if (isAdmin) {
                            adminStateService.set(chatId, AdminAction.ADD_SUBSCRIPTION);
                            execute(simpleMessage(chatId, "Отправьте @username и количество дней через пробел, например:\n\n@user 30\n\n/cancel — отмена."));
                        }
                    }
                    case "ADMIN_CHECK_SUB" -> {
                        if (isAdmin) {
                            adminStateService.set(chatId, AdminAction.CHECK_SUBSCRIPTION);
                            execute(simpleMessage(chatId, "Отправьте @username для проверки подписки.\n\n/cancel — отмена."));
                        }
                    }
                    case "ADMIN_REVOKE_SUB" -> {
                        if (isAdmin) {
                            adminStateService.set(chatId, AdminAction.REVOKE_SUBSCRIPTION);
                            execute(simpleMessage(chatId, "Отправьте @username, чтобы отключить подписку.\n\n/cancel — отмена."));
                        }
                    }
                    case "ADMIN_DISABLE_USER" -> {
                        if (isAdmin) {
                            adminStateService.set(chatId, AdminAction.DISABLE_USER);
                            execute(simpleMessage(chatId, "Отправьте @username, чтобы отключить пользователя.\n\n/cancel — отмена."));
                        }
                    }
                    case "ADMIN_ENABLE_USER" -> {
                        if (isAdmin) {
                            adminStateService.set(chatId, AdminAction.ENABLE_USER);
                            execute(simpleMessage(chatId, "Отправьте @username, чтобы включить пользователя.\n\n/cancel — отмена."));
                        }
                    }
                }
                execute(AnswerCallbackQuery.builder().callbackQueryId(cq.getId()).build());
            }
        } catch (Exception e) {
            log.error("Ошибка в боте: ", e);
        }
    }

    // ОТПРАВКА ПРОСТЕНЬКОГО СООБЩЕНИЯ
    private SendMessage simpleMessage(Long chatId, String text) {
        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build();
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    // ИЗМЕНЕНИЕ СООБЩЕНИЯ
    private void editFromSendMessage(SendMessage sm, Long chatId, Integer messageId) throws Exception {
        execute(EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text(sm.getText())
                .replyMarkup((InlineKeyboardMarkup) sm.getReplyMarkup())
                .build());
    }

    private void denyRateLimit(Long chatId, String callbackId) throws Exception {
        if (callbackId != null) {
            execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text("Слишком часто. Подождите пару секунд.")
                    .showAlert(false)
                    .build());
        } else if (chatId != null) {
            execute(simpleMessage(chatId, "Слишком часто. Подождите пару секунд."));
        }
    }

    private boolean guardIdempotency(Long chatId, String key, Duration ttl, String callbackId) throws Exception {
        String redisKey = "idemp:" + key;
        try {
            if (idempotencyService.tryAcquire(redisKey, ttl)) {
                return true;
            }
        } catch (Exception e) {
            log.warn("Idempotency check failed: {}", e.getMessage());
            return true;
        }

        if (callbackId != null) {
            execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text("Запрос уже выполняется. Подождите немного.")
                    .showAlert(false)
                    .build());
        } else if (chatId != null) {
            execute(simpleMessage(chatId, "Запрос уже выполняется. Подождите немного."));
        }
        return false;
    }

    private void handleAdminInput(Long chatId, String text, AdminAction action) throws Exception {
        String trimmed = text == null ? "" : text.trim();
        switch (action) {
            case ADD_SUBSCRIPTION -> handleAddSubscription(chatId, trimmed);
            case CHECK_SUBSCRIPTION -> handleCheckSubscription(chatId, trimmed);
            case REVOKE_SUBSCRIPTION -> handleRevokeSubscription(chatId, trimmed);
            case DISABLE_USER -> handleDisableUser(chatId, trimmed);
            case ENABLE_USER -> handleEnableUser(chatId, trimmed);
            default -> {
            }
        }
    }

    private void handleAddSubscription(Long chatId, String text) throws Exception {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            execute(simpleMessage(chatId, "Нужно указать @username и число дней, например: @user 30"));
            return;
        }
        String username = normalizeUsername(parts[0]);
        Integer days = parseDays(parts[1]);
        if (username == null || days == null || days <= 0) {
            execute(simpleMessage(chatId, "Некорректный формат. Пример: @user 30"));
            return;
        }
        Optional<User> userOpt = findUserByIdentifier(username);
        if (userOpt.isEmpty()) {
            execute(simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }

        Subscription sub = subscriptionService.extendSubscription(userOpt.get(), days);
        adminStateService.clear(chatId);
        execute(simpleMessage(chatId, "✅ Подписка выдана до: " + formatDate(sub.getEndDate())));
    }

    private void handleCheckSubscription(Long chatId, String text) throws Exception {
        String username = firstTokenUsername(text);
        if (username == null) {
            execute(simpleMessage(chatId, "Нужно указать @username."));
            return;
        }
        Optional<User> userOpt = findUserByIdentifier(username);
        if (userOpt.isEmpty()) {
            execute(simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }
        User user = userOpt.get();
        Optional<ru.uzden.uzdenbot.entities.Subscription> subOpt = subscriptionService.getActiveSubscription(user);
        if (subOpt.isEmpty()) {
            execute(simpleMessage(chatId, "❌ Активной подписки нет."));
        } else {
            long daysLeft = subscriptionService.getDaysLeft(subOpt.get());
            execute(simpleMessage(chatId,
                    "✅ Активна. Осталось: " + daysLeft + " дн. До: " + formatDate(subOpt.get().getEndDate())));
        }
        adminStateService.clear(chatId);
    }

    private void handleRevokeSubscription(Long chatId, String text) throws Exception {
        String username = firstTokenUsername(text);
        if (username == null) {
            execute(simpleMessage(chatId, "Нужно указать @username."));
            return;
        }
        Optional<User> userOpt = findUserByIdentifier(username);
        if (userOpt.isEmpty()) {
            execute(simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }
        Optional<ru.uzden.uzdenbot.entities.Subscription> revoked = subscriptionService.revokeActiveSubscription(userOpt.get());
        adminStateService.clear(chatId);
        if (revoked.isPresent()) {
            execute(simpleMessage(chatId, "🛑 Подписка отключена."));
        } else {
            execute(simpleMessage(chatId, "Активной подписки не было."));
        }
    }

    private void handleDisableUser(Long chatId, String text) throws Exception {
        String username = firstTokenUsername(text);
        if (username == null) {
            execute(simpleMessage(chatId, "Нужно указать @username."));
            return;
        }
        Optional<User> userOpt = findUserByIdentifier(username);
        if (userOpt.isEmpty()) {
            execute(simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }
        User user = userService.setDisabled(userOpt.get(), true);
        try {
            vpnKeyService.revokeActiveKey(user);
        } catch (Exception e) {
            log.warn("Не удалось отозвать ключ для пользователя {}: {}", user.getId(), e.getMessage());
        }
        adminStateService.clear(chatId);
        execute(simpleMessage(chatId, "🚫 Пользователь отключён."));
    }

    private void handleEnableUser(Long chatId, String text) throws Exception {
        String username = firstTokenUsername(text);
        if (username == null) {
            execute(simpleMessage(chatId, "Нужно указать @username."));
            return;
        }
        Optional<User> userOpt = findUserByIdentifier(username);
        if (userOpt.isEmpty()) {
            execute(simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }
        userService.setDisabled(userOpt.get(), false);
        adminStateService.clear(chatId);
        execute(simpleMessage(chatId, "✅ Пользователь включён."));
    }

    private Optional<User> findUserByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) return Optional.empty();
        if (identifier.chars().allMatch(Character::isDigit)) {
            try {
                return userService.findByTelegramId(Long.parseLong(identifier));
            } catch (NumberFormatException ignore) {
                return Optional.empty();
            }
        }
        return userService.findByUsername(identifier);
    }

    private String normalizeUsername(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.startsWith("@")) t = t.substring(1);
        if (t.isBlank()) return null;
        return t;
    }

    private String firstTokenUsername(String raw) {
        if (raw == null) return null;
        String[] parts = raw.trim().split("\\s+");
        if (parts.length == 0) return null;
        return normalizeUsername(parts[0]);
    }

    private Integer parseDays(String raw) {
        if (raw == null) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatDate(java.time.LocalDateTime dt) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        return dt == null ? "-" : dt.format(fmt);
    }

}
