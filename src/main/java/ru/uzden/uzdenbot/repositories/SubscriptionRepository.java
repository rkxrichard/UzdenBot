package ru.uzden.uzdenbot.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    @Query("""
           select s from Subscription s
           where s.user = :user
             and s.vpnKey is null
             and s.endDate > :now
           order by s.endDate desc
           """)
    List<Subscription> findActiveUnassigned(@Param("user") User user, @Param("now") LocalDateTime now);

    @Query("""
           select s from Subscription s
           where s.user = :user
             and s.endDate > :now
           order by s.endDate desc
           """)
    List<Subscription> findActiveByUser(@Param("user") User user, @Param("now") LocalDateTime now);

    List<Subscription> findByEndDateAfter(LocalDateTime now);
}
