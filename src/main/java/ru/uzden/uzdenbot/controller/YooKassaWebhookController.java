package ru.uzden.uzdenbot.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.uzden.uzdenbot.client.YooKassaWebhookEvent;
import ru.uzden.uzdenbot.config.YooKassaProperties;
import ru.uzden.uzdenbot.service.SubscriptionService;

@RestController
@RequestMapping("/api/yookassa")
public class YooKassaWebhookController {
    private final SubscriptionService subscriptionService;
    private final YooKassaProperties properties;

    public YooKassaWebhookController(SubscriptionService subscriptionService, YooKassaProperties properties) {
        this.subscriptionService = subscriptionService;
        this.properties = properties;
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(@RequestBody YooKassaWebhookEvent event,
                                                @RequestHeader(value = "X-Webhook-Secret", required = false)
                                                String webhookSecret) {
        if (properties.webhookSecret() != null && !properties.webhookSecret().isBlank()) {
            if (!properties.webhookSecret().equals(webhookSecret)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid secret");
            }
        }

        if (event != null && "payment.succeeded".equals(event.event())) {
            subscriptionService.handlePaymentSucceeded(event);
        }

        return ResponseEntity.ok("ok");
    }
}
