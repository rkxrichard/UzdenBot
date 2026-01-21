package ru.uzden.uzdenbot.bot;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.uzden.uzdenbot.config.BotProperties;
import ru.uzden.uzdenbot.service.SubscriptionService;

@Component
public class UzdenTelegramBot extends TelegramLongPollingBot {
    private final BotProperties properties;
    private final SubscriptionService subscriptionService;

    public UzdenTelegramBot(BotProperties properties, SubscriptionService subscriptionService) {
        super(properties.token());
        this.properties = properties;
        this.subscriptionService = subscriptionService;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }
        String text = update.getMessage().getText().trim();
        Long chatId = update.getMessage().getChatId();
        Long userId = update.getMessage().getFrom().getId();

        switch (text) {
            case "/start" -> sendMessage(chatId, startMessage());
            case "/buy" -> sendMessage(chatId, paymentMessage(userId));
            case "/key" -> sendMessage(chatId, keyMessage(userId));
            default -> sendMessage(chatId, defaultMessage());
        }
    }

    @Override
    public String getBotUsername() {
        return properties.username();
    }

    private String startMessage() {
        return """
                Привет! Я помогу купить подписку VPN на 30 дней.

                Команды:
                /buy — оплатить подписку
                /key — получить активный ключ
                """;
    }

    private String paymentMessage(Long userId) {
        String paymentUrl = subscriptionService.createPayment(userId);
        return "Оплатите подписку по ссылке: " + paymentUrl;
    }

    private String keyMessage(Long userId) {
        Optional<String> key = subscriptionService.findActiveKey(userId);
        return key.map(value -> "Ваш ключ VLESS:\n" + value)
                .orElse("Активной подписки нет. Используйте /buy для оплаты.");
    }

    private String defaultMessage() {
        return "Неизвестная команда. Используйте /buy или /key.";
    }

    private void sendMessage(Long chatId, String text) {
        SendMessage message = new SendMessage(chatId.toString(), text);
        try {
            execute(message);
        } catch (TelegramApiException exception) {
            if (properties.adminChatId() != null) {
                SendMessage fallback = new SendMessage(properties.adminChatId().toString(),
                        "Ошибка отправки сообщения пользователю " + chatId + ": " + exception.getMessage());
                try {
                    execute(fallback);
                } catch (TelegramApiException ignored) {
                    // ignored
                }
            }
        }
    }
}
