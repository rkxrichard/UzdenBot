package ru.uzden.uzdenbot.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "subscription")
public record SubscriptionProperties(
        BigDecimal priceRubles,
        int durationDays
) {
}
