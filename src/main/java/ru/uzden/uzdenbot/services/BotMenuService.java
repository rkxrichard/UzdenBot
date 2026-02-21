package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import ru.uzden.uzdenbot.config.SubscriptionPlansProperties;
import ru.uzden.uzdenbot.entities.Subscription;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.repositories.UserRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BotMenuService {

    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;
    private final SubscriptionPlansProperties subscriptionPlansProperties;
    private final PaymentService paymentService;
    private final VpnKeyService vpnKeyService;

    @Value("${telegram.main-menu-text:Добро пожаловать в Uzden.\\n\\nЗдесь всё просто: управляйте подпиской и получайте доступ к сервису в пару нажатий.\\n\\nВыберите нужный раздел ниже.}")
    private String mainMenuText;

    @Value("${telegram.instructions-text:Инструкция}")
    private String instructionsText;

    @Value("${telegram.support-username:@support}")
    private String supportUsername;

    private static final DateTimeFormatter DT_FMT   = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public SendMessage mainMenu(Long chatId, boolean isAdmin, User user) {
        vpnKeyService.ensureKeyForActiveSubscription(user);
        boolean hasAnySubscription = user != null && subscriptionService.getLastSubscription(user).isPresent();

        InlineKeyboardButton b1 = InlineKeyboardButton.builder()
                .text("📦 Подписка и тарифы")
                .callbackData("MENU_SUBSCRIPTION")
                .build();
        InlineKeyboardButton bKeys = InlineKeyboardButton.builder()
                .text("🔑 Мои ключи")
                .callbackData("MENU_KEYS")
                .build();
        InlineKeyboardButton bAdmin = InlineKeyboardButton.builder()
                .text("🛠 Админ‑панель")
                .callbackData("MENU_ADMIN")
                .build();
        InlineKeyboardButton bHelp = InlineKeyboardButton.builder()
                .text("📘 Инструкция")
                .callbackData("MENU_HELP")
                .build();
        InlineKeyboardButton bReferral = InlineKeyboardButton.builder()
                .text("🎁 Пригласить друга")
                .callbackData("MENU_REFERRAL")
                .build();
        InlineKeyboardButton bSupport = InlineKeyboardButton.builder()
                .text("💬 Техподдержка")
                .url(buildSupportUrl())
                .build();

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(b1));
        if (hasAnySubscription) {
            rows.add(List.of(bKeys));
        }
        rows.add(List.of(bHelp));
        rows.add(List.of(bReferral));
        rows.add(List.of(bSupport));
        if (isAdmin) {
            rows.add(List.of(bAdmin));
        }

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();

        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(mainMenuText + buildMainMenuKeysSummary(user))
                .replyMarkup(markup)
                .build();
    }

    public SendMessage commandKeyboardMessage(Long chatId, boolean isAdmin) {
        return SendMessage.builder()
                .chatId(chatId.toString())
                .text("\u200B")
                .replyMarkup(buildCommandKeyboard(isAdmin))
                .build();
    }

    public SendMessage adminMenu(Long chatId) {
        InlineKeyboardButton bAddSub = InlineKeyboardButton.builder()
                .text("➕ Выдать подписку")
                .callbackData("ADMIN_ADD_SUB")
                .build();
        InlineKeyboardButton bCheckSub = InlineKeyboardButton.builder()
                .text("🔎 Проверить подписку")
                .callbackData("ADMIN_CHECK_SUB")
                .build();
        InlineKeyboardButton bActiveUsers = InlineKeyboardButton.builder()
                .text("👥 Активные пользователи")
                .callbackData("ADMIN_ACTIVE_USERS")
                .build();
        InlineKeyboardButton bBroadcast = InlineKeyboardButton.builder()
                .text("📣 Рассылка")
                .callbackData("ADMIN_BROADCAST")
                .build();
        InlineKeyboardButton bRevokeSub = InlineKeyboardButton.builder()
                .text("🛑 Отключить подписку")
                .callbackData("ADMIN_REVOKE_SUB")
                .build();
        InlineKeyboardButton bDisableUser = InlineKeyboardButton.builder()
                .text("🚫 Заблокировать пользователя")
                .callbackData("ADMIN_DISABLE_USER")
                .build();
        InlineKeyboardButton bEnableUser = InlineKeyboardButton.builder()
                .text("✅ Разблокировать пользователя")
                .callbackData("ADMIN_ENABLE_USER")
                .build();
        InlineKeyboardButton bPurgeDisabled = InlineKeyboardButton.builder()
                .text("🧹 Удалить отключённых клиентов")
                .callbackData("ADMIN_PURGE_DISABLED_KEYS")
                .build();
        InlineKeyboardButton bBack = InlineKeyboardButton.builder()
                .text("⬅️ Назад")
                .callbackData("MENU_BACK")
                .build();

        InlineKeyboardMarkup keyboardMarkup = InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        List.of(bAddSub),
                        List.of(bCheckSub),
                        List.of(bActiveUsers),
                        List.of(bBroadcast),
                        List.of(bRevokeSub),
                        List.of(bDisableUser),
                        List.of(bEnableUser),
                        List.of(bPurgeDisabled),
                        List.of(bBack)
                ))
                .build();

        return SendMessage.builder()
                .chatId(chatId.toString())
                .text("🛠 Админ-меню")
                .replyMarkup(keyboardMarkup)
                .build();
    }

    public SendMessage instructionsMenu(Long chatId) {
        InlineKeyboardButton bBack = InlineKeyboardButton.builder()
                .text("⬅️ Назад")
                .callbackData("MENU_BACK")
                .build();

        InlineKeyboardMarkup keyboardMarkup = InlineKeyboardMarkup.builder()
                .keyboard(List.of(List.of(bBack)))
                .build();

        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(instructionsText)
                .replyMarkup(keyboardMarkup)
                .build();
    }

    public SendMessage referralMenu(Long chatId, User user, String botUsername) {
        String code = (user == null) ? null : user.getReferralCode();
        if (code == null || code.isBlank()) {
            code = "";
        }
        String bot = botUsername == null ? "" : botUsername.trim();
        String link = "https://t.me/" + bot + "?start=ref_" + code;
        String text = "🎁 Рефералы\n" +
                "━━━━━━━━━━━━\n" +
                "✅ Вам: +7 дней\n" +
                "✅ Другу: +3 дня\n\n" +
                "🔗 Ссылка:\n" + link + "\n" +
                "🔑 Код: " + code;

        InlineKeyboardButton bBack = InlineKeyboardButton.builder()
                .text("⬅️ Назад")
                .callbackData("MENU_BACK")
                .build();

        InlineKeyboardMarkup keyboardMarkup = InlineKeyboardMarkup.builder()
                .keyboard(List.of(List.of(bBack)))
                .build();

        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .replyMarkup(keyboardMarkup)
                .build();
    }

    public SendMessage subscriptionMenu(Long chatId) {
        User user = userRepository.findUserByTelegramId(chatId)
                .orElseThrow(() -> new IllegalStateException("User not found for chatId: " + chatId));

        reconcileUserPaymentsSafe(user);
        vpnKeyService.ensureKeyForActiveSubscription(user);

        Optional<Subscription> activeSubOpt = subscriptionService.getActiveSubscription(user);
        Optional<Subscription> lastSubOpt = subscriptionService.getLastSubscription(user);
        List<VpnKey> keys = vpnKeyService.listUserKeys(user);
        boolean hasKeys = !keys.isEmpty();

        boolean isActive = activeSubOpt.isPresent();
        boolean wasExpired = !isActive && lastSubOpt.isPresent()
                && lastSubOpt.get().getEndDate() != null
                && lastSubOpt.get().getEndDate().isBefore(LocalDateTime.now());
        String buyOrExtendText = isActive
                ? "Продлить подписку"
                : (wasExpired ? "Возобновить подписку" : "Купить подписку");
        String menuText = buildSubscriptionMenuText(activeSubOpt, lastSubOpt);

        InlineKeyboardButton bMyKeys = InlineKeyboardButton.builder()
                .text("🔑 Мои ключи")
                .callbackData("MENU_KEYS")
                .build();

//        InlineKeyboardButton b2 = InlineKeyboardButton.builder()
//                .text("Остаток дней")
//                .callbackData("MENU_STATUS")
//                .build();

        InlineKeyboardButton bBack = InlineKeyboardButton.builder()
                .text("⬅️ Назад")
                .callbackData("MENU_BACK")
                .build();



        InlineKeyboardMarkup keyboardMarkup;
        if (hasKeys) {
            keyboardMarkup = InlineKeyboardMarkup.builder()
                    .keyboard(List.of(
                            List.of(bMyKeys),
                            List.of(bBack)
                    ))
                    .build();
        } else {
            InlineKeyboardButton bBuy = InlineKeyboardButton.builder()
                    .text(buyOrExtendText)
                    .callbackData("MENU_BUY")
                    .build();
            keyboardMarkup = InlineKeyboardMarkup.builder()
                    .keyboard(List.of(
                            List.of(bBuy, bBack)
                    ))
                    .build();
        }


        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(menuText)
                .replyMarkup(keyboardMarkup)
                .build();
    }

    public SendMessage subscriptionPlanMenu(Long chatId) {
        User user = userRepository.findUserByTelegramId(chatId)
                .orElseThrow(() -> new IllegalStateException("User not found for chatId: " + chatId));
        reconcileUserPaymentsSafe(user);
        Optional<Subscription> activeSubOpt = subscriptionService.getActiveSubscription(user);
        Optional<Subscription> lastSubOpt = subscriptionService.getLastSubscription(user);
        String baseText = buildSubscriptionMenuText(activeSubOpt, lastSubOpt);

        SubscriptionPlansProperties.Plan p1 = subscriptionPlansProperties.getPlan1();
        SubscriptionPlansProperties.Plan p2 = subscriptionPlansProperties.getPlan2();
        int price1 = p1.getPrice();
        int price2 = p2.getPrice();
        int baseMonthlyPrice = p1.getPrice();
        String label1 = normalizeLabel(p1.getLabel(), "1 месяц");
        String label2 = normalizeLabel(p2.getLabel(), "2 месяца");

        String text = baseText + "\n\n" +
                "💳 Тарифы\n" +
                "━━━━━━━━━━━━\n" +
                "• " + label1 + " — " + price1 + "₽\n" +
                "• " + label2 + " — " + price2 + "₽ (−" + discountPercent(baseMonthlyPrice, price2, p2.getMonths()) + "%)\n" +
                "\nВыберите срок ниже 👇";

        InlineKeyboardButton b1 = InlineKeyboardButton.builder()
                .text("💳 " + label1 + " — " + price1 + "₽")
                .callbackData("BUY_1M")
                .build();
        InlineKeyboardButton b2 = InlineKeyboardButton.builder()
                .text("🔥 " + label2 + " — " + price2 + "₽ (−" + discountPercent(baseMonthlyPrice, price2, p2.getMonths()) + "%)")
                .callbackData("BUY_2M")
                .build();
        InlineKeyboardButton bBack = InlineKeyboardButton.builder()
                .text("⬅️ Назад")
                .callbackData("MENU_SUBSCRIPTION")
                .build();

        InlineKeyboardMarkup keyboardMarkup = InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        List.of(b1),
                        List.of(b2),
                        List.of(bBack)
                ))
                .build();

        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .replyMarkup(keyboardMarkup)
                .build();
    }

    public SendMessage keyPlanMenu(Long chatId, User user, Long keyId, boolean isNewKey) {
        SubscriptionPlansProperties.Plan p1 = subscriptionPlansProperties.getPlan1();
        SubscriptionPlansProperties.Plan p2 = subscriptionPlansProperties.getPlan2();
        int baseMonthlyPrice = p1.getPrice();
        String label1 = normalizeLabel(p1.getLabel(), "1 месяц");
        String label2 = normalizeLabel(p2.getLabel(), "2 месяца");

        String title = isNewKey ? "Новый ключ" : "Продление ключа";
        String keyInfo = "";
        if (!isNewKey && keyId != null) {
            try {
                VpnKey key = vpnKeyService.findKeyForUser(user, keyId);
                String daysInfo = keyDaysLeftText(key);
                int idx = resolveKeyIndex(user, keyId);
                String label = idx > 0 ? "Ключ №" + idx : "Ключ";
                keyInfo = "\n" + label + " • " + daysInfo;
            } catch (Exception e) {
                InlineKeyboardButton bBack = InlineKeyboardButton.builder()
                        .text("⬅️ Назад")
                        .callbackData("MENU_KEYS")
                        .build();
                InlineKeyboardMarkup keyboardMarkup = InlineKeyboardMarkup.builder()
                        .keyboard(List.of(List.of(bBack)))
                        .build();
                return SendMessage.builder()
                        .chatId(chatId.toString())
                        .text("Ключ не найден.")
                        .replyMarkup(keyboardMarkup)
                        .build();
            }
        }

        String text = title + keyInfo + "\n\n" +
                "💳 Тарифы\n" +
                "━━━━━━━━━━━━\n" +
                "• " + label1 + " — " + p1.getPrice() + "₽\n" +
                "• " + label2 + " — " + p2.getPrice() + "₽ (−" + discountPercent(baseMonthlyPrice, p2.getPrice(), p2.getMonths()) + "%)\n" +
                "\nВыберите срок ниже 👇";

        InlineKeyboardButton b1 = InlineKeyboardButton.builder()
                .text("💳 " + label1 + " — " + p1.getPrice() + "₽")
                .callbackData(isNewKey ? "KEY_NEW_BUY_1M" : "KEY_RENEW_1M:" + keyId)
                .build();
        InlineKeyboardButton b2 = InlineKeyboardButton.builder()
                .text("🔥 " + label2 + " — " + p2.getPrice() + "₽ (−" + discountPercent(baseMonthlyPrice, p2.getPrice(), p2.getMonths()) + "%)")
                .callbackData(isNewKey ? "KEY_NEW_BUY_2M" : "KEY_RENEW_2M:" + keyId)
                .build();
        InlineKeyboardButton bBack = InlineKeyboardButton.builder()
                .text("⬅️ Назад")
                .callbackData("MENU_KEYS")
                .build();

        InlineKeyboardMarkup keyboardMarkup = InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        List.of(b1),
                        List.of(b2),
                        List.of(bBack)
                ))
                .build();

        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .replyMarkup(keyboardMarkup)
                .build();
    }

    public SendMessage myKeysMenu(Long chatId, User user) {
        vpnKeyService.ensureKeyForActiveSubscription(user);
        List<VpnKey> keys = vpnKeyService.listUserKeys(user);
        int maxKeys = vpnKeyService.getMaxKeysPerUser();

        StringBuilder text = new StringBuilder("🔑 Мои ключи\n━━━━━━━━━━━━\n");
        if (keys.isEmpty()) {
            text.append("У вас пока нет ключей");
        } else {
            for (int i = 0; i < keys.size(); i++) {
                VpnKey key = keys.get(i);
                text.append(i + 1)
                        .append(") ")
                        .append(keyStatusLabel(key))
                        .append(" • ")
                        .append(keyDaysLeftText(key))
                        .append("\n");
            }
        }
        if (keys.size() < maxKeys) {
            text.append("\nМожно создать новый ключ (макс ").append(maxKeys).append(")");
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            VpnKey key = keys.get(i);
            InlineKeyboardButton b = InlineKeyboardButton.builder()
                    .text("🔑 Ключ " + (i + 1))
                    .callbackData("KEY_SELECT:" + key.getId())
                    .build();
            rows.add(List.of(b));
        }

        if (keys.size() < maxKeys) {
            InlineKeyboardButton bNew = InlineKeyboardButton.builder()
                    .text("➕ Новый ключ")
                    .callbackData("KEY_NEW")
                    .build();
            rows.add(List.of(bNew));
        }

        InlineKeyboardButton bBack = InlineKeyboardButton.builder()
                .text("⬅️ Назад")
                .callbackData("MENU_BACK")
                .build();
        rows.add(List.of(bBack));

        InlineKeyboardMarkup keyboardMarkup = InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();

        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(text.toString())
                .replyMarkup(keyboardMarkup)
                .build();
    }

    public SendMessage keyActionsMenu(Long chatId, User user, long keyId) {
        vpnKeyService.ensureKeyForActiveSubscription(user);
        List<VpnKey> keys = vpnKeyService.listUserKeys(user);
        int index = -1;
        VpnKey target = null;
        for (int i = 0; i < keys.size(); i++) {
            VpnKey key = keys.get(i);
            if (key.getId() != null && key.getId() == keyId) {
                index = i;
                target = key;
                break;
            }
        }
        if (target == null) {
            InlineKeyboardButton bBack = InlineKeyboardButton.builder()
                    .text("⬅️ Назад")
                    .callbackData("MENU_KEYS")
                    .build();
            InlineKeyboardMarkup keyboardMarkup = InlineKeyboardMarkup.builder()
                    .keyboard(List.of(List.of(bBack)))
                    .build();
            return SendMessage.builder()
                    .chatId(chatId.toString())
                    .text("Ключ не найден.")
                    .replyMarkup(keyboardMarkup)
                    .build();
        }

        String created = formatInstant(target.getCreatedAt());
        Optional<Subscription> keySubOpt = subscriptionService.getActiveSubscription(target);
        String text = "🔑 Ключ №" + (index + 1) + "\n" +
                "━━━━━━━━━━━━\n" +
                "Статус: " + keyStatusLabel(target) + "\n" +
                "Срок: " + keyDaysLeftText(target) + "\n" +
                "Создан: " + created +
                (keySubOpt.isPresent() ? "\nУдаление после окончания срока" : "");

        InlineKeyboardButton bGet = InlineKeyboardButton.builder()
                .text("📋 Получить ключ")
                .callbackData("KEY_GET:" + target.getId())
                .build();
        InlineKeyboardButton bReplace = InlineKeyboardButton.builder()
                .text("♻️ Заменить ключ")
                .callbackData("KEY_REPLACE:" + target.getId())
                .build();
        InlineKeyboardButton bRenew = InlineKeyboardButton.builder()
                .text("🔁 Продлить")
                .callbackData("KEY_RENEW:" + target.getId())
                .build();
        InlineKeyboardButton bBack = InlineKeyboardButton.builder()
                .text("⬅️ Назад")
                .callbackData("MENU_KEYS")
                .build();

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(bGet));
        rows.add(List.of(bReplace));
        rows.add(List.of(bRenew));
        if (keySubOpt.isEmpty()) {
            InlineKeyboardButton bDelete = InlineKeyboardButton.builder()
                    .text("🗑 Удалить ключ")
                    .callbackData("KEY_DELETE:" + target.getId())
                    .build();
            rows.add(List.of(bDelete));
        }
        rows.add(List.of(bBack));

        InlineKeyboardMarkup keyboardMarkup = InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();

        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .replyMarkup(keyboardMarkup)
                .build();
    }

    private String buildSubscriptionMenuText(Optional<Subscription> activeSubOpt, Optional<Subscription> lastSubOpt) {
        if (activeSubOpt.isEmpty()) {
            if (lastSubOpt.isPresent() && lastSubOpt.get().getEndDate() != null
                    && lastSubOpt.get().getEndDate().isBefore(LocalDateTime.now())) {
                String endedAt = lastSubOpt.get().getEndDate().format(DT_FMT);
                return "📦 Подписка\n" +
                        "━━━━━━━━━━━━\n" +
                        "Статус: истекла\n" +
                        "До: " + endedAt;
            }
            return "📦 Подписка\n" +
                    "━━━━━━━━━━━━\n" +
                    "Статус: нет активной";
        }

        Subscription sub = activeSubOpt.get();
        long daysLeft = subscriptionService.getDaysLeft(sub);

        // Если хочешь показывать только дату: sub.getEndDate().toLocalDate().format(DATE_FMT)
        String until = sub.getEndDate().format(DT_FMT);

        return "📦 Подписка\n" +
                "━━━━━━━━━━━━\n" +
                "Статус: активна\n" +
                "Осталось: " + formatDaysLeft(daysLeft) + "\n" +
                "До: " + until;
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

    private int discountPercent(int baseMonthlyPrice, int planPrice, int months) {
        if (months <= 1 || baseMonthlyPrice <= 0) return 0;
        double baseTotal = baseMonthlyPrice * (double) months;
        if (baseTotal <= 0) return 0;
        double discount = 100.0 - (planPrice / baseTotal) * 100.0;
        int rounded = (int) Math.round(discount / 5.0) * 5;
        return Math.max(0, rounded);
    }

    private String normalizeLabel(String label, String fallback) {
        if (label == null || label.isBlank()) return fallback;
        return label;
    }

    private String buildMainMenuKeysSummary(User user) {
        if (user == null) return "";
        List<VpnKey> keys = vpnKeyService.listUserKeys(user);
        if (keys.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nКлючи\n");
        for (int i = 0; i < keys.size(); i++) {
            VpnKey key = keys.get(i);
            sb.append("№").append(i + 1).append(" — ").append(shortKeyDays(key));
            if (i + 1 < keys.size()) sb.append("\n");
        }
        return sb.toString();
    }

    private String shortKeyDays(VpnKey key) {
        Optional<Subscription> active = subscriptionService.getActiveSubscription(key);
        if (active.isPresent()) {
            long days = subscriptionService.getDaysLeft(active.get());
            return days + "д";
        }
        Optional<Subscription> last = subscriptionService.getLastSubscription(key);
        if (last.isPresent()) {
            return "0д";
        }
        return "-";
    }

    private String keyDaysLeftText(VpnKey key) {
        Optional<Subscription> active = subscriptionService.getActiveSubscription(key);
        if (active.isPresent()) {
            long days = subscriptionService.getDaysLeft(active.get());
            String until = active.get().getEndDate().format(DT_FMT);
            return formatDaysLeft(days) + " • до " + until;
        }
        Optional<Subscription> last = subscriptionService.getLastSubscription(key);
        if (last.isPresent() && last.get().getEndDate() != null) {
            String endedAt = last.get().getEndDate().format(DT_FMT);
            return "истекла • " + endedAt;
        }
        return "нет подписки";
    }

    private int resolveKeyIndex(User user, Long keyId) {
        if (user == null || keyId == null) return -1;
        List<VpnKey> keys = vpnKeyService.listUserKeys(user);
        for (int i = 0; i < keys.size(); i++) {
            VpnKey key = keys.get(i);
            if (keyId.equals(key.getId())) {
                return i + 1;
            }
        }
        return -1;
    }

    private String keyStatusLabel(VpnKey key) {
        if (key == null) return "неизвестно";
        if (key.isRevoked() || key.getStatus() == VpnKey.Status.REVOKED) return "отозван";
        return switch (key.getStatus()) {
            case ACTIVE -> "активен";
            case PENDING -> "выпускается";
            case FAILED -> "ошибка";
            case REVOKED -> "отозван";
        };
    }

    private String formatInstant(Instant instant) {
        if (instant == null) return "-";
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(DT_FMT);
    }

    private String buildSupportUrl() {
        String u = supportUsername == null ? "" : supportUsername.trim();
        if (u.isEmpty()) {
            return "https://t.me";
        }
        if (u.startsWith("@")) {
            u = u.substring(1);
        }
        return "https://t.me/" + u;
    }

    private void reconcileUserPaymentsSafe(User user) {
        try {
            paymentService.reconcileUserPayments(user);
        } catch (Exception ignored) {
            // Do not block menu rendering if reconciliation fails
        }
    }

    private ReplyKeyboardMarkup buildCommandKeyboard(boolean isAdmin) {
        List<KeyboardRow> rows = new ArrayList<>();
        KeyboardRow row1 = new KeyboardRow();
        row1.add(KeyboardButton.builder().text("/start").build());
        rows.add(row1);

        if (isAdmin) {
            KeyboardRow row2 = new KeyboardRow();
            row2.add(KeyboardButton.builder().text("/admin").build());
            row2.add(KeyboardButton.builder().text("/cancel").build());
            rows.add(row2);
        }

        return ReplyKeyboardMarkup.builder()
                .keyboard(rows)
                .resizeKeyboard(true)
                .build();
    }
}
