package ru.uzden.uzdenbot.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.uzden.uzdenbot.entities.ReferralLink;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReferralLinkRepository extends JpaRepository<ReferralLink, Long> {
    Optional<ReferralLink> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<ReferralLink> findByReferrerUserIdOrderByCreatedAtDesc(Long referrerUserId);
}
