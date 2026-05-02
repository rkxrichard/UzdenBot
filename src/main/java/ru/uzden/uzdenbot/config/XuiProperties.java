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
        List<Long> subscriptionInboundIds
) {}
