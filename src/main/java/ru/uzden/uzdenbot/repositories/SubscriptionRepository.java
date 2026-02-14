package ru.uzden.uzdenbot.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.uzden.uzdenbot.entities.Subscription;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.entities.VpnKey;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    // Последняя подписка (по endDate)
    Optional<Subscription> findTopByUserOrderByEndDateDesc(User user);

    // Активная подписка
    Optional<Subscription> findTopByUserAndEndDateAfterOrderByEndDateDesc(User user, LocalDateTime now);

    // Активная подписка по ключу
    Optional<Subscription> findTopByVpnKeyAndEndDateAfterOrderByEndDateDesc(VpnKey vpnKey, LocalDateTime now);

    // Последняя подписка по ключу
    Optional<Subscription> findTopByVpnKeyOrderByEndDateDesc(VpnKey vpnKey);

    List<Subscription> findByEndDateAfter(LocalDateTime now);
}
