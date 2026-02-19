package ru.uzden.uzdenbot.xui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Строит vless:// ссылку для Reality (3x-ui 2.8.9).
 *
 * 3x-ui хранит streamSettings/settings как JSON-строки, а realitySettings
 * может лежать строкой внутри streamSettings, поэтому распаковываем уровни.
 */
@Component
public class VlessLinkBuilder {

    private final String fallbackPublicKey;
    private final String fallbackSni;
    private final String fallbackTarget;

    public VlessLinkBuilder(
            @Value("${xui.reality-public-key:}") String fallbackPublicKey,
            @Value("${xui.reality-sni:}") String fallbackSni,
            @Value("${xui.reality-target:}") String fallbackTarget) {
        this.fallbackPublicKey = fallbackPublicKey;
        this.fallbackSni = fallbackSni;
        this.fallbackTarget = fallbackTarget;
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
            String inbound = JsonMini.unquoteIfString(inboundJson);

            String streamSettings = JsonMini.unquoteIfString(
                    JsonMini.extractFieldValue(inbound, "streamSettings")
            );
            String realitySettings = JsonMini.unquoteIfString(
                    JsonMini.extractFieldValue(streamSettings, "realitySettings")
            );
            if (realitySettings == null || realitySettings.isBlank()) {
                realitySettings = JsonMini.unquoteIfString(
                        JsonMini.extractFieldValue(inbound, "realitySettings")
                );
            }
            String settings = JsonMini.unquoteIfString(
                    JsonMini.extractFieldValue(inbound, "settings")
            );

            String pbk = firstNonBlank(
                    stringField(realitySettings, "publicKey"),
                    stringField(streamSettings, "publicKey"),
                    fallbackPublicKey
            );

            if (pbk == null || pbk.isBlank()) {
                throw new IllegalStateException("reality publicKey not found (checked streamSettings.realitySettings.publicKey and xui.reality-public-key)");
            }

            String sni = firstNonBlank(
                    firstArrayItem(realitySettings, "serverNames"),
                    stringField(realitySettings, "serverName"),
                    hostFromTarget(stringField(realitySettings, "dest")),
                    hostFromTarget(stringField(realitySettings, "target")),
                    fallbackSni,
                    hostFromTarget(fallbackTarget)
            );

            if (sni == null || sni.isBlank()) {
                throw new IllegalStateException("reality serverName/SNI not found");
            }

            String sid = firstNonBlank(
                    firstArrayItem(realitySettings, "shortIds"),
                    "0000"
            );

            String fp = firstNonBlank(
                    stringField(realitySettings, "fingerprint"),
                    stringField(streamSettings, "fingerprint"),
                    "chrome"
            );

            String spx = firstNonBlank(
                    stringField(realitySettings, "spiderX"),
                    "/"
            );

            String flow = firstNonBlank(
                    findClientField(settings, clientUuid, "flow"),
                    "xtls-rprx-vision"
            );

            String encryption = firstNonBlankNotNone(
                    findClientField(settings, clientUuid, "encryption"),
                    stringField(settings, "encryption"),
                    stringField(realitySettings, "encryption"),
                    stringField(streamSettings, "encryption"),
                    findFirstStringFieldValue(settings, "encryption", true),
                    findFirstStringFieldValue(realitySettings, "encryption", true),
                    findFirstStringFieldValue(streamSettings, "encryption", true),
                    findFirstStringFieldValue(inbound, "encryption", true)
            );
            if (encryption == null || encryption.isBlank()) encryption = "none";

            // Собираем простую ссылку как в твоём curl-успешном примере
            StringBuilder qs = new StringBuilder();
            qs.append("type=tcp");
            qs.append("&encryption=").append(url(encryption));
            qs.append("&security=reality");
            qs.append("&pbk=").append(url(pbk));
            qs.append("&fp=").append(url(fp));
            qs.append("&sni=").append(url(sni));
            qs.append("&sid=").append(url(sid));
            qs.append("&spx=").append(url(spx));
            qs.append("&flow=").append(url(flow));

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

    private static String firstNonBlankNotNone(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank() && !"none".equalsIgnoreCase(v)) return v;
        }
        return null;
    }

    private static String stringField(String json, String field) {
        return JsonMini.unquoteIfString(JsonMini.extractFieldValue(json, field));
    }

    private static String firstArrayItem(String json, String field) {
        String arr = JsonMini.extractFieldValue(json, field);
        return firstStringInJson(arr);
    }

    private static String firstStringInJson(String json) {
        if (json == null) return null;
        int start = json.indexOf('"');
        if (start < 0) return null;
        int end = JsonMini.findStringEnd(json, start);
        if (end < 0) return null;
        return JsonMini.unquoteIfString(json.substring(start, end + 1));
    }

    private static String findClientField(String settingsJson, String clientUuid, String field) {
        if (settingsJson == null || settingsJson.isBlank()) return null;
        String uuid = (clientUuid == null) ? null : clientUuid.trim();
        if (uuid != null && !uuid.isBlank()) {
            int idx = settingsJson.indexOf(uuid);
            if (idx >= 0) {
                int objStart = settingsJson.lastIndexOf('{', idx);
                if (objStart >= 0) {
                    int objEnd = JsonMini.findMatchingBracket(settingsJson, objStart);
                    if (objEnd > objStart) {
                        String obj = settingsJson.substring(objStart, objEnd + 1);
                        String v = findStringField(obj, field);
                        if (v != null && !v.isBlank()) return v;
                    }
                }
            }
        }
        return findStringField(settingsJson, field);
    }

    private static String findStringField(String json, String field) {
        if (json == null) return null;
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;
        int end = JsonMini.findStringEnd(json, i);
        if (end < 0) return null;
        return JsonMini.unquoteIfString(json.substring(i, end + 1));
    }

    private static String findFirstStringFieldValue(String json, String field, boolean skipNone) {
        if (json == null) return null;
        String needle = "\"" + field + "\"";
        int idx = 0;
        while (true) {
            idx = json.indexOf(needle, idx);
            if (idx < 0) return null;
            int colon = json.indexOf(':', idx + needle.length());
            if (colon < 0) return null;
            int i = colon + 1;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length() || json.charAt(i) != '"') {
                idx = colon + 1;
                continue;
            }
            int end = JsonMini.findStringEnd(json, i);
            if (end < 0) return null;
            String val = JsonMini.unquoteIfString(json.substring(i, end + 1));
            if (val != null && !val.isBlank() && (!skipNone || !"none".equalsIgnoreCase(val))) return val;
            idx = end + 1;
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
