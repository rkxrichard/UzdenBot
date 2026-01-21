package ru.uzden.uzdenbot.client;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import ru.uzden.uzdenbot.config.YooKassaProperties;

@Component
public class YooKassaClient {
    private final WebClient webClient;
    private final YooKassaProperties properties;

    public YooKassaClient(WebClient.Builder webClientBuilder, YooKassaProperties properties) {
        this.properties = properties;
        this.webClient = webClientBuilder
                .baseUrl("https://api.yookassa.ru/v3")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeaders(headers -> headers.setBasicAuth(properties.shopId(), properties.secretKey()))
                .build();
    }

    public YooKassaPaymentResponse createPayment(BigDecimal amountRub, String description, Map<String, String> metadata) {
        YooKassaCreatePaymentRequest request = new YooKassaCreatePaymentRequest(
                new YooKassaAmount(amountRub, "RUB"),
                new YooKassaConfirmationRequest("redirect", properties.returnUrl()),
                description,
                metadata
        );

        return webClient.post()
                .uri("/payments")
                .header("Idempotence-Key", UUID.randomUUID().toString())
                .bodyValue(request)
                .retrieve()
                .bodyToMono(YooKassaPaymentResponse.class)
                .block();
    }

    public record YooKassaAmount(BigDecimal value, String currency) {
    }

    public record YooKassaConfirmationRequest(String type, @JsonProperty("return_url") String returnUrl) {
    }

    public record YooKassaConfirmationResponse(String type,
                                               @JsonProperty("confirmation_url") String confirmationUrl) {
    }

    public record YooKassaCreatePaymentRequest(
            YooKassaAmount amount,
            YooKassaConfirmationRequest confirmation,
            String description,
            Map<String, String> metadata
    ) {
    }

    public record YooKassaPaymentResponse(
            String id,
            String status,
            YooKassaConfirmationResponse confirmation
    ) {
    }
}
