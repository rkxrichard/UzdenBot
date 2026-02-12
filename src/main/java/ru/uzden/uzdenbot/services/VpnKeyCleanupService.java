package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.repositories.VpnKeyRepository;
import ru.uzden.uzdenbot.xui.ThreeXuiClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class VpnKeyCleanupService {

    private final VpnKeyRepository vpnKeyRepository;
    private final ThreeXuiClient threeXuiClient;

    @Value("${app.vpn-keys.unused-ttl-hours:24}")
    private long unusedTtlHours;

    @Scheduled(fixedDelayString = "${app.vpn-keys.cleanup-delay-ms:3600000}")
    public void cleanupUnusedKeys() {
        Instant border = Instant.now().minus(Duration.ofHours(unusedTtlHours));
        cleanupPendingAndFailed(border);
        cleanupActiveWithoutTraffic(border);
    }

    @Transactional
    protected void cleanupPendingAndFailed(Instant border) {
        List<VpnKey> stale = vpnKeyRepository.findPendingOrFailedOlderThan(border);
        for (VpnKey key : stale) {
            tryDisableInXui(key);
            vpnKeyRepository.delete(key);
        }
        if (!stale.isEmpty()) {
            log.info("Removed stale pending/failed keys: {}", stale.size());
        }
    }

    @Transactional
    protected void cleanupActiveWithoutTraffic(Instant border) {
        List<VpnKey> candidates = vpnKeyRepository.findActiveOlderThan(border);
        int removed = 0;
        for (VpnKey key : candidates) {
            OptionalLong traffic = threeXuiClient.getClientTraffic(
                    key.getInboundId(), key.getClientUuid(), key.getClientEmail()
            );
            if (traffic.isEmpty()) {
                continue;
            }
            if (traffic.getAsLong() > 0) {
                continue;
            }
            tryDisableInXui(key);
            vpnKeyRepository.delete(key);
            removed++;
        }
        if (removed > 0) {
            log.info("Removed unused active keys: {}", removed);
        }
    }

    private void tryDisableInXui(VpnKey key) {
        try {
            if (key.getClientUuid() != null && key.getInboundId() != null) {
                threeXuiClient.disableClient(key.getInboundId(), key.getClientUuid());
            }
        } catch (Exception e) {
            log.warn("Failed to disable client in 3x-ui for keyId={}: {}", key.getId(), e.getMessage());
        }
    }
}
