package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.repositories.SubscriptionRepository;
import ru.uzden.uzdenbot.repositories.VpnKeyRepository;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionExpiryService {

    private final VpnKeyRepository vpnKeyRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final VpnKeyService vpnKeyService;

    @Scheduled(fixedDelayString = "${app.subscriptions.expire-check-delay-ms:300000}")
    public void revokeExpiredKeys() {
        LocalDateTime now = LocalDateTime.now();
        List<VpnKey> keys = vpnKeyRepository.findActiveKeysWithoutSubscription(now);
        List<Long> usersWithUnassigned = subscriptionRepository.findUsersWithActiveUnassigned(now);
        if (!usersWithUnassigned.isEmpty()) {
            for (Long userId : usersWithUnassigned) {
                if (userId == null) continue;
                try {
                    vpnKeyService.ensureKeyForActiveSubscriptionByUserId(userId);
                } catch (Exception e) {
                    log.warn("Failed to attach unassigned subscription for userId={}: {}", userId, e.getMessage());
                }
            }
            keys = vpnKeyRepository.findActiveKeysWithoutSubscription(now);
        }

        if (keys.isEmpty()) {
            return;
        }

        int revoked = 0;
        for (VpnKey key : keys) {
            Long keyId = key.getId();
            if (keyId == null) {
                continue;
            }
            try {
                vpnKeyService.revokeById(keyId);
                revoked++;
            } catch (Exception e) {
                log.warn("Failed to revoke expired keyId={}: {}", keyId, e.getMessage());
            }
        }

        if (revoked > 0) {
            log.info("Revoked keys without active subscription: {}", revoked);
        }
    }
}
