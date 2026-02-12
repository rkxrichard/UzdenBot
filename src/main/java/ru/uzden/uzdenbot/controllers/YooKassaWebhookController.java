package ru.uzden.uzdenbot.controllers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.uzden.uzdenbot.services.PaymentService;
import ru.uzden.uzdenbot.yookassa.YooKassaProperties;
import ru.uzden.uzdenbot.yookassa.YooKassaWebhook;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/webhooks/yookassa")
public class YooKassaWebhookController {

    private final PaymentService paymentService;
    private final YooKassaProperties properties;

    @PostMapping
    public ResponseEntity<String> handleWebhook(
            @RequestBody YooKassaWebhook webhook,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401).body("unauthorized");
        }
        try {
            paymentService.handleWebhook(webhook);
        } catch (Exception e) {
            log.warn("Webhook processing failed: {}", e.getMessage());
        }
        return ResponseEntity.ok("ok");
    }

    private boolean isAuthorized(String authorization) {
        String secret = properties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            return true;
        }
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        if (authorization.equals(secret)) {
            return true;
        }
        String bearer = "Bearer " + secret;
        return bearer.equals(authorization);
    }
}
