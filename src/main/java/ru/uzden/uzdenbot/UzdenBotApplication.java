package ru.uzden.uzdenbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class UzdenBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(UzdenBotApplication.class, args);
    }

}
