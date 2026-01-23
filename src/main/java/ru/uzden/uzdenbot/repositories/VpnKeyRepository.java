package ru.uzden.uzdenbot.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.uzden.uzdenbot.entities.VpnKey;

@Repository
public interface VpnKeyRepository extends JpaRepository<VpnKey, Long> {
}
