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

    public SendMessage buildAdminKeysMessage(Long chatId) {
        List<VpnKey> keys = vpnKeyService.listAdminCreatedKeys();
        if (keys.isEmpty()) {
            return BotMessageFactory.simpleMessage(chatId, "Созданных админом ключей пока нет.");
        }
        StringBuilder sb = new StringBuilder("📃 Созданные ключи (" + keys.size() + "):\n");
        for (VpnKey key : keys) {
            sb.append("\nID ").append(key.getId());
            if (key.getName() != null && !key.getName().isBlank()) {
                sb.append(" • ").append(key.getName());
            }
            sb.append("\nСтатус: ").append(adminKeyStatus(key));
            Optional<Subscription> sub = subscriptionService.getActiveSubscription(key);
            if (sub.isPresent()) {
                long daysLeft = subscriptionService.getDaysLeft(sub.get());
                sb.append("\nСрок: ").append(formatDaysLeft(daysLeft))
                        .append(" (до ").append(BotTextUtils.formatDate(sub.get().getEndDate())).append(")");
            } else {
                sb.append("\nСрок: истёк / нет подписки");
            }
            sb.append("\n");
        }
        sb.append("\nПродление — «🔁 Продлить ключ», формат: ID дней (например: ")
                .append(keys.get(0).getId()).append(" 30).");
        return BotMessageFactory.simpleMessage(chatId, sb.toString().trim());
    }

    private SendMessage buildAdminKeyDeliveryMessage(Long chatId, VpnKey key, Subscription sub) {
        String until = sub == null ? "-" : BotTextUtils.formatDate(sub.getEndDate());
        String text = "✅ Ключ создан.\n" +
                "ID: " + key.getId() + "\n" +
                (key.getName() != null && !key.getName().isBlank()
                        ? "Имя: " + BotTextUtils.escapeHtml(key.getName()) + "\n" : "") +
                "🗓 Действует до: " + until + "\n" +
                "Протоколы: VLESS, XHTTP, Trojan, gRPC\n\n" +
                "🔗 Subscription-ссылка:\n" +
                "<code>" + BotTextUtils.escapeHtml(key.getKeyValue()) + "</code>\n\n" +
                "Продлить — «🔁 Продлить ключ», формат: " + key.getId() + " дней.";
        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("HTML")
                .build();
    }

    private SendMessage buildAdminKeyLinkMessage(Long chatId, VpnKey key) {
        String text = "🔗 Subscription-ссылка:\n<code>" + BotTextUtils.escapeHtml(key.getKeyValue()) + "</code>";
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
