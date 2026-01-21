package ru.uzden.uzdenbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telegram.bot")
public record BotProperties(
        String token,
        String username,
        Long adminChatId
) {
}
