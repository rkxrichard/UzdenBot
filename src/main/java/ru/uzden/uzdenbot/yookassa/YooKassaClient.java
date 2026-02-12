package ru.uzden.uzdenbot.yookassa;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class YooKassaClient {

    private final RestClient.Builder restClientBuilder;
    private final YooKassaProperties properties;

    private RestClient buildClient() {
        return restClientBuilder
                .baseUrl(properties.getApiBase())
                .defaultHeaders(h -> h.setBasicAuth(properties.getShopId(), properties.getSecretKey()))
                .build();
    }

    public YooKassaPayment createPayment(YooKassaCreatePaymentRequest request, String idempotencyKey) {
        return buildClient()
                .post()
                .uri("/payments")
                .header("Idempotence-Key", idempotencyKey)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(request)
                .retrieve()
                .body(YooKassaPayment.class);
    }

    public YooKassaPayment getPayment(String paymentId) {
        return buildClient()
                .get()
                .uri("/payments/{id}", paymentId)
                .retrieve()
                .body(YooKassaPayment.class);
    }
}
