package ru.uzden.uzdenbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "xui-ru-eu")
public record RuEuXuiProperties(
        String baseUrl,
        String basePath,
        String subscriptionBaseUrl,
        String username,
        String password,
        long inboundId,
        long xhttpInboundId,
        List<Long> subscriptionInboundIds
) {
    public boolean configured() {
        return hasText(baseUrl)
                && hasText(username)
                && hasText(password)
                && inboundId > 0;
    }

    public XuiProperties toXuiProperties() {
        return new XuiProperties(
                baseUrl,
                basePath,
                subscriptionBaseUrl,
                username,
                password,
                inboundId,
                xhttpInboundId > 0 ? xhttpInboundId : inboundId,
                subscriptionInboundIds
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
