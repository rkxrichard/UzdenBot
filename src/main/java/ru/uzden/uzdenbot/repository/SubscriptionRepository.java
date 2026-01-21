package ru.uzden.uzdenbot.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.uzden.uzdenbot.model.Subscription;
import ru.uzden.uzdenbot.model.SubscriptionStatus;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    Optional<Subscription> findFirstByTelegramUserIdAndStatusOrderByCreatedAtDesc(Long telegramUserId,
                                                                                 SubscriptionStatus status);

    Optional<Subscription> findByPaymentId(String paymentId);

    Optional<Subscription> findFirstByTelegramUserIdAndExpiresAtAfterOrderByExpiresAtDesc(Long telegramUserId,
                                                                                         Instant instant);
}
