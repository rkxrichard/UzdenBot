package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import ru.uzden.uzdenbot.config.SubscriptionPlansProperties;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.utils.BotMessageFactory;
import ru.uzden.uzdenbot.utils.BotTextUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BotUpdateHandler {

    private final BotMenuService botMenuService;
    private final AdminService adminService;
    private final AdminStateService adminStateService;
    private final AdminFlowService adminFlowService;
    private final UserService userService;
    private final VpnKeyService vpnKeyService;
    private final IdempotencyService idempotencyService;
    private final PaymentService paymentService;
    private final SubscriptionPlansProperties subscriptionPlansProperties;
    private final ReferralService referralService;

    @Value("${app.idempotency.ttl-seconds:10}")
    private long idempotencyTtlSeconds;
    @Value("${telegram.bot.username}")
    private String botUsername;

    public List<BotApiMethod<?>> handle(Update update) {
        if (update == null) return List.of();
        if (update.hasMessage() && update.getMessage().hasText()) {
            return handleMessage(update);
        }
        if (update.hasCallbackQuery()) {
            return handleCallback(update);
        }
        return List.of();
    }

    private List<BotApiMethod<?>> handleMessage(Update update) {
        List<BotApiMethod<?>> out = new ArrayList<>();
        String text = update.getMessage().getText();
        Long chatId = update.getMessage().getChatId();
        var from = update.getMessage().getFrom();
        boolean isAdmin = adminService.isAdmin(from.getId());

        User user = userService.registerOrUpdate(from);
        if (text != null && text.startsWith("/start")) {
            handleReferralOnStart(out, chatId, user, text, isAdmin);
        }

        if (isAdmin) {
            if (isAdminCommand(text)) {
                adminStateService.clear(chatId);
                out.add(botMenuService.adminMenu(chatId));
                out.add(botMenuService.commandKeyboardMessage(chatId, true));
                return out;
            }
            Optional<AdminAction> pending = adminStateService.get(chatId);
            if (pending.isPresent() && !isStartCommand(text)) {
                if (isCancelCommand(text)) {
                    adminStateService.clear(chatId);
                    out.add(BotMessageFactory.simpleMessage(chatId, "✅ Действие отменено."));
                    return out;
                }
                List<SendMessage> adminResponses = adminFlowService.handleAdminInput(chatId, text, pending.get());
                out.addAll(adminResponses);
                return out;
            }
        }

        if (user.isDisabled() && !isAdmin) {
            out.add(BotMessageFactory.simpleMessage(chatId, "🚫 Ваш доступ отключён. Обратитесь к администратору."));
            return out;
        }

        if (isStartCommand(text)) {
            out.add(botMenuService.mainMenu(chatId, isAdmin, user));
            out.add(botMenuService.commandKeyboardMessage(chatId, isAdmin));
        }
        return out;
    }

    private List<BotApiMethod<?>> handleCallback(Update update) {
        List<BotApiMethod<?>> out = new ArrayList<>();
        var cq = update.getCallbackQuery();
        String data = cq.getData();
        Long chatId = cq.getMessage().getChatId();
        Integer messageId = cq.getMessage().getMessageId();
        String callbackId = cq.getId();
        boolean isAdmin = adminService.isAdmin(cq.getFrom().getId());
        boolean answered = false;

        User user = userService.registerOrUpdate(cq.getFrom());
        if (user.isDisabled() && !isAdmin) {
            out.add(BotMessageFactory.simpleMessage(chatId, "🚫 Ваш доступ отключён. Обратитесь к администратору."));
            out.add(BotMessageFactory.callbackAnswer(callbackId, null));
            return out;
        }

        if (data != null && data.startsWith("KEY_SELECT:")) {
            Long keyId = parseKeyId(data, "KEY_SELECT:");
            if (keyId != null) {
                out.add(BotMessageFactory.editFromSendMessage(
                        botMenuService.keyActionsMenu(chatId, user, keyId), chatId, messageId));
            } else {
                out.add(BotMessageFactory.simpleMessage(chatId, "❌ Не удалось определить ключ."));
            }
        } else if (data != null && data.startsWith("KEY_GET:")) {
            Long keyId = parseKeyId(data, "KEY_GET:");
            answered = handleKeyGet(out, chatId, callbackId, user, keyId);
        } else if (data != null && data.startsWith("KEY_REPLACE:")) {
            Long keyId = parseKeyId(data, "KEY_REPLACE:");
            answered = handleKeyReplace(out, chatId, callbackId, user, keyId);
        } else if (data != null && data.startsWith("KEY_DELETE:")) {
            Long keyId = parseKeyId(data, "KEY_DELETE:");
            answered = handleKeyDelete(out, chatId, callbackId, user, keyId);
        } else if (data != null && data.startsWith("KEY_RENEW:")) {
            Long keyId = parseKeyId(data, "KEY_RENEW:");
            if (keyId != null) {
                out.add(BotMessageFactory.editFromSendMessage(
                        botMenuService.keyPlanMenu(chatId, user, keyId, false), chatId, messageId));
            } else {
                out.add(BotMessageFactory.simpleMessage(chatId, "❌ Не удалось определить ключ."));
            }
        } else if ("KEY_NEW_BUY_1M".equals(data)) {
            SubscriptionPlansProperties.Plan p1 = subscriptionPlansProperties.getPlan1();
            answered = handleKeyPlanPurchase(out, chatId, callbackId, user, null,
                    p1.getDays(), p1.getPrice(), planLabel(p1, "1 месяц"));
        } else if ("KEY_NEW_BUY_2M".equals(data)) {
            SubscriptionPlansProperties.Plan p2 = subscriptionPlansProperties.getPlan2();
            answered = handleKeyPlanPurchase(out, chatId, callbackId, user, null,
                    p2.getDays(), p2.getPrice(), planLabel(p2, "2 месяца"));
        } else if (data != null && data.startsWith("KEY_RENEW_1M:")) {
            Long keyId = parseKeyId(data, "KEY_RENEW_1M:");
            if (keyId == null) {
                out.add(BotMessageFactory.simpleMessage(chatId, "❌ Не удалось определить ключ."));
            } else {
                SubscriptionPlansProperties.Plan p1 = subscriptionPlansProperties.getPlan1();
                answered = handleKeyPlanPurchase(out, chatId, callbackId, user, keyId,
                        p1.getDays(), p1.getPrice(), planLabel(p1, "1 месяц"));
            }
        } else if (data != null && data.startsWith("KEY_RENEW_2M:")) {
            Long keyId = parseKeyId(data, "KEY_RENEW_2M:");
            if (keyId == null) {
                out.add(BotMessageFactory.simpleMessage(chatId, "❌ Не удалось определить ключ."));
            } else {
                SubscriptionPlansProperties.Plan p2 = subscriptionPlansProperties.getPlan2();
                answered = handleKeyPlanPurchase(out, chatId, callbackId, user, keyId,
                        p2.getDays(), p2.getPrice(), planLabel(p2, "2 месяца"));
            }
        } else {
            switch (data) {
            case "MENU_SUBSCRIPTION" -> out.add(BotMessageFactory.editFromSendMessage(
                    botMenuService.subscriptionMenu(chatId), chatId, messageId));
            case "MENU_KEYS" -> out.add(BotMessageFactory.editFromSendMessage(
                    botMenuService.myKeysMenu(chatId, user), chatId, messageId));
            case "MENU_HELP" -> out.add(BotMessageFactory.editFromSendMessage(
                    botMenuService.instructionsMenu(chatId), chatId, messageId));
            case "MENU_REFERRAL" -> out.add(BotMessageFactory.editFromSendMessage(
                    botMenuService.referralMenu(chatId, user, botUsername), chatId, messageId));
            case "MENU_BACK" -> {
                adminStateService.clear(chatId);
                out.add(BotMessageFactory.editFromSendMessage(
                        botMenuService.mainMenu(chatId, isAdmin, user), chatId, messageId));
            }
            case "MENU_ADMIN" -> {
                if (isAdmin) {
                    adminStateService.clear(chatId);
                    out.add(BotMessageFactory.editFromSendMessage(
                            botMenuService.adminMenu(chatId), chatId, messageId));
                }
            }
            case "ADMIN_ACTIVE_USERS" -> {
                if (isAdmin) {
                    out.add(adminFlowService.buildActiveUsersMessage(chatId));
                }
            }
            case "MENU_BUY" -> {
                if (vpnKeyService.listUserKeys(user).isEmpty()) {
                    out.add(BotMessageFactory.editFromSendMessage(
                            botMenuService.subscriptionPlanMenu(chatId), chatId, messageId));
                } else {
                    out.add(BotMessageFactory.editFromSendMessage(
                            botMenuService.myKeysMenu(chatId, user), chatId, messageId));
                }
            }
            case "BUY_1M" -> {
                SubscriptionPlansProperties.Plan p1 = subscriptionPlansProperties.getPlan1();
                answered = handleKeyPlanPurchase(out, chatId, callbackId, user, null,
                        p1.getDays(), p1.getPrice(), planLabel(p1, "1 месяц"));
            }
            case "BUY_2M" -> {
                SubscriptionPlansProperties.Plan p2 = subscriptionPlansProperties.getPlan2();
                answered = handleKeyPlanPurchase(out, chatId, callbackId, user, null,
                        p2.getDays(), p2.getPrice(), planLabel(p2, "2 месяца"));
            }
            case "KEY_NEW" -> out.add(BotMessageFactory.editFromSendMessage(
                    botMenuService.keyPlanMenu(chatId, user, null, true), chatId, messageId));
            case "MENU_GET_KEY" -> out.add(BotMessageFactory.editFromSendMessage(
                    botMenuService.myKeysMenu(chatId, user), chatId, messageId));
            case "MENU_REPLACE_KEY" -> out.add(BotMessageFactory.editFromSendMessage(
                    botMenuService.myKeysMenu(chatId, user), chatId, messageId));
            case "ADMIN_ADD_SUB" -> {
                if (isAdmin) {
                    adminStateService.set(chatId, AdminAction.ADD_SUBSCRIPTION);
                    out.add(BotMessageFactory.simpleMessage(chatId,
                            "Отправьте @username и количество дней через пробел, например:\n\n@user 30\n\n" +
                                    "Подписка будет привязана к первому ключу (или ключ будет создан).\n\nОтмена (или /cancel) — отмена."));
                }
            }
            case "ADMIN_CHECK_SUB" -> {
                if (isAdmin) {
                    adminStateService.set(chatId, AdminAction.CHECK_SUBSCRIPTION);
                    out.add(BotMessageFactory.simpleMessage(chatId,
                            "Отправьте @username для проверки подписок по ключам.\n\nОтмена (или /cancel) — отмена."));
                }
            }
            case "ADMIN_REVOKE_SUB" -> {
                if (isAdmin) {
                    adminStateService.set(chatId, AdminAction.REVOKE_SUBSCRIPTION);
                    out.add(BotMessageFactory.simpleMessage(chatId,
                            "Отправьте @username, чтобы отключить все активные подписки.\n\nОтмена (или /cancel) — отмена."));
                }
            }
            case "ADMIN_DISABLE_USER" -> {
                if (isAdmin) {
                    adminStateService.set(chatId, AdminAction.DISABLE_USER);
                    out.add(BotMessageFactory.simpleMessage(chatId,
                            "Отправьте @username, чтобы отключить пользователя.\n\nОтмена (или /cancel) — отмена."));
                }
            }
            case "ADMIN_ENABLE_USER" -> {
                if (isAdmin) {
                    adminStateService.set(chatId, AdminAction.ENABLE_USER);
                    out.add(BotMessageFactory.simpleMessage(chatId,
                            "Отправьте @username, чтобы включить пользователя.\n\nОтмена (или /cancel) — отмена."));
                }
            }
            case "ADMIN_BROADCAST" -> {
                if (isAdmin) {
                    adminStateService.set(chatId, AdminAction.BROADCAST);
                    out.add(BotMessageFactory.simpleMessage(chatId,
                            "Отправьте текст рассылки. Сообщение будет отправлено всем пользователям.\n\nОтмена (или /cancel) — отмена."));
                }
            }
            case "ADMIN_PURGE_DISABLED_KEYS" -> {
                if (isAdmin) {
                    InlineKeyboardButton bYes = InlineKeyboardButton.builder()
                            .text("✅ Да, удалить")
                            .callbackData("ADMIN_PURGE_DISABLED_CONFIRM")
                            .build();
                    InlineKeyboardButton bNo = InlineKeyboardButton.builder()
                            .text("✖️ Отмена")
                            .callbackData("ADMIN_PURGE_DISABLED_CANCEL")
                            .build();
                    InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                            .keyboard(List.of(List.of(bYes, bNo)))
                            .build();
                    SendMessage sm = SendMessage.builder()
                            .chatId(chatId.toString())
                            .text("Удалить всех отключённых пользователей и их ключи? Действие необратимо.")
                            .replyMarkup(markup)
                            .build();
                    out.add(sm);
                }
            }
            case "ADMIN_PURGE_DISABLED_CONFIRM" -> {
                if (isAdmin) {
                    int removed = vpnKeyService.purgeDisabledUsers();
                    String msg = removed == 0
                            ? "🧹 Отключённых пользователей для удаления нет."
                            : "🧹 Удалено отключённых пользователей: " + removed;
                    out.add(BotMessageFactory.simpleMessage(chatId, msg));
                }
            }
            case "ADMIN_PURGE_DISABLED_CANCEL" -> {
                if (isAdmin) {
                    out.add(BotMessageFactory.simpleMessage(chatId, "Отменено."));
                }
            }
            default -> {
            }
        }
        }

        if (!answered) {
            out.add(BotMessageFactory.callbackAnswer(callbackId, null));
        }
        return out;
    }

    private void handleReferralOnStart(List<BotApiMethod<?>> out, Long chatId, User user, String text, boolean isAdmin) {
        if (text == null) return;
        if (user != null && user.isDisabled() && !isAdmin) return;
        String code = extractStartCode(text);
        if (code == null) return;
        ReferralService.ReferralResult result = referralService.applyReferral(user, code);
        switch (result.status) {
            case APPLIED -> {
                out.add(BotMessageFactory.simpleMessage(chatId,
                        "✅ После перехода по ссылке вам добавилось " + result.referredDays + " дн."));
                Long refTg = result.referrerTelegramId;
                if (refTg != null) {
                    out.add(BotMessageFactory.simpleMessage(refTg,
                            "🎉 Ваш реферал активировался! Вам начислено " + result.referrerDays + " дн."));
                }
            }
            case SELF_REF -> out.add(BotMessageFactory.simpleMessage(chatId, "Нельзя использовать свою реферальную ссылку."));
            case ALREADY_REFERRED -> out.add(BotMessageFactory.simpleMessage(chatId, "Реферал уже был активирован ранее."));
            case INVALID_CODE -> out.add(BotMessageFactory.simpleMessage(chatId, "Реферальный код не найден."));
            case NO_CODE -> {
            }
        }
    }

    private String extractStartCode(String text) {
        String t = text.trim();
        if (t.equalsIgnoreCase("/start")) return null;
        if (!t.startsWith("/start")) return null;
        String[] parts = t.split("\\s+", 2);
        if (parts.length < 2) return null;
        String payload = parts[1].trim();
        return payload.isBlank() ? null : payload;
    }

    private boolean isStartCommand(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.equalsIgnoreCase("/start")) return true;
        if (t.equalsIgnoreCase("старт")) return true;
        return t.toLowerCase().startsWith("/start ");
    }

    private boolean isAdminCommand(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.equalsIgnoreCase("/admin") || t.equalsIgnoreCase("админ");
    }

    private boolean isCancelCommand(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.equalsIgnoreCase("/cancel") || t.equalsIgnoreCase("отмена");
    }

    private boolean handleKeyGet(List<BotApiMethod<?>> out, Long chatId, String callbackId, User user, Long keyId) {
        if (keyId == null) {
            out.add(BotMessageFactory.simpleMessage(chatId, "❌ Не удалось определить ключ."));
            out.add(botMenuService.myKeysMenu(chatId, user));
            return false;
        }

        if (!acquireIdempotency(out, callbackId, "get_key:" + user.getId() + ":" + keyId)) {
            return true;
        }

        try {
            vpnKeyService.ensureKeyForActiveSubscription(user);
            var key = vpnKeyService.getKeyForUser(user, keyId);
            String msg = "🔑 Ваш VPN-ключ:\n\n" +
                    "<code>" + BotTextUtils.escapeHtml(key.getKeyValue()) + "</code>\n\n" +
                    "📌 Скопируйте ссылку и импортируйте в клиент.";
            SendMessage sm = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(msg)
                    .parseMode("HTML")
                    .build();
            out.add(sm);
        } catch (Exception e) {
            if (isNoActiveSubscriptionError(e)) {
                InlineKeyboardButton bRenew = InlineKeyboardButton.builder()
                        .text("🔁 Продлить")
                        .callbackData("KEY_RENEW:" + keyId)
                        .build();
                InlineKeyboardButton bBack = InlineKeyboardButton.builder()
                        .text("⬅️ Назад")
                        .callbackData("MENU_KEYS")
                        .build();
                InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                        .keyboard(List.of(List.of(bRenew), List.of(bBack)))
                        .build();
                SendMessage sm = SendMessage.builder()
                        .chatId(chatId.toString())
                        .text("❌ Для этого ключа нет активной подписки.\nХотите продлить?")
                        .replyMarkup(markup)
                        .build();
                out.add(sm);
            } else {
                out.add(BotMessageFactory.simpleMessage(chatId, "❌ Не удалось получить ключ: " + e.getMessage()));
            }
        }

        out.add(botMenuService.myKeysMenu(chatId, user));
        return false;
    }

    private boolean handleKeyDelete(List<BotApiMethod<?>> out, Long chatId, String callbackId, User user, Long keyId) {
        if (keyId == null) {
            out.add(BotMessageFactory.simpleMessage(chatId, "❌ Не удалось определить ключ."));
            out.add(botMenuService.myKeysMenu(chatId, user));
            return false;
        }

        if (!acquireIdempotency(out, callbackId, "delete_key:" + user.getId() + ":" + keyId)) {
            return true;
        }

        try {
            vpnKeyService.ensureKeyForActiveSubscription(user);
            if (!vpnKeyService.canDeleteKey(user, keyId)) {
                VpnKey key = vpnKeyService.findKeyForUser(user, keyId);
                var activeSub = vpnKeyService.getActiveSubscriptionForKey(key);
                String until = activeSub.isPresent()
                        ? BotTextUtils.formatDate(activeSub.get().getEndDate())
                        : "-";
                InlineKeyboardButton bRenew = InlineKeyboardButton.builder()
                        .text("🔁 Продлить")
                        .callbackData("KEY_RENEW:" + keyId)
                        .build();
                InlineKeyboardButton bBack = InlineKeyboardButton.builder()
                        .text("⬅️ Назад")
                        .callbackData("MENU_KEYS")
                        .build();
                InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                        .keyboard(List.of(List.of(bRenew), List.of(bBack)))
                        .build();
                SendMessage sm = SendMessage.builder()
                        .chatId(chatId.toString())
                        .text("❌ Ключ можно удалить только после окончания срока.\n" +
                                "🗓 Действует до: " + until)
                        .replyMarkup(markup)
                        .build();
                out.add(sm);
                out.add(botMenuService.myKeysMenu(chatId, user));
                return false;
            }
            vpnKeyService.revokeKeyForUser(user, keyId);
            out.add(BotMessageFactory.simpleMessage(chatId, "🗑 Ключ удалён."));
        } catch (Exception e) {
            out.add(BotMessageFactory.simpleMessage(chatId, "❌ Не удалось удалить ключ: " + e.getMessage()));
        }

        out.add(botMenuService.myKeysMenu(chatId, user));
        return false;
    }

    private boolean handleKeyReplace(List<BotApiMethod<?>> out, Long chatId, String callbackId, User user, Long keyId) {
        if (keyId == null) {
            out.add(BotMessageFactory.simpleMessage(chatId, "❌ Не удалось определить ключ."));
            out.add(botMenuService.myKeysMenu(chatId, user));
            return false;
        }

        if (!acquireIdempotency(out, callbackId, "replace_key:" + user.getId() + ":" + keyId)) {
            return true;
        }

        try {
            vpnKeyService.ensureKeyForActiveSubscription(user);
            var key = vpnKeyService.replaceKeyForUser(user, keyId);
            String msg = "🔄 Ключ заменён. Новый ключ:\n\n" +
                    "<code>" + BotTextUtils.escapeHtml(key.getKeyValue()) + "</code>\n\n" +
                    "📌 Старый ключ отключён.";
            SendMessage sm = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(msg)
                    .parseMode("HTML")
                    .build();
            out.add(sm);
        } catch (Exception e) {
            if (isNoActiveSubscriptionError(e)) {
                InlineKeyboardButton bRenew = InlineKeyboardButton.builder()
                        .text("🔁 Продлить")
                        .callbackData("KEY_RENEW:" + keyId)
                        .build();
                InlineKeyboardButton bBack = InlineKeyboardButton.builder()
                        .text("⬅️ Назад")
                        .callbackData("MENU_KEYS")
                        .build();
                InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                        .keyboard(List.of(List.of(bRenew), List.of(bBack)))
                        .build();
                SendMessage sm = SendMessage.builder()
                        .chatId(chatId.toString())
                        .text("❌ Для этого ключа нет активной подписки.\nХотите продлить?")
                        .replyMarkup(markup)
                        .build();
                out.add(sm);
            } else {
                out.add(BotMessageFactory.simpleMessage(chatId, "❌ Не удалось заменить ключ: " + e.getMessage()));
            }
        }

        out.add(botMenuService.myKeysMenu(chatId, user));
        return false;
    }

    private boolean handleKeyPlanPurchase(List<BotApiMethod<?>> out, Long chatId, String callbackId,
                                          User user, Long keyId, int days, int price, String label) {
        out.add(BotMessageFactory.simpleMessage(chatId, "Пока нет оплаты."));
        return false;
    }

    private Long parseKeyId(String data, String prefix) {
        if (data == null || prefix == null || !data.startsWith(prefix)) return null;
        String raw = data.substring(prefix.length()).trim();
        if (raw.isEmpty()) return null;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isNoActiveSubscriptionError(Exception e) {
        String msg = e == null ? null : e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("нет активной подписки");
    }

    private String planLabel(SubscriptionPlansProperties.Plan plan, String fallback) {
        if (plan == null || plan.getLabel() == null || plan.getLabel().isBlank()) {
            return fallback;
        }
        return plan.getLabel();
    }

    private boolean acquireIdempotency(List<BotApiMethod<?>> out, String callbackId, String key) {
        Duration ttl = Duration.ofSeconds(idempotencyTtlSeconds);
        try {
            if (idempotencyService.tryAcquire("idemp:" + key, ttl)) {
                return true;
            }
        } catch (Exception e) {
            log.warn("Idempotency check failed: {}", e.getMessage());
            return true;
        }
        AnswerCallbackQuery notice = BotMessageFactory.callbackAnswer(
                callbackId,
                "Запрос уже выполняется. Подождите немного."
        );
        out.add(notice);
        return false;
    }
}
