package ru.uzden.uzdenbot.repositories;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.uzden.uzdenbot.entities.Payment;
import ru.uzden.uzdenbot.entities.User;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByProviderPaymentId(String providerPaymentId);

    List<Payment> findTop100ByProcessedAtIsNullAndProviderOrderByCreatedAtAsc(String provider);

    List<Payment> findTop5ByUserAndProcessedAtIsNullAndProviderOrderByCreatedAtDesc(User user, String provider);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Payment lockById(@Param("id") Long id);
}
