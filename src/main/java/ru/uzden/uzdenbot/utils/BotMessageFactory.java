package ru.uzden.uzdenbot.utils;

import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

public final class BotMessageFactory {

    private BotMessageFactory() {
    }

    public static SendMessage simpleMessage(Long chatId, String text) {
        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build();
    }

    public static EditMessageText editFromSendMessage(SendMessage sm, Long chatId, Integer messageId) {
        return EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text(sm.getText())
                .replyMarkup((InlineKeyboardMarkup) sm.getReplyMarkup())
                .build();
    }

    public static AnswerCallbackQuery callbackAnswer(String callbackId, String text) {
        AnswerCallbackQuery.AnswerCallbackQueryBuilder b = AnswerCallbackQuery.builder()
                .callbackQueryId(callbackId);
        if (text != null && !text.isBlank()) {
            b.text(text).showAlert(false);
        }
        return b.build();
    }
}
