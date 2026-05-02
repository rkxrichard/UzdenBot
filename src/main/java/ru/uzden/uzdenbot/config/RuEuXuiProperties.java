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
        List<Long> subscriptionInboundIds,
        String publicHost,
        int publicPort,
        int xhttpPort,
        String linkTag,
        String xhttpLinkTag,
        String realityPublicKey,
        String realitySni,
        String realityTarget
) {
    public boolean configured() {
        return hasText(baseUrl)
                && hasText(username)
                && hasText(password)
                && hasText(publicHost)
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
                subscriptionInboundIds,
                publicHost,
                publicPort,
                xhttpPort,
                linkTag,
                xhttpLinkTag,
                realityPublicKey,
                realitySni,
                realityTarget
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
