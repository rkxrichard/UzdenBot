package ru.uzden.uzdenbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import ru.uzden.uzdenbot.config.TelegramBotProperties;

@SpringBootApplication
@EntityScan("ru.uzden.uzdenbot.entities")
@EnableConfigurationProperties(TelegramBotProperties.class)
public class UzdenBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(UzdenBotApplication.class, args);
    }
}
