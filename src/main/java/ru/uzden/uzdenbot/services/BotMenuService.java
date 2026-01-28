package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import ru.uzden.uzdenbot.entities.Subscription;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.repositories.UserRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BotMenuService {

    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;

    private static final DateTimeFormatter DT_FMT   = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public SendMessage mainMenu(Long chatId) {
        InlineKeyboardButton b1 = InlineKeyboardButton.builder()
                .text("Подписка")
                .callbackData("MENU_SUBSCRIPTION")
                .build();
//        InlineKeyboardButton b3 = InlineKeyboardButton.builder()
//                .text("Help")
//                .callbackData("MENU_HELP")
//                .build();

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        List.of(b1)
//                        ,List.of(b3)
                ))
                .build();

        return SendMessage.builder()
                .chatId(chatId.toString())
                .text("Выбери действие:")
                .replyMarkup(markup)
                .build();
    }

    public SendMessage subscriptionMenu(Long chatId) {
        User user = userRepository.findUserByTelegramId(chatId)
                .orElseThrow(() -> new IllegalStateException("User not found for chatId: " + chatId));

        Optional<Subscription> activeSubOpt = subscriptionService.getActiveSubscription(user);

        boolean isActive = activeSubOpt.isPresent();
        String buyOrExtendText = isActive ? "Продлить подписку" : "Купить подписку";
        String menuText = buildSubscriptionMenuText(activeSubOpt);

        InlineKeyboardButton b1 = InlineKeyboardButton.builder()
                .text(buyOrExtendText)
                .callbackData("MENU_BUY")
                .build();

//        InlineKeyboardButton b2 = InlineKeyboardButton.builder()
//                .text("Остаток дней")
//                .callbackData("MENU_STATUS")
//                .build();

        InlineKeyboardButton b3 = InlineKeyboardButton.builder()
                .text("Назад")
                .callbackData("MENU_BACK")
                .build();


        
        InlineKeyboardMarkup keyboardMarkup;
        if (buyOrExtendText.equals("Продлить подписку")) {
             keyboardMarkup = InlineKeyboardMarkup.builder()
                    .keyboard(List.of(
                            List.of(b1),
                            List.of(b3)
                    ))
                    .build();
        } else {
             keyboardMarkup = InlineKeyboardMarkup.builder()
                    .keyboard(List.of(
                            List.of(b1, b3)))
                    .build();

        }


        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(menuText)
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
}
