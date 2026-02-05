package ru.uzden.uzdenbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import ru.uzden.uzdenbot.config.XuiProperties;

@SpringBootApplication
@EntityScan("ru.uzden.uzdenbot.entities")
@ConfigurationProperties
public class UzdenBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(UzdenBotApplication.class, args);
    }
}
