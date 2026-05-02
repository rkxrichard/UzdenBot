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
        String publicHost,
        int publicPort,
        int xhttpPort,
        String linkTag,
        String xhttpLinkTag,
        String realityPublicKey,
        String realitySni,
        String realityTarget
) {}
