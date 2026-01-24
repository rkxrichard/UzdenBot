package ru.uzden.uzdenbot.bots;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
public class MainBot extends TelegramLongPollingBot {

    private final String token;
    private final String username;

    public MainBot(@Value("${telegram.bot.token}")String token, @Value("${telegram.bot.username}")String username) {
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
        // обработка апдейтов
        if(update.hasMessage() && update.getMessage().hasText()) {
            System.out.println("Message: " + update.getMessage().getText());
            try {
                execute(SendMessage.builder().chatId(update.getMessage().getChatId()).text("Принял: " + update.getMessage().getText()).build());
            }  catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
