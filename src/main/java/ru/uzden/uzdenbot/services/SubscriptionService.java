package ru.uzden.uzdenbot.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.uzden.uzdenbot.entities.Subscription;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.repositories.SubscriptionRepository;
import ru.uzden.uzdenbot.repositories.UserRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    public SubscriptionService(SubscriptionRepository subscriptionRepository, UserRepository userRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
    }

    public boolean hasActiveSubscription(User user) {
        if (user == null || user.isDisabled()) return false;
        return subscriptionRepository.findTopByUserAndEndDateAfterOrderByEndDateDesc(user, LocalDateTime.now()).isPresent();
    }


    @Transactional
    public Subscription extendSubscription(User user, int days) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User is required");
        }
        userRepository.lockUser(user.getId());

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
        subscription.setActive(true);

        return subscriptionRepository.save(subscription);
    }

    @Transactional(readOnly = true)
    public Optional<Subscription> getActiveSubscription(User user) {
        return subscriptionRepository.findTopByUserAndEndDateAfterOrderByEndDateDesc(
                user, LocalDateTime.now()
        );
    }

    @Transactional
    public Optional<Subscription> revokeActiveSubscription(User user) {
        if (user == null || user.getId() == null) {
            return Optional.empty();
        }
        userRepository.lockUser(user.getId());
        Optional<Subscription> active = getActiveSubscription(user);
        if (active.isEmpty()) return Optional.empty();
        Subscription sub = active.get();
        sub.setEndDate(LocalDateTime.now());
        sub.setActive(false);
        return Optional.of(subscriptionRepository.save(sub));
    }

    public long getDaysLeft(Subscription sub) {
        // считаем дни до конца, округляя вверх: осталось 0.2 дня -> покажем 1 день
        long minutesLeft = Duration.between(LocalDateTime.now(), sub.getEndDate()).toMinutes();
        if (minutesLeft <= 0) return 0;
        return (long) Math.ceil(minutesLeft / 1440.0);
    }

}
