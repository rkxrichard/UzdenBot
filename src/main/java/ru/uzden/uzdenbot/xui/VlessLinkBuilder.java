package ru.uzden.uzdenbot.xui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Строит vless:// ссылку для Reality.
 *
 * ВАЖНО: 3x-ui хранит streamSettings/settings как JSON-строки (escaped),
 * поэтому тут используются "поиск по тексту" + несколько паттернов (escaped/unescaped).
 * Это намного устойчивее, чем пытаться распарсить вложенный JSON через ObjectMapper.
 */
@Component
public class VlessLinkBuilder {

    private final String fallbackPublicKey;

    public VlessLinkBuilder(@Value("${xui.reality-public-key:}") String fallbackPublicKey) {
        this.fallbackPublicKey = fallbackPublicKey;
    }

    public String buildRealityLink(
            String inboundJson,
            String publicHost,
            int publicPort,
            java.util.UUID clientUuid,
            String linkTag
    ) {
        return buildRealityLink(inboundJson, publicHost, publicPort, clientUuid.toString(), linkTag);
    }

    public String buildRealityLink(
            String inboundJson,
            String publicHost,
            int publicPort,
            String clientUuid,
            String linkTag
    ) {
        try {
            String pbk = firstNonBlank(
                    findAny(inboundJson,
                            // unescaped: "publicKey":"...."
                            "\\\"publicKey\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
                            // escaped inside string: \"publicKey\":\"....\"
                            "\\\\\\\"publicKey\\\\\\\"\\s*:\\\\s*\\\\\\\"([^\\\\\\\"]+)\\\\\\\""
                    ),
                    fallbackPublicKey
            );

            if (pbk == null || pbk.isBlank()) {
                throw new IllegalStateException("reality publicKey not found (checked realitySettings.settings.publicKey, realitySettings.publicKey and xui.reality-public-key)");
            }

            String sni = firstNonBlank(
                    findAny(inboundJson,
                            // "serverNames":["www.amazon.com"]
                            "\\\"serverNames\\\"\\s*:\\s*\\[\\s*\\\"([^\\\"]+)\\\"",
                            // \"serverNames\":[\"www.amazon.com\"]
                            "\\\\\\\"serverNames\\\\\\\"\\s*:\\\\s*\\\\\\[\\\\s*\\\\\\\"([^\\\\\\\"]+)\\\\\\\""
                    ),
                    // иногда serverName лежит отдельным полем
                    findAny(inboundJson,
                            "\\\"serverName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
                            "\\\\\\\"serverName\\\\\\\"\\s*:\\\\s*\\\\\\\"([^\\\\\\\"]+)\\\\\\\""
                    ),
                    // иногда target: www.amazon.com:443
                    hostFromTarget(findAny(inboundJson,
                            "\\\"target\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
                            "\\\\\\\"target\\\\\\\"\\s*:\\\\s*\\\\\\\"([^\\\\\\\"]+)\\\\\\\""
                    ))
            );

            if (sni == null || sni.isBlank()) {
                throw new IllegalStateException("reality serverName/SNI not found");
            }

            String sid = firstNonBlank(
                    findAny(inboundJson,
                            "\\\"shortIds\\\"\\s*:\\s*\\[\\s*\\\"([^\\\"]+)\\\"",
                            "\\\\\\\"shortIds\\\\\\\"\\s*:\\\\s*\\\\\\[\\\\s*\\\\\\\"([^\\\\\\\"]+)\\\\\\\""
                    ),
                    "0000"
            );

            String fp = firstNonBlank(
                    findAny(inboundJson,
                            "\\\"fingerprint\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
                            "\\\\\\\"fingerprint\\\\\\\"\\s*:\\\\s*\\\\\\\"([^\\\\\\\"]+)\\\\\\\""
                    ),
                    "chrome"
            );

            String spx = firstNonBlank(
                    findAny(inboundJson,
                            "\\\"spiderX\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"",
                            "\\\\\\\"spiderX\\\\\\\"\\s*:\\\\s*\\\\\\\"([^\\\\\\\"]*)\\\\\\\""
                    ),
                    "/"
            );

            String flow = firstNonBlank(
                    findAny(inboundJson,
                            "\\\"flow\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
                            "\\\\\\\"flow\\\\\\\"\\s*:\\\\s*\\\\\\\"([^\\\\\\\"]+)\\\\\\\""
                    ),
                    "xtls-rprx-vision"
            );

            // Собираем простую ссылку как в твоём curl-успешном примере
            StringBuilder qs = new StringBuilder();
            qs.append("type=tcp");
            qs.append("&security=reality");
            qs.append("&encryption=none");
            qs.append("&flow=").append(url(flow));
            qs.append("&sni=").append(url(sni));
            qs.append("&fp=").append(url(fp));
            qs.append("&pbk=").append(url(pbk));
            qs.append("&sid=").append(url(sid));
            qs.append("&spx=").append(url(spx));

            String tag = (linkTag == null || linkTag.isBlank()) ? "vpn" : linkTag;
            return "vless://" + clientUuid + "@" + publicHost + ":" + publicPort + "?" + qs + "#" + urlFragment(tag);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to build VLESS Reality link", e);
        }
    }

    private static String url(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String urlFragment(String s) {
        // фрагмент лучше тоже энкодить (но без '+' для пробелов)
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String findAny(String text, String... patterns) {
        if (text == null) return null;
        for (String p : patterns) {
            String v = regexGroup(text, p, 1);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String regexGroup(String text, String pattern, int group) {
        try {
            Matcher m = Pattern.compile(pattern, Pattern.DOTALL).matcher(text);
            if (m.find()) return m.group(group);
            return null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String hostFromTarget(String target) {
        if (target == null || target.isBlank()) return null;
        // "www.amazon.com:443"
        int idx = target.indexOf(':');
        String host = (idx > 0) ? target.substring(0, idx) : target;
        return host.trim();
    }
}