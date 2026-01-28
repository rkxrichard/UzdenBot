package ru.uzden.uzdenbot.bots;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.repositories.UserRepository;
import ru.uzden.uzdenbot.services.BotMenuService;
import ru.uzden.uzdenbot.services.SubscriptionService;
import ru.uzden.uzdenbot.services.UserService;

@Slf4j
@Component
public class MainBot extends TelegramLongPollingBot {

    private final BotMenuService botMenuService;
    private final UserService userService;
    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;

    private final String token;
    private final String username;

    @Autowired
    public MainBot(
            BotMenuService botMenuService, UserService userService, SubscriptionService subscriptionService, UserRepository userRepository,
            @Value("${telegram.bot.token}") String token,
            @Value("${telegram.bot.username}")String username) {
        this.botMenuService = botMenuService;
        this.userService = userService;
        this.subscriptionService = subscriptionService;
        this.userRepository = userRepository;
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
                Integer messageId = cq.getMessage().getMessageId();

                switch (data) {
                    case "MENU_SUBSCRIPTION" -> editFromSendMessage(botMenuService.subscriptionMenu(chatId), chatId, messageId);
//                    case "MENU_HELP" -> editFromSendMessage(simpleMessage(chatId,"Help menu"),chatId,messageId);
                    case "MENU_BACK" -> editFromSendMessage(botMenuService.mainMenu(chatId), chatId, messageId);

                    case "MENU_BUY" -> {
                        // ПОТОМ ДОБАВИТЬ РЕАЛИЗАЦИЮ СМЕНЫ (КУПИТЬ/ПРОДЛИТЬ)
                        // да и впринципе добавить реаллизацию оплаты через Юкассу
                        User user = userService.registerOrUpdate(cq.getFrom());
                        subscriptionService.extendSubscription(user, 30);
                        execute(simpleMessage(chatId,"✅ Подписка продлена на 30 дней"));
                        execute(botMenuService.subscriptionMenu(chatId));
                    }
//                    case "MENU_STATUS" -> {
//                        User user = userService.registerOrUpdate(cq.getFrom());
//                        var activeSub = subscriptionService.getActiveSubscription(user);
//                        if (activeSub.isPresent()) {
//                            long daysLeft = subscriptionService.getDaysLeft(activeSub.get());
//                            execute(simpleMessage(chatId, "✅ Подписка активна\nОсталось дней: " + daysLeft));
//                        } else {
//                            execute(simpleMessage(chatId, "❌ Подписки нет"));
//                        }
//                    }
                }
                execute(AnswerCallbackQuery.builder().callbackQueryId(cq.getId()).build());
            }
        } catch (Exception e) {
            log.error("Ошибка в боте: ", e);
        }
    }

    // ОТПРАВКА ПРОСТЕНЬКОГО СООБЩЕНИЯ
    private SendMessage simpleMessage(Long chatId, String text) {
        return SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build();
    }

    // ИЗМЕНЕНИЕ СООБЩЕНИЯ
    private void editFromSendMessage(SendMessage sm, Long chatId, Integer messageId) throws Exception {
        execute(EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text(sm.getText())
                .replyMarkup((InlineKeyboardMarkup) sm.getReplyMarkup())
                .build());
    }

}
