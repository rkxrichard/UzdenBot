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
import ru.uzden.uzdenbot.entities.Subscription;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.repositories.UserRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BotMenuService {

    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;

    @Value("${telegram.main-menu-text:Добро пожаловать в Uzden.\\n\\nЗдесь всё просто: управляйте подпиской и получайте доступ к сервису в пару нажатий.\\n\\nВыберите нужный раздел ниже.}")
    private String mainMenuText;

    @Value("${telegram.instructions-text:Инструкция}")
    private String instructionsText;

    private static final DateTimeFormatter DT_FMT   = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public SendMessage mainMenu(Long chatId, boolean isAdmin) {
        InlineKeyboardButton b1 = InlineKeyboardButton.builder()
                .text("📦 Подписка и тарифы")
                .callbackData("MENU_SUBSCRIPTION")
                .build();
        InlineKeyboardButton bAdmin = InlineKeyboardButton.builder()
                .text("🛠 Админ‑панель")
                .callbackData("MENU_ADMIN")
                .build();
        InlineKeyboardButton bHelp = InlineKeyboardButton.builder()
                .text("📘 Инструкция")
                .callbackData("MENU_HELP")
                .build();

        List<List<InlineKeyboardButton>> rows = isAdmin
                ? List.of(List.of(b1), List.of(bHelp), List.of(bAdmin))
                : List.of(List.of(b1), List.of(bHelp));

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();

        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(mainMenuText)
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

    public SendMessage subscriptionMenu(Long chatId) {
        User user = userRepository.findUserByTelegramId(chatId)
                .orElseThrow(() -> new IllegalStateException("User not found for chatId: " + chatId));

        Optional<Subscription> activeSubOpt = subscriptionService.getActiveSubscription(user);
        Optional<Subscription> lastSubOpt = subscriptionService.getLastSubscription(user);

        boolean isActive = activeSubOpt.isPresent();
        boolean wasExpired = !isActive && lastSubOpt.isPresent()
                && lastSubOpt.get().getEndDate() != null
                && lastSubOpt.get().getEndDate().isBefore(LocalDateTime.now());
        String buyOrExtendText = isActive
                ? "🔁 Продлить подписку"
                : (wasExpired ? "🔁 Возобновить подписку" : "💳 Купить подписку");
        String menuText = buildSubscriptionMenuText(activeSubOpt, lastSubOpt);

        InlineKeyboardButton bBuy = InlineKeyboardButton.builder()
                .text(buyOrExtendText)
                .callbackData("MENU_BUY")
                .build();

        InlineKeyboardButton bGetKey = InlineKeyboardButton.builder()
                .text("🔑 Получить ключ")
                .callbackData("MENU_GET_KEY")
                .build();

        InlineKeyboardButton bReplaceKey = InlineKeyboardButton.builder()
                .text("♻️ Заменить ключ")
                .callbackData("MENU_REPLACE_KEY")
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
        if (isActive) {
            // Активна: дать кнопку ключа + продление
            keyboardMarkup = InlineKeyboardMarkup.builder()
                    .keyboard(List.of(
                            List.of(bGetKey),
                            List.of(bReplaceKey),
                            List.of(bBuy),
                            List.of(bBack)
                    ))
                    .build();
        } else {
            // Нет подписки: купить + назад
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
        Optional<Subscription> activeSubOpt = subscriptionService.getActiveSubscription(user);
        Optional<Subscription> lastSubOpt = subscriptionService.getLastSubscription(user);
        String baseText = buildSubscriptionMenuText(activeSubOpt, lastSubOpt);

        int baseMonthlyPrice = 149;
        Plan p1 = new Plan(1, 149);
        Plan p2 = new Plan(2, 249);

        String text = baseText + "\n\n" +
                "💳 Тарифы\n" +
                "━━━━━━━━━━━━\n" +
                "Выберите срок — подписка активируется или продлевается сразу.\n\n" +
                "• 1 месяц — 149₽\n" +
                "• 2 месяца — 249₽ (скидка " + discountPercent(baseMonthlyPrice, p2) + "%)\n\n" +
                "⭐ Выгоднее брать 2 месяца.";

        InlineKeyboardButton b1 = InlineKeyboardButton.builder()
                .text("💳 1 месяц — 149₽")
                .callbackData("BUY_1M")
                .build();
        InlineKeyboardButton b2 = InlineKeyboardButton.builder()
                .text("🔥 2 месяца — 249₽ (" + discountPercent(baseMonthlyPrice, p2) + "%)")
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

    private String buildSubscriptionMenuText(Optional<Subscription> activeSubOpt, Optional<Subscription> lastSubOpt) {
        if (activeSubOpt.isEmpty()) {
            if (lastSubOpt.isPresent() && lastSubOpt.get().getEndDate() != null
                    && lastSubOpt.get().getEndDate().isBefore(LocalDateTime.now())) {
                String endedAt = lastSubOpt.get().getEndDate().format(DT_FMT);
                return "📦 Подписка\n\n" +
                        "━━━━━━━━━━━━\n" +
                        "Статус: истекла\n" +
                        "🗓 Закончилась: " + endedAt + "\n" +
                        "Возобновите подписку, чтобы снова получить доступ.";
            }
            return "📦 Подписка\n\n" +
                    "━━━━━━━━━━━━\n" +
                    "Статус: нет активной подписки\n" +
                    "Выберите срок ниже и оформите покупку.\n" +
                    "После оформления сможете получить ключ.";
        }

        Subscription sub = activeSubOpt.get();
        long daysLeft = subscriptionService.getDaysLeft(sub);

        // Если хочешь показывать только дату: sub.getEndDate().toLocalDate().format(DATE_FMT)
        String until = sub.getEndDate().format(DT_FMT);

        return "📦 Подписка\n\n" +
                "━━━━━━━━━━━━\n" +
                "Статус: активна\n" +
                "⏳ Осталось: " + formatDaysLeft(daysLeft) + "\n" +
                "🗓 Действует до: " + until + "\n" +
                "Управление ключом и продление — ниже.";
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

    private int discountPercent(int baseMonthlyPrice, Plan plan) {
        if (plan.months <= 1 || baseMonthlyPrice <= 0) return 0;
        double baseTotal = baseMonthlyPrice * (double) plan.months;
        if (baseTotal <= 0) return 0;
        double discount = 100.0 - (plan.price / baseTotal) * 100.0;
        int rounded = (int) Math.round(discount / 5.0) * 5;
        return Math.max(0, rounded);
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

    private static final class Plan {
        final int months;
        final int price;

        private Plan(int months, int price) {
            this.months = months;
            this.price = price;
        }
    }
}
