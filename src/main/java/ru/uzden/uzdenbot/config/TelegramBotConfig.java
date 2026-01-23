package ru.uzden.uzdenbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import ru.uzden.uzdenbot.bots.MainBot;

@Configuration
public class TelegramBotConfig {

    @Bean
    public SpringLongPollingBot springLongPollingBot(
            TelegramBotProperties props,
            MainBot mainBot
    ) {
        return new SpringLongPollingBot() {
            @Override
            public String getBotToken() {
                return props.token();
            }

            @Override
            public LongPollingUpdateConsumer getUpdatesConsumer() {
                return mainBot;
            }
        };
    }
}
