package ru.uzden.uzdenbot.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.uzden.uzdenbot.entities.Subscription;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.repositories.SubscriptionRepository;
import ru.uzden.uzdenbot.repositories.UserRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

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

    public boolean hasActiveSubscriptionForKey(VpnKey key) {
        if (key == null) return false;
        return subscriptionRepository.findTopByVpnKeyAndEndDateAfterOrderByEndDateDesc(key, LocalDateTime.now()).isPresent();
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

    @Transactional
    public Subscription extendSubscriptionForKey(User user, VpnKey key, int days) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User is required");
        }
        if (key == null || key.getId() == null) {
            throw new IllegalArgumentException("Key is required");
        }
        userRepository.lockUser(user.getId());

        LocalDateTime now = LocalDateTime.now();
        Instant createdAt = Instant.now();

        LocalDateTime start = subscriptionRepository
                .findTopByVpnKeyAndEndDateAfterOrderByEndDateDesc(key, now)
                .map(Subscription::getEndDate)
                .orElse(now);

        Subscription subscription = new Subscription();
        subscription.setUser(user);
        subscription.setVpnKey(key);
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

    @Transactional(readOnly = true)
    public Optional<Subscription> getActiveSubscription(VpnKey key) {
        if (key == null) return Optional.empty();
        return subscriptionRepository.findTopByVpnKeyAndEndDateAfterOrderByEndDateDesc(
                key, LocalDateTime.now()
        );
    }

    @Transactional(readOnly = true)
    public Optional<Subscription> getLastSubscription(User user) {
        if (user == null) return Optional.empty();
        return subscriptionRepository.findTopByUserOrderByEndDateDesc(user);
    }

    @Transactional(readOnly = true)
    public Optional<Subscription> getLastSubscription(VpnKey key) {
        if (key == null) return Optional.empty();
        return subscriptionRepository.findTopByVpnKeyOrderByEndDateDesc(key);
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

    @Transactional
    public int revokeAllActiveSubscriptions(User user) {
        if (user == null || user.getId() == null) {
            return 0;
        }
        userRepository.lockUser(user.getId());
        List<Subscription> active = subscriptionRepository.findActiveByUser(user, LocalDateTime.now());
        if (active.isEmpty()) return 0;
        LocalDateTime now = LocalDateTime.now();
        for (Subscription sub : active) {
            sub.setEndDate(now);
            sub.setActive(false);
            subscriptionRepository.save(sub);
        }
        return active.size();
    }

    public long getDaysLeft(Subscription sub) {
        // считаем дни до конца, округляя вверх: осталось 0.2 дня -> покажем 1 день
        long minutesLeft = Duration.between(LocalDateTime.now(), sub.getEndDate()).toMinutes();
        if (minutesLeft <= 0) return 0;
        return (long) Math.ceil(minutesLeft / 1440.0);
    }

}
