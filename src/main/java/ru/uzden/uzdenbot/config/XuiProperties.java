package ru.uzden.uzdenbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xui")
public record XuiProperties(
        String baseUrl,
        String username,
        String password,
        long inboundId,
        String publicHost,
        int publicPort,
        String linkTag
) {}
