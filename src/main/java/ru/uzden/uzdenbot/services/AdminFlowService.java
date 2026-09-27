package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.CopyMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import ru.uzden.uzdenbot.entities.Subscription;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.utils.BotMessageFactory;
import ru.uzden.uzdenbot.utils.BotTextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminFlowService {

    private final AdminStateService adminStateService;
    private final SubscriptionService subscriptionService;
    private final UserService userService;
    private final VpnKeyService vpnKeyService;
    private final ReferralService referralService;

    @Value("${telegram.bot.username:}")
    private String botUsername;

    /** MainBot берём лениво: он сам зависит (через BotUpdateHandler) от этого сервиса. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<ru.uzden.uzdenbot.bots.MainBot> mainBotProvider;

    private final java.util.concurrent.atomic.AtomicBoolean rotateAllRunning = new java.util.concurrent.atomic.AtomicBoolean(false);

    public List<BotApiMethod<?>> handleAdminMessage(Long chatId, Message message, AdminAction action) {
        List<BotApiMethod<?>> out = new ArrayList<>();
        String text = message == null ? null : message.getText();
        String trimmed = text == null ? "" : text.trim();
        switch (action) {
            case ADD_SUBSCRIPTION -> handleAddSubscription(chatId, trimmed, out);
            case CHECK_SUBSCRIPTION -> handleCheckSubscription(chatId, trimmed, out);
            case REVOKE_SUBSCRIPTION -> handleRevokeSubscription(chatId, trimmed, out);
            case DISABLE_USER -> handleDisableUser(chatId, trimmed, out);
            case ENABLE_USER -> handleEnableUser(chatId, trimmed, out);
            case BROADCAST -> handleBroadcast(chatId, message, out);
            case CREATE_RU_EU_KEY -> handleCreateRuEuKey(chatId, trimmed, out);
            case CREATE_REFERRAL_LINK -> handleCreateReferralLink(chatId, trimmed, out);
            case REFERRAL_LINK_STATS -> handleReferralLinkStats(chatId, trimmed, out);
            case RESET_REFERRAL_LINK_COUNTER -> handleResetReferralLinkCounter(chatId, trimmed, out);
            case DELETE_REFERRAL_LINK -> handleDeleteReferralLink(chatId, trimmed, out);
            case CREATE_ADMIN_KEY -> handleCreateAdminKey(chatId, trimmed, out);
            case RENEW_ADMIN_KEY -> handleRenewAdminKey(chatId, trimmed, out);
            case REPLACE_ADMIN_KEY -> handleReplaceAdminKey(chatId, trimmed, out);
            default -> {
            }
        }
        return out;
    }

    public SendMessage buildActiveUsersMessage(Long chatId) {
        List<User> users = subscriptionService.getActiveUsersWithSubscription();
        if (users.isEmpty()) {
            return BotMessageFactory.simpleMessage(chatId, "Активных подписок нет.");
        }
        StringBuilder sb = new StringBuilder("👥 Активные пользователи (" + users.size() + "):\n");
        for (User u : users) {
            String uname = u.getUsername();
            if (uname != null && !uname.isBlank()) {
                if (!uname.startsWith("@")) {
                    uname = "@" + uname;
                }
                sb.append(uname);
            } else if (u.getTelegramId() != null) {
                sb.append("tg_").append(u.getTelegramId());
            } else {
                sb.append("user_").append(u.getId());
            }
            long daysLeft = subscriptionService.getActiveSubscription(u)
                    .map(subscriptionService::getDaysLeft)
                    .orElse(0L);
            sb.append(" — ").append(formatDaysLeft(daysLeft));
            sb.append("\n");
        }
        return BotMessageFactory.simpleMessage(chatId, sb.toString().trim());
    }

    private String formatDaysLeft(long daysLeft) {
        long abs = Math.abs(daysLeft);
        long mod100 = abs % 100;
        long mod10 = abs % 10;
        String word;
        if (mod100 >= 11 && mod100 <= 14) {
            word = "дней";
        } else if (mod10 == 1) {
            word = "день";
        } else if (mod10 >= 2 && mod10 <= 4) {
            word = "дня";
        } else {
            word = "дней";
        }
        return daysLeft + " " + word;
    }

    private void handleAddSubscription(Long chatId, String text, List<BotApiMethod<?>> out) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Нужно указать @username и число дней, например: @user 30"));
            return;
        }
        String username = normalizeUsername(parts[0]);
        Integer days = parseDays(parts[1]);
        if (username == null || days == null || days <= 0) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Некорректный формат. Пример: @user 30"));
            return;
        }
        Optional<User> userOpt = findUserByIdentifier(username);
        if (userOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }

        User user = userOpt.get();
        Subscription sub;
        var keys = vpnKeyService.listUserKeys(user);
        var preferredKey = keys.stream()
                .filter(key -> key.getBackend() == VpnKey.Backend.DEFAULT)
                .findFirst()
                .orElse(keys.isEmpty() ? null : keys.get(0));
        String keyLabel;
        if (preferredKey != null) {
            sub = subscriptionService.extendSubscriptionForKey(user, preferredKey, days);
            keyLabel = "№1";
        } else {
            var key = vpnKeyService.createPendingKey(user);
            sub = subscriptionService.extendSubscriptionForKey(user, key, days);
            keyLabel = "№1 (создан)";
        }
        adminStateService.clear(chatId);
        out.add(BotMessageFactory.simpleMessage(chatId, "✅ Подписка выдана до: " + BotTextUtils.formatDate(sub.getEndDate()) +
                "\nКлюч: " + keyLabel));
    }

    private void handleCheckSubscription(Long chatId, String text, List<BotApiMethod<?>> out) {
        String username = firstTokenUsername(text);
        if (username == null) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Нужно указать @username."));
            return;
        }
        Optional<User> userOpt = findUserByIdentifier(username);
        if (userOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }
        User user = userOpt.get();
        vpnKeyService.ensureKeyForActiveSubscription(user);
        var keys = vpnKeyService.listUserKeys(user);
        if (keys.isEmpty()) {
            Optional<Subscription> subOpt = subscriptionService.getActiveSubscription(user);
            if (subOpt.isEmpty()) {
                out.add(BotMessageFactory.simpleMessage(chatId, "❌ Активных подписок нет."));
            } else {
                long daysLeft = subscriptionService.getDaysLeft(subOpt.get());
                out.add(BotMessageFactory.simpleMessage(chatId,
                        "✅ Активна. Осталось: " + daysLeft + " дн. До: " + BotTextUtils.formatDate(subOpt.get().getEndDate())));
            }
        } else {
            StringBuilder sb = new StringBuilder("📦 Подписки по ключам:\n");
            for (int i = 0; i < keys.size(); i++) {
                var key = keys.get(i);
                var active = subscriptionService.getActiveSubscription(key);
                if (active.isPresent()) {
                    long daysLeft = subscriptionService.getDaysLeft(active.get());
                    sb.append("Ключ ").append(i + 1)
                            .append(": ").append(daysLeft).append(" дн. до ")
                            .append(BotTextUtils.formatDate(active.get().getEndDate()))
                            .append("\n");
                } else {
                    sb.append("Ключ ").append(i + 1).append(": подписка не активна\n");
                }
            }
            out.add(BotMessageFactory.simpleMessage(chatId, sb.toString().trim()));
        }
        adminStateService.clear(chatId);
    }

    private void handleRevokeSubscription(Long chatId, String text, List<BotApiMethod<?>> out) {
        String username = firstTokenUsername(text);
        if (username == null) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Нужно указать @username."));
            return;
        }
        Optional<User> userOpt = findUserByIdentifier(username);
        if (userOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }
        int revoked = subscriptionService.revokeAllActiveSubscriptions(userOpt.get());
        adminStateService.clear(chatId);
        if (revoked > 0) {
            out.add(BotMessageFactory.simpleMessage(chatId, "🛑 Отключено подписок: " + revoked));
        } else {
            out.add(BotMessageFactory.simpleMessage(chatId, "Активных подписок не было."));
        }
    }

    private void handleDisableUser(Long chatId, String text, List<BotApiMethod<?>> out) {
        String username = firstTokenUsername(text);
        if (username == null) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Нужно указать @username."));
            return;
        }
        Optional<User> userOpt = findUserByIdentifier(username);
        if (userOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }
        User user = userService.setDisabled(userOpt.get(), true);
        try {
            vpnKeyService.revokeAllKeys(user);
        } catch (Exception e) {
            log.warn("Не удалось отозвать ключ для пользователя {}: {}", user.getId(), e.getMessage());
        }
        adminStateService.clear(chatId);
        out.add(BotMessageFactory.simpleMessage(chatId, "🚫 Пользователь отключён."));
    }

    private void handleEnableUser(Long chatId, String text, List<BotApiMethod<?>> out) {
        String username = firstTokenUsername(text);
        if (username == null) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Нужно указать @username."));
            return;
        }
        Optional<User> userOpt = findUserByIdentifier(username);
        if (userOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }
        userService.setDisabled(userOpt.get(), false);
        adminStateService.clear(chatId);
        out.add(BotMessageFactory.simpleMessage(chatId, "✅ Пользователь включён."));
    }

    private void handleBroadcast(Long chatId, Message message, List<BotApiMethod<?>> out) {
        if (message == null || message.getChatId() == null || message.getMessageId() == null) {
            out.add(BotMessageFactory.simpleMessage(chatId,
                    "Не удалось прочитать сообщение для рассылки. Попробуйте ещё раз."));
            return;
        }
        if (!message.hasText() && !message.hasPhoto() && !message.hasVideo()) {
            out.add(BotMessageFactory.simpleMessage(chatId,
                    "Поддерживаются текст, фото и видео.\n\n/cancel — отмена."));
            return;
        }

        List<User> users = userService.listAll();
        if (users.isEmpty()) {
            adminStateService.clear(chatId);
            out.add(BotMessageFactory.simpleMessage(chatId, "Пользователей нет. Рассылка не отправлена."));
            return;
        }

        int delivered = 0;
        for (User u : users) {
            Long telegramId = u.getTelegramId();
            if (telegramId == null) continue;
            out.add(CopyMessage.builder()
                    .chatId(telegramId.toString())
                    .fromChatId(message.getChatId().toString())
                    .messageId(message.getMessageId())
                    .build());
            delivered++;
        }

        adminStateService.clear(chatId);
        out.add(0, BotMessageFactory.simpleMessage(chatId,
                "📣 Рассылка отправлена: " + delivered + " пользователей."));
    }

    private void handleCreateReferralLink(Long chatId, String text, List<BotApiMethod<?>> out) {
        String identifier = firstTokenIdentifier(text);
        if (identifier == null) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Нужно указать @username или telegram id пользователя."));
            return;
        }

        Optional<User> userOpt = findUserByIdentifier(identifier);
        if (userOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }

        User user = userOpt.get();
        ReferralService.CreatedReferralLink link = referralService.createTrackedLink(user);
        String url = referralService.buildReferralUrl(botUsername, link.code());

        adminStateService.clear(chatId);
        out.add(BotMessageFactory.simpleMessage(chatId,
                "🔗 Уникальная реферальная ссылка создана.\n" +
                        "Пользователь: " + displayUser(user) + "\n" +
                        "Код: " + link.code() + "\n" +
                        "Создана: " + BotTextUtils.formatDate(link.createdAt()) + "\n" +
                        "Переходов по ссылке: " + link.transitionsCount() + "\n" +
                        "Бонусные дни по этой ссылке не начисляются.\n\n" +
                        "Ссылка:\n" + url));
    }

    private void handleCreateRuEuKey(Long chatId, String text, List<BotApiMethod<?>> out) {
        String identifier = firstTokenIdentifier(text);
        if (identifier == null) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Нужно указать @username или telegram id пользователя."));
            return;
        }

        Optional<User> userOpt = findUserByIdentifier(identifier);
        if (userOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }

        User user = userOpt.get();
        try {
            VpnKey key = vpnKeyService.issueRuEuKey(user);
            adminStateService.clear(chatId);
            out.add(BotMessageFactory.simpleMessage(chatId,
                    "🌉 RU+EU ключ создан.\n" +
                            "Пользователь: " + displayUser(user) + "\n" +
                            "Ключ ID: " + key.getId() + "\n" +
                            "Тип: RU+EU\n" +
                            "Это тестовый ключ без подписки. Пользователь сможет получить его в разделе «Мои ключи»."));
        } catch (Exception e) {
            out.add(BotMessageFactory.simpleMessage(chatId, "❌ Не удалось создать RU+EU ключ: " + e.getMessage()));
        }
    }

    private void handleCreateAdminKey(Long chatId, String text, List<BotApiMethod<?>> out) {
        if (text == null || text.isBlank()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Нужно указать срок в днях и имя. Пример: 30 Клиент Иван"));
            return;
        }
        String[] parts = text.trim().split("\\s+", 2);
        Integer days = parseDays(parts[0]);
        String name = parts.length > 1 ? parts[1].trim() : null;
        if (days == null || days <= 0) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Некорректный срок. Первым идёт число дней. Пример: 30 Клиент Иван"));
            return;
        }
        if (name == null || name.isBlank()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Нужно указать имя ключа после числа дней. Пример: 30 Клиент Иван"));
            return;
        }

        Optional<User> ownerOpt = userService.findByTelegramId(chatId);
        if (ownerOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Не удалось определить админа-владельца. Напишите /start и повторите."));
            return;
        }

        try {
            User owner = ownerOpt.get();
            VpnKey key = vpnKeyService.issueAdminKey(owner, name);
            Subscription sub = subscriptionService.extendSubscriptionForKey(owner, key, days);
            adminStateService.clear(chatId);
            out.add(buildAdminKeyDeliveryMessage(chatId, key, sub));
        } catch (Exception e) {
            log.warn("Не удалось создать админ-ключ: {}", e.getMessage());
            out.add(BotMessageFactory.simpleMessage(chatId, "❌ Не удалось создать ключ: " + e.getMessage()));
        }
    }

    private void handleRenewAdminKey(Long chatId, String text, List<BotApiMethod<?>> out) {
        String[] parts = text == null ? new String[0] : text.trim().split("\\s+");
        if (parts.length < 2) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Нужно указать ID ключа и число дней. Пример: 42 30"));
            return;
        }
        Long keyId = parseLong(parts[0]);
        Integer days = parseDays(parts[1]);
        if (keyId == null || days == null || days <= 0) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Некорректный формат. Пример: 42 30 (ID ключа и число дней)"));
            return;
        }
        try {
            VpnKey key = vpnKeyService.renewAdminKey(keyId, days);
            Optional<Subscription> sub = subscriptionService.getActiveSubscription(key);
            adminStateService.clear(chatId);
            out.add(BotMessageFactory.simpleMessage(chatId,
                    "🔁 Ключ продлён." +
                            "\nID: " + key.getId() +
                            (key.getName() != null && !key.getName().isBlank() ? "\nИмя: " + key.getName() : "") +
                            "\n🗓 Действует до: " + sub.map(s -> BotTextUtils.formatDate(s.getEndDate())).orElse("-")));
            out.add(buildAdminKeyLinkMessage(chatId, key));
        } catch (Exception e) {
            log.warn("Не удалось продлить админ-ключ {}: {}", keyId, e.getMessage());
            out.add(BotMessageFactory.simpleMessage(chatId, "❌ Не удалось продлить ключ: " + e.getMessage()));
        }
    }

    /**
     * Список админ-ключей с актуальными ссылками (tap-to-copy через &lt;code&gt;).
     * Бьётся на несколько сообщений, чтобы не упереться в лимит Telegram 4096 символов.
     */
    public List<SendMessage> buildAdminKeysMessages(Long chatId) {
        List<VpnKey> keys = vpnKeyService.listAdminCreatedKeys();
        if (keys.isEmpty()) {
            return List.of(BotMessageFactory.simpleMessage(chatId, "Созданных админом ключей пока нет."));
        }
        final int limit = 3500;
        List<SendMessage> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder("📃 <b>Созданные ключи</b> · " + keys.size() + "\n"
                + "<i>Нажмите на ссылку — она скопируется.</i>\n");
        for (VpnKey key : keys) {
            String card = "\n" + adminKeyCard(key);
            if (sb.length() + card.length() > limit) {
                out.add(htmlMessage(chatId, sb.toString()));
                sb = new StringBuilder();
            }
            sb.append(card);
        }
        sb.append("\n<i>Продлить: «🔁 Продлить ключ» → </i><code>")
                .append(keys.get(0).getId()).append(" 30</code> <i>(ID и дни)</i>");
        SendMessage last = htmlMessage(chatId, sb.toString());
        last.setReplyMarkup(adminKeysKeyboard());
        out.add(last);

        appendManualPanelClients(chatId, out);
        return out;
    }

    /**
     * Клиенты, добавленные вручную прямо в панели 3x-ui (не через бота) — у них нет строки
     * в БД, поэтому в основной список они не попадают. Показываем отдельным блоком, той же
     * ссылкой для копирования, чтобы их было видно и можно было проверить/отозвать в панели.
     */
    private void appendManualPanelClients(Long chatId, List<SendMessage> out) {
        List<VpnKeyService.ManualPanelClient> manual;
        try {
            manual = vpnKeyService.listUnlinkedPanelClients();
        } catch (Exception e) {
            log.warn("Не удалось получить список ручных клиентов панели: {}", e.getMessage());
            return;
        }
        if (manual.isEmpty()) {
            return;
        }
        final int limit = 3500;
        List<SendMessage> extra = new ArrayList<>();
        StringBuilder sb = new StringBuilder("🧩 <b>Ключи, созданные вручную в панели</b> · " + manual.size() + "\n"
                + "<i>Не привязаны к боту/пользователям — только из 3x-ui.</i>\n");
        for (VpnKeyService.ManualPanelClient c : manual) {
            String card = "\n" + manualClientCard(c);
            if (sb.length() + card.length() > limit) {
                extra.add(htmlMessage(chatId, sb.toString()));
                sb = new StringBuilder();
            }
            sb.append(card);
        }
        extra.add(htmlMessage(chatId, sb.toString()));
        out.addAll(extra);
    }

    private String manualClientCard(VpnKeyService.ManualPanelClient c) {
        StringBuilder card = new StringBuilder("<blockquote>");
        String email = (c.email() == null || c.email().isBlank()) ? "—" : c.email();
        card.append("👤 <b>").append(BotTextUtils.escapeHtml(email)).append("</b>");
        card.append("\n").append(c.enable() ? "🟢 включён" : "🔴 выключен");
        if (c.backend() == VpnKey.Backend.RU_EU) {
            card.append(" · RU+EU");
        }
        card.append("</blockquote>");
        String link = null;
        try {
            link = vpnKeyService.manualPanelClientLink(c);
        } catch (Exception e) {
            log.warn("Не удалось собрать ссылку для ручного клиента {}: {}", c.email(), e.getMessage());
        }
        if (link != null) {
            card.append("\n<code>").append(BotTextUtils.escapeHtml(link)).append("</code>\n");
        } else {
            card.append("\n<i>без subId — подписочная ссылка недоступна, проверяйте в панели</i>\n");
        }
        return card.toString();
    }

    public static org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup adminKeysKeyboard() {
        var one = org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                .text("♻️ Заменить ключ").callbackData("ADMIN_REPLACE_KEY").build();
        var all = org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                .text("♻️ Заменить все").callbackData("ADMIN_REPLACE_ALL_KEYS").build();
        var refresh = org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                .text("🔄 Обновить список").callbackData("ADMIN_LIST_KEYS").build();
        return org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup.builder()
                .keyboard(List.of(List.of(one, all), List.of(refresh)))
                .build();
    }

    /* ======================= замена админ-ключей ======================= */

    public SendMessage replaceAdminKeyPrompt(Long chatId) {
        adminStateService.set(chatId, AdminAction.REPLACE_ADMIN_KEY);
        return htmlMessage(chatId,
                "♻️ <b>Замена ключа</b>\n\n" +
                "Отправьте <b>название</b> ключа (как в списке) или его <b>ID</b>.\n" +
                "Ключ получит новую ссылку, старая перестанет работать. Срок и ID сохранятся.\n\n" +
                "<i>Отмена — /cancel</i>");
    }

    private void handleReplaceAdminKey(Long chatId, String text, List<BotApiMethod<?>> out) {
        if (text == null || text.isBlank()) {
            out.add(htmlMessage(chatId, "Отправьте название или ID ключа. <i>Отмена — /cancel</i>"));
            return;
        }
        List<VpnKey> candidates = findAdminKeysByNameOrId(text.trim());
        if (candidates.isEmpty()) {
            out.add(htmlMessage(chatId, "❌ Ключ «" + BotTextUtils.escapeHtml(text.trim()) + "» не найден среди созданных.\n" +
                    "Проверьте название в «📃 Созданные ключи» или отправьте ID. <i>Отмена — /cancel</i>"));
            return;
        }
        if (candidates.size() > 1) {
            StringBuilder sb = new StringBuilder("Нашлось несколько ключей — отправьте <b>ID</b> нужного:\n");
            for (VpnKey k : candidates) {
                sb.append("\n<code>").append(k.getId()).append("</code> · ")
                        .append(BotTextUtils.escapeHtml(k.getName() == null ? "" : k.getName()));
            }
            out.add(htmlMessage(chatId, sb.toString()));
            return;
        }
        VpnKey target = candidates.get(0);
        try {
            VpnKey fresh = vpnKeyService.rotateAdminKey(target.getId());
            adminStateService.clear(chatId);
            out.add(htmlMessage(chatId, "✅ <b>Ключ заменён</b>\n\n" + adminKeyCard(fresh) +
                    "\n<i>Старая ссылка больше не работает — отправьте человеку новую.</i>"));
        } catch (Exception e) {
            log.warn("Не удалось заменить админ-ключ {}: {}", target.getId(), e.getMessage());
            out.add(htmlMessage(chatId, "❌ Не удалось заменить ключ: " + BotTextUtils.escapeHtml(String.valueOf(e.getMessage())) +
                    "\nСтарая ссылка продолжает работать."));
        }
    }

    private List<VpnKey> findAdminKeysByNameOrId(String query) {
        List<VpnKey> all = vpnKeyService.listAdminCreatedKeys();
        Long id = parseLong(query);
        if (id != null) {
            List<VpnKey> byId = all.stream().filter(k -> id.equals(k.getId())).toList();
            if (!byId.isEmpty()) return byId;
        }
        String q = query.toLowerCase(java.util.Locale.ROOT);
        List<VpnKey> exact = all.stream()
                .filter(k -> k.getName() != null && k.getName().trim().toLowerCase(java.util.Locale.ROOT).equals(q))
                .toList();
        if (!exact.isEmpty()) return exact;
        return all.stream()
                .filter(k -> k.getName() != null && k.getName().toLowerCase(java.util.Locale.ROOT).contains(q))
                .toList();
    }

    public SendMessage replaceAllConfirm(Long chatId) {
        int n = vpnKeyService.listRotatableAdminKeys().size();
        var yes = org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                .text("✅ Да, заменить все").callbackData("ADMIN_REPLACE_ALL_CONFIRM").build();
        var no = org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                .text("✖️ Отмена").callbackData("ADMIN_REPLACE_ALL_CANCEL").build();
        SendMessage sm = htmlMessage(chatId,
                "♻️ <b>Заменить все созданные ключи?</b>\n\n" +
                "Активных ключей: <b>" + n + "</b>. Каждый получит новую ссылку, старые перестанут работать.\n" +
                "ID, названия и сроки сохранятся. Отозванные ключи не трогаю.\n\n" +
                "<i>После замены пришлю новый список со ссылками.</i>");
        sm.setReplyMarkup(org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup.builder()
                .keyboard(List.of(List.of(yes, no))).build());
        return sm;
    }

    /**
     * Запускает замену всех ключей в фоне (это десятки запросов к панели — не блокируем бота),
     * по окончании присылает итог и свежий список со ссылками.
     */
    public SendMessage startReplaceAll(Long chatId) {
        if (!rotateAllRunning.compareAndSet(false, true)) {
            return htmlMessage(chatId, "⏳ Замена уже идёт — дождитесь итога.");
        }
        List<VpnKey> keys = vpnKeyService.listRotatableAdminKeys();
        Thread worker = new Thread(() -> {
            int ok = 0;
            List<String> failed = new ArrayList<>();
            try {
                for (VpnKey k : keys) {
                    try {
                        vpnKeyService.rotateAdminKey(k.getId());
                        ok++;
                    } catch (Exception e) {
                        log.warn("Замена всех: ключ {} не заменён: {}", k.getId(), e.getMessage());
                        failed.add(k.getId() + (k.getName() != null ? " · " + k.getName() : ""));
                    }
                }
                StringBuilder sb = new StringBuilder("✅ <b>Замена завершена</b>\nЗаменено: <b>")
                        .append(ok).append("</b> из ").append(keys.size());
                if (!failed.isEmpty()) {
                    sb.append("\n\n⚠️ Не удалось (старые ссылки у них работают):");
                    failed.forEach(f -> sb.append("\n• ").append(BotTextUtils.escapeHtml(f)));
                }
                send(htmlMessage(chatId, sb.toString()));
                for (SendMessage m : buildAdminKeysMessages(chatId)) {
                    send(m);
                }
            } catch (Exception e) {
                log.error("Замена всех админ-ключей упала", e);
                send(htmlMessage(chatId, "❌ Замена прервана: " + BotTextUtils.escapeHtml(String.valueOf(e.getMessage()))));
            } finally {
                rotateAllRunning.set(false);
            }
        }, "admin-rotate-all");
        worker.setDaemon(true);
        worker.start();
        return htmlMessage(chatId, "⏳ Заменяю <b>" + keys.size() + "</b> ключей… Это займёт пару минут, пришлю итог и новый список.");
    }

    /**
     * Досоздаёт всех активных клиентов на всех настроенных сейчас inbound'ах (после добавления
     * нового inbound'а в панель). Долгая операция (по одному-двум HTTP-запросам на inbound на
     * каждый ключ) — запускаем в фоне, чтобы не блокировать бота, и присылаем итог.
     */
    public SendMessage startSyncInbounds(Long chatId) {
        if (!rotateAllRunning.compareAndSet(false, true)) {
            return htmlMessage(chatId, "⏳ Другая массовая операция уже идёт — дождитесь итога.");
        }
        Thread worker = new Thread(() -> {
            try {
                int ok = vpnKeyService.syncActiveKeysAcrossConfiguredInbounds();
                send(htmlMessage(chatId, "✅ <b>Синхронизация завершена</b>\nДосоздано/проверено ключей: <b>" + ok + "</b>."));
            } catch (Exception e) {
                log.error("Синхронизация инбаундов упала", e);
                send(htmlMessage(chatId, "❌ Синхронизация прервана: " + BotTextUtils.escapeHtml(String.valueOf(e.getMessage()))));
            } finally {
                rotateAllRunning.set(false);
            }
        }, "admin-sync-inbounds");
        worker.setDaemon(true);
        worker.start();
        return htmlMessage(chatId, "⏳ Досоздаю активные ключи на всех настроенных inbound'ах… Это может занять несколько минут, пришлю итог.");
    }

    private void send(SendMessage m) {
        try {
            if (mainBotProvider != null) {
                mainBotProvider.getObject().execute(m);
            }
        } catch (Exception e) {
            log.warn("Не удалось отправить сообщение админу: {}", e.getMessage());
        }
    }

    private String adminKeyCard(VpnKey key) {
        StringBuilder card = new StringBuilder("<blockquote><b>🆔 ").append(key.getId()).append("</b>");
        if (key.getName() != null && !key.getName().isBlank()) {
            card.append(" · <b>").append(BotTextUtils.escapeHtml(key.getName())).append("</b>");
        }
        card.append("\n").append(adminKeyStatus(key));
        Optional<Subscription> sub = subscriptionService.getActiveSubscription(key);
        if (sub.isPresent()) {
            card.append("\n🗓 ").append(formatDaysLeft(subscriptionService.getDaysLeft(sub.get())))
                    .append(" · до ").append(sub.get().getEndDate().toLocalDate().format(DATE_ONLY));
        } else {
            card.append("\n⌛ срок истёк / нет подписки");
        }
        card.append("</blockquote>");
        String link = null;
        try {
            link = vpnKeyService.currentSubscriptionLink(key);
        } catch (Exception e) {
            log.warn("Не удалось собрать ссылку для ключа {}: {}", key.getId(), e.getMessage());
        }
        if (link != null) {
            card.append("\n<code>").append(BotTextUtils.escapeHtml(link)).append("</code>\n");
        }
        return card.toString();
    }

    private static final java.time.format.DateTimeFormatter DATE_ONLY =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static SendMessage htmlMessage(Long chatId, String text) {
        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("HTML")
                .disableWebPagePreview(true)
                .build();
    }

    private SendMessage buildAdminKeyDeliveryMessage(Long chatId, VpnKey key, Subscription sub) {
        String until = sub == null ? "—" : BotTextUtils.formatDate(sub.getEndDate());
        boolean hasName = key.getName() != null && !key.getName().isBlank();
        String text = "✅ <b>Ключ создан</b>\n\n" +
                "<blockquote>" +
                (hasName ? "👤 " + BotTextUtils.escapeHtml(key.getName()) + "\n" : "") +
                "🆔 " + key.getId() + "\n" +
                "🗓 до " + until + "\n" +
                "🌐 VLESS · XHTTP · Trojan · gRPC" +
                "</blockquote>\n\n" +
                "🔗 <b>Ссылка для Happ</b>\n" +
                "<code>" + BotTextUtils.escapeHtml(key.getKeyValue()) + "</code>\n" +
                "<i>Нажмите на ссылку — она скопируется.</i>\n\n" +
                "<i>Продлить: «🔁 Продлить ключ» → </i><code>" + key.getId() + " 30</code>";
        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("HTML")
                .build();
    }

    private SendMessage buildAdminKeyLinkMessage(Long chatId, VpnKey key) {
        String text = "🔗 <b>Ссылка для Happ</b>\n" +
                "<code>" + BotTextUtils.escapeHtml(key.getKeyValue()) + "</code>\n" +
                "<i>Нажмите на ссылку — она скопируется.</i>";
        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("HTML")
                .build();
    }

    private String adminKeyStatus(VpnKey key) {
        if (key.isRevoked() || key.getStatus() == VpnKey.Status.REVOKED) {
            return "🛑 отозван (продлите, чтобы включить)";
        }
        return switch (key.getStatus()) {
            case ACTIVE -> "✅ активен";
            case PENDING -> "⏳ выпускается";
            case FAILED -> "⚠️ ошибка";
            case REVOKED -> "🛑 отозван";
        };
    }

    private Long parseLong(String raw) {
        if (raw == null) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void handleReferralLinkStats(Long chatId, String text, List<BotApiMethod<?>> out) {
        if (text == null || text.isBlank()) {
            out.add(BotMessageFactory.simpleMessage(chatId,
                    "Отправьте @username / telegram id пользователя или саму ссылку / код."));
            return;
        }

        String trimmed = text.trim();
        if (trimmed.startsWith("@") || trimmed.chars().allMatch(Character::isDigit)) {
            Optional<User> userOpt = findUserByIdentifier(firstTokenIdentifier(trimmed));
            if (userOpt.isEmpty()) {
                out.add(BotMessageFactory.simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
                return;
            }

            User user = userOpt.get();
            ReferralService.ReferralLinksStats stats = referralService.getTrackedLinksStats(user);
            adminStateService.clear(chatId);
            out.add(BotMessageFactory.simpleMessage(chatId, buildReferralStatsMessage(user, stats)));
            return;
        }

        Optional<ReferralService.TrackedReferralLinkStat> statOpt = referralService.findTrackedLinkStats(trimmed);
        if (statOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Реферальная ссылка не найдена."));
            return;
        }

        adminStateService.clear(chatId);
        out.add(BotMessageFactory.simpleMessage(chatId, buildSingleReferralLinkStatsMessage(statOpt.get())));
    }

    private void handleResetReferralLinkCounter(Long chatId, String text, List<BotApiMethod<?>> out) {
        if (text == null || text.isBlank()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Отправьте ссылку или код, чтобы обнулить счётчик."));
            return;
        }

        Optional<ReferralService.TrackedReferralLinkStat> statOpt = referralService.resetTrackedLinkCounter(text);
        if (statOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Реферальная ссылка не найдена."));
            return;
        }

        ReferralService.TrackedReferralLinkStat stat = statOpt.get();
        adminStateService.clear(chatId);
        out.add(BotMessageFactory.simpleMessage(chatId,
                "♻️ Счётчик переходов обнулён.\n" +
                        "Код: " + stat.code() + "\n" +
                        "Ссылка:\n" + referralService.buildReferralUrl(botUsername, stat.code())));
    }

    private void handleDeleteReferralLink(Long chatId, String text, List<BotApiMethod<?>> out) {
        if (text == null || text.isBlank()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Отправьте ссылку или код, чтобы удалить её."));
            return;
        }

        Optional<ReferralService.DeletedReferralLink> deletedOpt = referralService.deleteTrackedLink(text);
        if (deletedOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Реферальная ссылка не найдена."));
            return;
        }

        ReferralService.DeletedReferralLink deleted = deletedOpt.get();
        adminStateService.clear(chatId);
        out.add(BotMessageFactory.simpleMessage(chatId,
                "🗑 Реферальная ссылка удалена.\n" +
                        "Код: " + deleted.code() + "\n" +
                        "Удалённый счётчик переходов: " + deleted.transitionsCount()));
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

    private String buildReferralStatsMessage(User user, ReferralService.ReferralLinksStats stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Реферальные ссылки ").append(displayUser(user)).append("\n")
                .append("Всего пришло: ").append(stats.totalInvitedCount()).append("\n")
                .append("По обычной бонусной ссылке: ").append(stats.regularInvitedCount()).append("\n")
                .append("По уникальным трекинговым ссылкам: ").append(stats.trackedInvitedCount());

        if (stats.links().isEmpty()) {
            sb.append("\n\nУникальных ссылок пока нет.");
            return sb.toString();
        }

        for (int i = 0; i < stats.links().size(); i++) {
            ReferralService.TrackedReferralLinkStat link = stats.links().get(i);
            sb.append("\n\n")
                    .append(i + 1)
                    .append(") Переходов: ")
                    .append(link.invitedCount())
                    .append("\nСоздана: ")
                    .append(BotTextUtils.formatDate(link.createdAt()))
                    .append("\nКод: ")
                    .append(link.code())
                    .append("\nСсылка:\n")
                    .append(referralService.buildReferralUrl(botUsername, link.code()));
        }
        return sb.toString();
    }

    private String buildSingleReferralLinkStatsMessage(ReferralService.TrackedReferralLinkStat link) {
        return "📊 Статистика реферальной ссылки\n" +
                "Переходов: " + link.invitedCount() + "\n" +
                "Бонусные дни по этой ссылке не начисляются.\n" +
                "Создана: " + BotTextUtils.formatDate(link.createdAt()) + "\n" +
                "Код: " + link.code() + "\n" +
                "Ссылка:\n" + referralService.buildReferralUrl(botUsername, link.code());
    }

    private String displayUser(User user) {
        if (user == null) return "пользователь";
        String username = user.getUsername();
        if (username != null && !username.isBlank()) {
            return username.startsWith("@") ? username : "@" + username;
        }
        if (user.getTelegramId() != null) {
            return "tg_" + user.getTelegramId();
        }
        return "user_" + user.getId();
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

    private String firstTokenIdentifier(String raw) {
        return firstTokenUsername(raw);
    }

    private Integer parseDays(String raw) {
        if (raw == null) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
