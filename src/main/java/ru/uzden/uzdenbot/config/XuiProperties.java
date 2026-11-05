package ru.uzden.uzdenbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "xui")
public record XuiProperties(
        String baseUrl,
        String basePath,
        String subscriptionBaseUrl,
        String username,
        String password,
        long inboundId,
        long xhttpInboundId,
        List<Long> subscriptionInboundIds,
        /**
         * Опциональный API-токен (Bearer) для панелей 3x-ui 3.x с CSRF-защитой,
         * где логин по cookie (username/password) не проходит CSRF-мидлварь.
         * Если задан — {@link ru.uzden.uzdenbot.xui.ThreeXuiClient} использует
         * Authorization: Bearer вместо логина по cookie.
         */
        String apiToken
) {}
