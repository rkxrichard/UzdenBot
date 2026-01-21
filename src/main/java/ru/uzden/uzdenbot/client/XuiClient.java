package ru.uzden.uzdenbot.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import ru.uzden.uzdenbot.config.XuiProperties;

@Component
public class XuiClient {
    private final WebClient webClient;
    private final XuiProperties properties;
    private final ObjectMapper objectMapper;

    public XuiClient(WebClient.Builder webClientBuilder, XuiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String createVlessKey(Long telegramUserId) {
        UUID clientId = UUID.randomUUID();
        String email = properties.clientEmailPrefix() + telegramUserId;
        String cookie = loginAndGetCookie();
        Map<String, Object> payload = Map.of(
                "id", properties.inboundId(),
                "settings", buildSettings(clientId, email)
        );

        webClient.post()
                .uri("/panel/api/inbounds/addClient")
                .header(HttpHeaders.COOKIE, cookie)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return buildVlessLink(clientId.toString(), email);
    }

    private String loginAndGetCookie() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", properties.username());
        form.add("password", properties.password());

        ClientResponse response = webClient.post()
                .uri("/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .exchangeToMono(result -> result)
                .block();

        if (response == null || response.cookies().isEmpty()) {
            throw new IllegalStateException("Не удалось авторизоваться в 3x-ui.");
        }

        return response.cookies().entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .map(cookie -> entry.getKey() + "=" + cookie.getValue()))
                .reduce((left, right) -> left + "; " + right)
                .orElseThrow(() -> new IllegalStateException("Cookie отсутствует в ответе 3x-ui."));
    }

    private String buildSettings(UUID clientId, String email) {
        Map<String, Object> client = Map.of(
                "id", clientId.toString(),
                "email", email,
                "flow", properties.flow()
        );
        Map<String, Object> settings = Map.of("clients", List.of(client));
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось сериализовать настройки клиента.", exception);
        }
    }

    private String buildVlessLink(String clientId, String email) {
        String host = properties.serverHost();
        int port = properties.serverPort();
        String securityParams = String.format(
                "type=tcp&security=reality&pbk=%s&fp=%s&sni=%s&sid=%s&flow=%s",
                properties.realityPublicKey(),
                properties.fingerprint(),
                properties.serverName(),
                properties.realityShortId(),
                properties.flow()
        );

        return String.format("vless://%s@%s:%d?%s#%s", clientId, host, port, securityParams, email);
    }
}
