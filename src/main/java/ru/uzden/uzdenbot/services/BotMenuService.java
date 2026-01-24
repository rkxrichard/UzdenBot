package ru.uzden.uzdenbot.services;

import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;

@Service
public class BotMenuService {

    public SendMessage mainMenu(Long chatId) {
        InlineKeyboardButton b1 = InlineKeyboardButton.builder()
                .text("Subscription")
                .callbackData("MENU_SUBSCRIPTION")
                .build();
        InlineKeyboardButton b2 = InlineKeyboardButton.builder()
                .text("Profile")
                .callbackData("MENU_PROFILE")
                .build();
        InlineKeyboardButton b3 = InlineKeyboardButton.builder()
                .text("Help")
                .callbackData("MENU_HELP")
                .build();

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboard(
                        List.of(
                                List.of(b1,b2),
                                List.of(b3)))
                .build();

        return SendMessage.builder()
                .chatId(chatId.toString())
                .text("Выбери действие: ")
                .replyMarkup(markup)
                .build();
    }
}
