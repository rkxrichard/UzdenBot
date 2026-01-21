package ru.uzden.uzdenbot.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.uzden.uzdenbot.client.XuiClient;
import ru.uzden.uzdenbot.client.YooKassaClient;
import ru.uzden.uzdenbot.client.YooKassaWebhookEvent;
import ru.uzden.uzdenbot.config.SubscriptionProperties;
import ru.uzden.uzdenbot.model.Subscription;
import ru.uzden.uzdenbot.model.SubscriptionStatus;
import ru.uzden.uzdenbot.repository.SubscriptionRepository;

@Service
public class SubscriptionService {
    private final SubscriptionRepository subscriptionRepository;
    private final YooKassaClient yooKassaClient;
    private final XuiClient xuiClient;
    private final SubscriptionProperties subscriptionProperties;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                               YooKassaClient yooKassaClient,
                               XuiClient xuiClient,
                               SubscriptionProperties subscriptionProperties) {
        this.subscriptionRepository = subscriptionRepository;
        this.yooKassaClient = yooKassaClient;
        this.xuiClient = xuiClient;
        this.subscriptionProperties = subscriptionProperties;
    }

    public Optional<String> findActiveKey(Long telegramUserId) {
        return subscriptionRepository
                .findFirstByTelegramUserIdAndExpiresAtAfterOrderByExpiresAtDesc(telegramUserId, Instant.now())
                .map(Subscription::getVlessKey);
    }

    @Transactional
    public String createPayment(Long telegramUserId) {
        Subscription subscription = new Subscription(UUID.randomUUID(), telegramUserId, SubscriptionStatus.PENDING, Instant.now());
        subscriptionRepository.save(subscription);

        YooKassaClient.YooKassaPaymentResponse response = yooKassaClient.createPayment(
                subscriptionProperties.priceRubles(),
                "VPN подписка на " + subscriptionProperties.durationDays() + " дней",
                Map.of("subscriptionId", subscription.getId().toString(), "telegramUserId", telegramUserId.toString())
        );

        subscription.setPaymentId(response.id());
        subscription.setConfirmationUrl(response.confirmation().confirmationUrl());
        subscriptionRepository.save(subscription);

        return response.confirmation().confirmationUrl();
    }

    @Transactional
    public void handlePaymentSucceeded(YooKassaWebhookEvent event) {
        if (event == null || event.object() == null) {
            return;
        }
        subscriptionRepository.findByPaymentId(event.object().id())
                .filter(subscription -> subscription.getStatus() != SubscriptionStatus.ACTIVE)
                .ifPresent(subscription -> {
                    String key = xuiClient.createVlessKey(subscription.getTelegramUserId());
                    subscription.setVlessKey(key);
                    subscription.setStatus(SubscriptionStatus.ACTIVE);
                    subscription.setExpiresAt(Instant.now().plus(subscriptionProperties.durationDays(), ChronoUnit.DAYS));
                    subscriptionRepository.save(subscription);
                });
    }
}
