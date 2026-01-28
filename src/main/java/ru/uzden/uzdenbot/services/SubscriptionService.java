package ru.uzden.uzdenbot.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.uzden.uzdenbot.entities.Subscription;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.repositories.SubscriptionRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;

    @Autowired
    public SubscriptionService(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    public boolean hasActiveSubscription(User user) {
        return subscriptionRepository.findTopByUserAndEndDateAfterOrderByEndDateDesc(user, LocalDateTime.now()).isPresent();
    }


    @Transactional
    public Subscription extendSubscription(User user, int days) {

        LocalDateTime now = LocalDateTime.now();
        Instant createdAt = Instant.now();

        // Проверка активной подписки
        LocalDateTime start = subscriptionRepository
                .findTopByUserAndEndDateAfterOrderByEndDateDesc(user, now)
                .map(Subscription::getEndDate)
                .orElse(now);

        Subscription subscription = new Subscription();
        subscription.setUser(user);
        subscription.setStartDate(start);
        subscription.setEndDate(start.plusDays(days));
        subscription.setCreatedAt(createdAt);

        return subscriptionRepository.save(subscription);
    }

    @Transactional(readOnly = true)
    public Optional<Subscription> getActiveSubscription(User user) {
        return subscriptionRepository.findTopByUserAndEndDateAfterOrderByEndDateDesc(
                user, LocalDateTime.now()
        );
    }

    public long getDaysLeft(Subscription sub) {
        // считаем дни до конца, округляя вверх: осталось 0.2 дня -> покажем 1 день
        long minutesLeft = Duration.between(LocalDateTime.now(), sub.getEndDate()).toMinutes();
        if (minutesLeft <= 0) return 0;
        return (long) Math.ceil(minutesLeft / 1440.0);
    }

}
