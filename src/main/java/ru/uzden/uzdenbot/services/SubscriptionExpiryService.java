package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.repositories.SubscriptionRepository;
import ru.uzden.uzdenbot.repositories.VpnKeyRepository;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        List<VpnKey> keys = vpnKeyRepository.findActiveKeysWithUser();
        if (keys.isEmpty()) {
            return;
        }

        Set<Long> ensured = new HashSet<>();
        int revoked = 0;

        for (VpnKey key : keys) {
            Long keyId = key.getId();
            if (keyId == null) {
                continue;
            }
            if (subscriptionRepository.existsByVpnKeyIdAndEndDateAfter(keyId, now)) {
                continue;
            }

            Long userId = key.getUser() == null ? null : key.getUser().getId();
            if (userId != null
                    && !ensured.contains(userId)
                    && subscriptionRepository.existsByUserIdAndVpnKeyIsNullAndEndDateAfter(userId, now)) {
                try {
                    vpnKeyService.ensureKeyForActiveSubscription(key.getUser());
                } catch (Exception e) {
                    log.warn("Failed to attach unassigned subscription for userId={}: {}", userId, e.getMessage());
                }
                ensured.add(userId);
                if (subscriptionRepository.existsByVpnKeyIdAndEndDateAfter(keyId, now)) {
                    continue;
                }
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
