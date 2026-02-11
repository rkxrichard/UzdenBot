package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import ru.uzden.uzdenbot.entities.Subscription;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.repositories.UserRepository;

import java.time.format.DateTimeFormatter;
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
                .text("Подписка")
                .callbackData("MENU_SUBSCRIPTION")
                .build();
        InlineKeyboardButton bAdmin = InlineKeyboardButton.builder()
                .text("Админка")
                .callbackData("MENU_ADMIN")
                .build();
        InlineKeyboardButton bHelp = InlineKeyboardButton.builder()
                .text("Инструкция")
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

    public SendMessage adminMenu(Long chatId) {
        InlineKeyboardButton bAddSub = InlineKeyboardButton.builder()
                .text("➕ Добавить подписку")
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
                .text("🚫 Отключить пользователя")
                .callbackData("ADMIN_DISABLE_USER")
                .build();
        InlineKeyboardButton bEnableUser = InlineKeyboardButton.builder()
                .text("✅ Включить пользователя")
                .callbackData("ADMIN_ENABLE_USER")
                .build();
        InlineKeyboardButton bBack = InlineKeyboardButton.builder()
                .text("Назад")
                .callbackData("MENU_BACK")
                .build();

        InlineKeyboardMarkup keyboardMarkup = InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        List.of(bAddSub),
                        List.of(bCheckSub),
                        List.of(bRevokeSub),
                        List.of(bDisableUser),
                        List.of(bEnableUser),
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
                .text("Назад")
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

        boolean isActive = activeSubOpt.isPresent();
        String buyOrExtendText = isActive ? "Продлить подписку" : "Купить подписку";
        String menuText = buildSubscriptionMenuText(activeSubOpt);

        InlineKeyboardButton bBuy = InlineKeyboardButton.builder()
                .text(buyOrExtendText)
                .callbackData("MENU_BUY")
                .build();

        InlineKeyboardButton bGetKey = InlineKeyboardButton.builder()
                .text("Получить ключ")
                .callbackData("MENU_GET_KEY")
                .build();

        InlineKeyboardButton bReplaceKey = InlineKeyboardButton.builder()
                .text("Заменить ключ")
                .callbackData("MENU_REPLACE_KEY")
                .build();

//        InlineKeyboardButton b2 = InlineKeyboardButton.builder()
//                .text("Остаток дней")
//                .callbackData("MENU_STATUS")
//                .build();

        InlineKeyboardButton bBack = InlineKeyboardButton.builder()
                .text("Назад")
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
        String baseText = buildSubscriptionMenuText(activeSubOpt);

        int baseMonthlyPrice = 199;
        Plan p1 = new Plan(1, 199);
        Plan p3 = new Plan(3, 399);
        Plan p6 = new Plan(6, 699);
        Plan p12 = new Plan(12, 1199);

        String text = baseText + "\n\n" +
                "💳 Выберите срок подписки:\n" +
                "• 1 месяц — 199₽\n" +
                "• 3 месяца — 399₽ (скидка " + discountPercent(baseMonthlyPrice, p3) + "%)\n" +
                "• 6 месяцев — 699₽ (скидка " + discountPercent(baseMonthlyPrice, p6) + "%)\n" +
                "• 12 месяцев — 1199₽ (скидка " + discountPercent(baseMonthlyPrice, p12) + "%)";

        InlineKeyboardButton b1 = InlineKeyboardButton.builder()
                .text("1 месяц — 199₽")
                .callbackData("BUY_1M")
                .build();
        InlineKeyboardButton b3 = InlineKeyboardButton.builder()
                .text("3 месяца — 399₽ (" + discountPercent(baseMonthlyPrice, p3) + "%)")
                .callbackData("BUY_3M")
                .build();
        InlineKeyboardButton b6 = InlineKeyboardButton.builder()
                .text("6 месяцев — 699₽ (" + discountPercent(baseMonthlyPrice, p6) + "%)")
                .callbackData("BUY_6M")
                .build();
        InlineKeyboardButton b12 = InlineKeyboardButton.builder()
                .text("12 месяцев — 1199₽ (" + discountPercent(baseMonthlyPrice, p12) + "%)")
                .callbackData("BUY_12M")
                .build();
        InlineKeyboardButton bBack = InlineKeyboardButton.builder()
                .text("Назад")
                .callbackData("MENU_SUBSCRIPTION")
                .build();

        InlineKeyboardMarkup keyboardMarkup = InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        List.of(b1),
                        List.of(b3),
                        List.of(b6),
                        List.of(b12),
                        List.of(bBack)
                ))
                .build();

        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .replyMarkup(keyboardMarkup)
                .build();
    }

    private String buildSubscriptionMenuText(Optional<Subscription> activeSubOpt) {
        if (activeSubOpt.isEmpty()) {
            return "📦 Подписка\n\n" +
                    "❌ Активной подписки нет.\n" +
                    "Нажмите «Купить подписку», чтобы оформить.";
        }

        Subscription sub = activeSubOpt.get();
        long daysLeft = subscriptionService.getDaysLeft(sub);

        // Если хочешь показывать только дату: sub.getEndDate().toLocalDate().format(DATE_FMT)
        String until = sub.getEndDate().format(DT_FMT);

        return "📦 Подписка\n\n" +
                "✅ Активна\n" +
                "⏳ Осталось: " + formatDaysLeft(daysLeft) + "\n" +
                "🗓 Действует до: " + until;
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

    private static final class Plan {
        final int months;
        final int price;

        private Plan(int months, int price) {
            this.months = months;
            this.price = price;
        }
    }
}
