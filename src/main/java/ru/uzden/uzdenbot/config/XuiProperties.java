package ru.uzden.uzdenbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xui")
public record XuiProperties(
        String baseUrl,
        String basePath,
        String username,
        String password,
        long inboundId,
        long xhttpInboundId,
        String publicHost,
        int publicPort,
        int xhttpPort,
        String linkTag,
        String xhttpLinkTag,
        String realityPublicKey,
        String realitySni,
        String realityTarget
) {}
