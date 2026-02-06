package ru.uzden.uzdenbot.xui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.uzden.uzdenbot.config.XuiProperties;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

@Component
public class VlessLinkBuilder {

    private final ObjectMapper om = new ObjectMapper();
    private final XuiProperties props;

    public VlessLinkBuilder(XuiProperties props) {
        this.props = props;
    }

    /**
     * Собирает vless:// ссылку для Reality на основе inbound JSON (ответ 3x-ui getInbound).
     *
     * @param inboundJson  JSON inbound (как в 3x-ui: streamSettings и settings часто строкой JSON)
     * @param host         публичный хост/IP сервера (куда подключается клиент)
     * @param port         порт (обычно 443)
     * @param clientUuid   UUID клиента (clients[].id)
     * @param tag          подпись в конце ссылки (#tag). Можно inbound.remark или свой
     */
    public String buildRealityLink(String inboundJson, String host, int port, UUID clientUuid, String tag) {
        try {
            JsonNode root = om.readTree(inboundJson);

            /*
             * В разных местах проекта сюда может прилететь:
             *  1) полный inbound JSON (как ответ getInbound)
             *  2) только значение inbound.streamSettings (часто это строка JSON)
             * Поэтому делаем детект формата и нормализуем до streamSettings-объекта.
             */
            JsonNode stream;
            if (root.has("streamSettings")) {
                // streamSettings может быть строкой JSON или объектом
                stream = root.path("streamSettings");
                if (stream.isTextual() && !stream.asText().isBlank()) {
                    stream = om.readTree(stream.asText());
                }
            } else {
                // Похоже, что нам передали уже сам streamSettings
                stream = root;
                // На всякий случай: если streamSettings пришёл как строка JSON целиком
                if (stream.isTextual() && !stream.asText().isBlank()) {
                    stream = om.readTree(stream.asText());
                }
            }

            JsonNode reality = stream.path("realitySettings");
            JsonNode realitySettings = reality.path("settings");

            // publicKey: разные сборки 3x-ui кладут это поле по-разному, а некоторые не отдают вовсе.
            String pbk = textOrNull(realitySettings, "publicKey");
            if (pbk == null) pbk = textOrNull(reality, "publicKey");
            if (pbk == null) pbk = (props != null) ? blankToNull(props.realityPublicKey()) : null;
            // В некоторых сборках/проксирующих слоях inboundJson может приходить в неожиданном формате
            // (двойное экранирование, неполный объект и т.п.). Тогда просто вытащим publicKey регуляркой.
            if (pbk == null) {
                // 1) когда streamSettings/settings лежат строкой JSON: кавычки экранированы (\")
                pbk = regexExtract(inboundJson, "\\\"publicKey\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
            }
            if (pbk == null) {
                // 2) когда нам уже передали распарсенный объект streamSettings: кавычки обычные (")
                pbk = regexExtract(inboundJson, "\"publicKey\"\\s*:\\s*\"([^\"]+)\"");
            }
            if (pbk == null) {
                throw new IllegalStateException(
                        "reality publicKey not found (checked realitySettings.settings.publicKey, realitySettings.publicKey and xui.reality-public-key)"
                );
            }

            // fingerprint (обычно chrome)
            String fp = firstNonBlank(
                    textOrNull(realitySettings, "fingerprint"),
                    textOrNull(reality, "fingerprint"),
                    firstNonBlank(
                            regexExtract(inboundJson, "\\\"fingerprint\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""),
                            regexExtract(inboundJson, "\"fingerprint\"\\s*:\\s*\"([^\"]+)\"")
                    ),
                    "chrome"
            );

            // serverNames[0] -> sni (иногда лежит в reality.settings.serverNames)
            String sni = host;
            JsonNode serverNames = reality.path("serverNames");
            if (!serverNames.isArray()) serverNames = realitySettings.path("serverNames");
            if (serverNames.isArray() && serverNames.size() > 0) {
                sni = serverNames.get(0).asText(host);
            }

            // shortIds -> sid
            // На практике многие клиенты ожидают длину shortId 8/16 hex. Поэтому выбираем первый,
            // который достаточно длинный (>=8), иначе берём самый первый.
            String sid = "";
            JsonNode shortIds = reality.path("shortIds");
            if (!shortIds.isArray()) shortIds = realitySettings.path("shortIds");
            if (shortIds.isArray() && shortIds.size() > 0) {
                for (JsonNode n : shortIds) {
                    String v = blankToNull(n.asText(""));
                    if (v != null && v.length() >= 8) {
                        sid = v;
                        break;
                    }
                }
                if (sid.isBlank()) {
                    sid = shortIds.get(0).asText("");
                }
            }
            if (sid == null || sid.isBlank()) {
                // fallback regex: берём первый shortId из массива
                sid = firstNonBlank(
                        regexExtract(inboundJson, "\\\"shortIds\\\"\\s*:\\s*\\[\\s*\\\"([^\\\"]+)\\\""),
                        regexExtract(inboundJson, "\"shortIds\"\\s*:\\s*\\[\\s*\"([^\"]+)\"")
                );
                if (sid == null) sid = "";
            }

            // flow: попробуем взять из inbound.settings.clients[0].flow (settings может быть строкой JSON)
            String flow = "xtls-rprx-vision";
            JsonNode inboundSettings = root.path("settings");
            if (inboundSettings.isTextual() && !inboundSettings.asText().isBlank()) {
                inboundSettings = om.readTree(inboundSettings.asText());
            }
            JsonNode clients = inboundSettings.path("clients");
            if (clients.isArray() && clients.size() > 0) {
                String f = textOrNull(clients.get(0), "flow");
                if (f != null) flow = f;
            }
            if (flow == null || flow.isBlank()) {
                flow = firstNonBlank(
                        regexExtract(inboundJson, "\\\"flow\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""),
                        regexExtract(inboundJson, "\"flow\"\\s*:\\s*\"([^\"]+)\""),
                        "xtls-rprx-vision"
                );
            }

            // spx (spiderX): чаще всего "/". Может лежать spiderX/spx.
            String spiderX = firstNonBlank(
                    textOrNull(reality, "spiderX"),
                    textOrNull(reality, "spx"),
                    textOrNull(realitySettings, "spiderX"),
                    textOrNull(realitySettings, "spx"),
                    firstNonBlank(
                            regexExtract(inboundJson, "\\\"spiderX\\\"\\s*:\\s*\\\"([^\\\"]*)\\\""),
                            regexExtract(inboundJson, "\"spiderX\"\\s*:\\s*\"([^\"]*)\"")
                    ),
                    "/"
            );
            String spx = enc(spiderX);

            // tag: если пустой, попробуем inbound.remark
            if (tag == null || tag.isBlank()) {
                tag = root.path("remark").asText("vpn");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("vless://")
                    .append(clientUuid)
                    .append("@")
                    .append(host)
                    .append(":")
                    .append(port)
                    .append("?type=tcp")
                    .append("&headerType=none")
                    .append("&security=reality")
                    .append("&encryption=none")
                    .append("&flow=").append(enc(flow))
                    .append("&sni=").append(enc(sni))
                    .append("&fp=").append(enc(fp))
                    .append("&pbk=").append(enc(pbk));

            if (sid != null && !sid.isBlank()) {
                sb.append("&sid=").append(enc(sid));
            }

            sb.append("&spx=").append(spx)
                    .append("#")
                    .append(enc(tag));

            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build VLESS Reality link", e);
        }
    }

    private static String regexExtract(String text, String pattern) {
        if (text == null || text.isBlank()) return null;
        try {
            Pattern p = Pattern.compile(pattern, Pattern.MULTILINE | Pattern.DOTALL);
            Matcher m = p.matcher(text);
            if (m.find()) {
                return blankToNull(m.group(1));
            }
        } catch (Exception ignored) {
            // если regex некорректен — просто считаем, что не нашли
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        String s = v.asText();
        return blankToNull(s);
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            String t = blankToNull(v);
            if (t != null) return t;
        }
        return null;
    }

    private String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
