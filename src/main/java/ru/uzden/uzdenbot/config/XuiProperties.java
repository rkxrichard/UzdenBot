package ru.uzden.uzdenbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xui")
public record XuiProperties(
        String baseUrl,
        String username,
        String password,
        Long inboundId,
        String clientEmailPrefix,
        String serverHost,
        Integer serverPort,
        String serverName,
        String realityPublicKey,
        String realityShortId,
        String fingerprint,
        String flow
) {
}
