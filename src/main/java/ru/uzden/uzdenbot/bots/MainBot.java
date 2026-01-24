package ru.uzden.uzdenbot.bots;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.uzden.uzdenbot.services.BotMenuService;

@Component
public class MainBot extends TelegramLongPollingBot {

    @Autowired
    private BotMenuService botMenuService;

    private final String token;
    private final String username;

    public MainBot(
            BotMenuService botMenuService,
                   @Value("${telegram.bot.token}") String token,
                   @Value("${telegram.bot.username}")String username) {
        this.botMenuService = botMenuService;
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
        try {
            if(update.hasMessage() && update.getMessage().hasText()) {
                String text = update.getMessage().getText();
                Long chatId = update.getMessage().getChatId();

                if ("/start".equals(text)) {
                    execute(botMenuService.mainMenu(chatId));
                    return;
                }
            }

            if (update.hasCallbackQuery()) {
                var cq = update.getCallbackQuery();
                String data = update.getCallbackQuery().getData();
                Long chatId = cq.getMessage().getChatId();

                switch (data) {
                    case "MENU_SUBSCRIPTION" -> execute(simpleMessage(chatId,"subscription menu"));
                    case "MENU_PROFILE" -> execute(simpleMessage(chatId,"Profile menu"));
                    case "MENU_HELP" -> execute(simpleMessage(chatId,"Help menu"));
                    case "MENU_BACK" -> execute(simpleMessage(chatId,"Back menu"));

                    case "MENU_BUY" -> execute(simpleMessage(chatId,"Buy menu"));
                    case "MENU_STATUS" -> execute(simpleMessage(chatId,"Status menu"));
                }
                execute(AnswerCallbackQuery.builder().callbackQueryId(cq.getId()).build());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private SendMessage simpleMessage(Long chatId, String text) {
        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build();
    }
}
