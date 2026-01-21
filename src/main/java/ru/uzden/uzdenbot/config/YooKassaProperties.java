package ru.uzden.uzdenbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yookassa")
public record YooKassaProperties(
        String shopId,
        String secretKey,
        String returnUrl,
        String webhookSecret
) {
}
