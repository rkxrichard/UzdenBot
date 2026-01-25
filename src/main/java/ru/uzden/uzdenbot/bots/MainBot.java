package ru.uzden.uzdenbot.bots;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.uzden.uzdenbot.entities.Subscription;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.services.BotMenuService;
import ru.uzden.uzdenbot.services.SubscriptionService;
import ru.uzden.uzdenbot.services.UserService;

@Slf4j
@Component
public class MainBot extends TelegramLongPollingBot {

    private final BotMenuService botMenuService;
    private final UserService userService;
    private final SubscriptionService subscriptionService;

    private final String token;
    private final String username;

    @Autowired
    public MainBot(
            BotMenuService botMenuService, UserService userService, SubscriptionService subscriptionService,
            @Value("${telegram.bot.token}") String token,
            @Value("${telegram.bot.username}")String username) {
        this.botMenuService = botMenuService;
        this.userService = userService;
        this.subscriptionService = subscriptionService;
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
                    userService.registerOrUpdate(update.getMessage().getFrom());
                    execute(botMenuService.mainMenu(chatId));
                    return;
                }
            }

            if (update.hasCallbackQuery()) {
                var cq = update.getCallbackQuery();
                String data = update.getCallbackQuery().getData();
                Long chatId = cq.getMessage().getChatId();

                switch (data) {
                    case "MENU_SUBSCRIPTION" -> {
                        User user = userService.registerOrUpdate(cq.getFrom());
                        boolean active = subscriptionService.hasActiveSubscription(user);
                        execute(simpleMessage(chatId, active ? "✅ Подписка активна" : "❌ Подписки нет"));
                    }
                    case "MENU_PROFILE" -> execute(simpleMessage(chatId,"Profile menu"));
                    case "MENU_HELP" -> execute(simpleMessage(chatId,"Help menu"));
                    case "MENU_BACK" -> execute(simpleMessage(chatId,"Back menu"));

                    case "MENU_BUY" -> execute(simpleMessage(chatId,"Buy menu"));
                    case "MENU_STATUS" -> execute(simpleMessage(chatId,"Status menu"));
                }
                execute(AnswerCallbackQuery.builder().callbackQueryId(cq.getId()).build());
            }
        } catch (Exception e) {
            log.error("Ошибка в боте: ", e);
        }

    }

    private SendMessage simpleMessage(Long chatId, String text) {
        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build();
    }
}
