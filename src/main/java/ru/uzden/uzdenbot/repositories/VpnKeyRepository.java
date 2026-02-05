package ru.uzden.uzdenbot.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.uzden.uzdenbot.entities.VpnKey;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface VpnKeyRepository extends JpaRepository<VpnKey, Long> {

    @Query("""
           select k from VpnKey k
           where k.user.id = :userId
             and k.revoked = false
             and k.status in (ru.uzden.uzdenbot.entities.VpnKey$Status.PENDING,
                              ru.uzden.uzdenbot.entities.VpnKey$Status.ACTIVE)
           order by k.createdAt desc
           """)
    Optional<VpnKey> findActiveOrPending(@Param("userId") long userId);

    @Query("""
           select k from VpnKey k
           where k.user.id = :userId
             and k.revoked = false
             and k.status = ru.uzden.uzdenbot.entities.VpnKey$Status.ACTIVE
           order by k.createdAt desc
           """)
    Optional<VpnKey> findActiveKey(@Param("userId") long userId);

    @Query("""
           select k from VpnKey k
           where k.revoked = false
             and k.status in (ru.uzden.uzdenbot.entities.VpnKey$Status.PENDING,
                              ru.uzden.uzdenbot.entities.VpnKey$Status.FAILED)
             and k.updatedAt < :border
           order by k.updatedAt asc
           """)
    List<VpnKey> findStale(@Param("border") Instant border);
}
