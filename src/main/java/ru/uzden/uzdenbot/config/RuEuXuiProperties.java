package ru.uzden.uzdenbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xui-ru-eu")
public record RuEuXuiProperties(
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
        String realityPublicKey
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
                username,
                password,
                inboundId,
                xhttpInboundId > 0 ? xhttpInboundId : inboundId,
                publicHost,
                publicPort,
                xhttpPort,
                linkTag,
                xhttpLinkTag,
                realityPublicKey
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
