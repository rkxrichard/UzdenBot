package ru.uzden.uzdenbot.client;

import java.util.Map;

public record YooKassaWebhookEvent(
        String event,
        YooKassaWebhookPayment object
) {
    public record YooKassaWebhookPayment(
            String id,
            String status,
            Map<String, String> metadata
    ) {
    }
}
