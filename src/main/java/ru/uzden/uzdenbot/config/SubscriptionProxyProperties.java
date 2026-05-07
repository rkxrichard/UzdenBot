package ru.uzden.uzdenbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.subscription-proxy")
public record SubscriptionProxyProperties(
        String baseUrl,
        String title
) {
    public boolean enabled() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
