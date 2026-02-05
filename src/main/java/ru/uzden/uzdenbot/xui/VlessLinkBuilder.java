package ru.uzden.uzdenbot.xui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class VlessLinkBuilder {

    private final ObjectMapper om = new ObjectMapper();

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

            // streamSettings в твоём inbound — строка JSON
            String streamSettingsStr = root.path("streamSettings").asText(null);
            JsonNode stream = (streamSettingsStr != null && !streamSettingsStr.isBlank())
                    ? om.readTree(streamSettingsStr)
                    : root.path("streamSettings");

            JsonNode reality = stream.path("realitySettings");
            JsonNode realitySettings = reality.path("settings");

            // publicKey
            String pbk = realitySettings.path("publicKey").asText();
            if (pbk == null || pbk.isBlank()) {
                throw new IllegalStateException("reality publicKey not found in inbound.streamSettings.realitySettings.settings.publicKey");
            }

            // fingerprint (обычно chrome)
            String fp = realitySettings.path("fingerprint").asText("chrome");

            // serverNames[0] -> sni
            String sni = host;
            JsonNode serverNames = reality.path("serverNames");
            if (serverNames.isArray() && serverNames.size() > 0) {
                sni = serverNames.get(0).asText(host);
            }

            // shortIds[0] -> sid
            String sid = "";
            JsonNode shortIds = reality.path("shortIds");
            if (shortIds.isArray() && shortIds.size() > 0) {
                sid = shortIds.get(0).asText("");
            }

            // flow: обычно xtls-rprx-vision
            // можно брать из inbound.settings.clients[0].flow, но чаще фиксируют.
            String flow = "xtls-rprx-vision";

            // spx = "/" -> urlencoded
            String spx = enc("/");

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

    private String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
