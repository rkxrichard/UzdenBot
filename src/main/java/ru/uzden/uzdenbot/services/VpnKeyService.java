package ru.uzden.uzdenbot.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.repositories.UserRepository;
import ru.uzden.uzdenbot.repositories.VpnKeyRepository;
import ru.uzden.uzdenbot.xui.ThreeXuiClient;
import ru.uzden.uzdenbot.xui.VlessLinkBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class VpnKeyService {

    private final VpnKeyRepository vpnKeyRepository;
    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;
    private final ThreeXuiClient xuiClient;
    private final VlessLinkBuilder linkBuilder;
    private final TransactionTemplate tx;

    private final Long inbound;
    private final String publicHost;
    private final int publicPort;
    private final String linkTag;

    @Autowired
    public VpnKeyService(
            VpnKeyRepository vpnKeyRepository,
            UserRepository userRepository,
            SubscriptionService subscriptionService,
            ThreeXuiClient xuiClient,
            VlessLinkBuilder linkBuilder,
            TransactionTemplate tx,
            @Value("${xui.inbound-id:1}") Long inbound,
            @Value("${xui.public-host:80.66.84.34}") String publicHost,
            @Value("${xui.public-port:443}") int publicPort,
            @Value("${xui.link-tag:reality443-auto}") String linkTag) {
        this.vpnKeyRepository = vpnKeyRepository;
        this.userRepository = userRepository;
        this.subscriptionService = subscriptionService;
        this.xuiClient = xuiClient;
        this.linkBuilder = linkBuilder;
        this.tx = tx;
        this.inbound = inbound;
        this.publicHost = publicHost;
        this.publicPort = publicPort;
        this.linkTag = linkTag;
    }


    /**
     * Выдать ключ пользователю.
     * Идемпотентно: если уже есть ACTIVE/PENDING - вернёт его.
     * Устойчиво: PENDING -> (3x-ui) -> ACTIVE, при ошибке FAILED.
     */
    public VpnKey issueKey(User user) {
        if(!subscriptionService.hasActiveSubscription(user)) {
            throw new IllegalStateException("Нет активной подписки");
        }


        // 1) в БД создаем/получаем PENDING или ACTIVE в транзакции
        VpnKey key = tx.execute(status -> createOrGetActiveOrPendingTx(user.getId()));


        // 2) если уже есть ACTIVE то просто возвращаю
        if (key.getStatus() == VpnKey.Status.ACTIVE && !key.isRevoked()) {
            return key;
        }


        // 3) если статус PENDING или FAILED стараемся довести до ACTIVE
        return finalizeIssueOutsideTx(key.getId());
    }


    /**
     * Отозвать ключ
     */
    public void revokeActiveKey(User user) {
        VpnKey key = tx.execute(status -> revokeTx(user.getId()));
        if (key == null) return;

        try {
            xuiClient.disableClient(key.getInboundId(), key.getClientUuid());
        } catch (Exception e) {
            log.error("Не удалось выключить ключ клиента: inbound:{}, uuid:{}", key.getInboundId(), key.getClientUuid());
            tx.execute(s -> markErrorTx(key.getId(), "disable failed: " + safeMsg(e)));
        }
    }


    /**
     * Заменить ключ
     * (сначала создается новый, потом отзывается старый)
     */
    public VpnKey replaceKey(User user) {

        Optional<VpnKey> oldActive = vpnKeyRepository.findActiveKey(user.getId());
        VpnKey newKey = issueKey(user);

        oldActive.ifPresent(old -> {
            if (!old.getId().equals(newKey.getId()) && old.isActive()) {
                try {
                    revokeById(old.getId());
                } catch (Exception e) {
                    log.error("Не удалось отозвать старый ключ id={}", old.getId(), e);
                }
            }
        });

        return newKey;
    }


    /**
     * Отозвать ключ по id
     */
    public void revokeById(long vpnKeyId) {
        VpnKey key = tx.execute(status -> revokeByIdTx(vpnKeyId));

        try {
            xuiClient.disableClient(key.getInboundId(), key.getClientUuid());
        } catch (Exception e) {
            log.error("Не удалось выключить ключ клиента: inbound:{}, uuid:{}", key.getInboundId(), key.getClientUuid());
            tx.execute(s -> markErrorTx(key.getId(), "disable failed: " + safeMsg(e)));
        }

    }


    /**
     * Попытаться доделать оставшиеся PENDING/FAILED старше порога (olderThan)
     * возвращает кол-во успешно выполненных
     */
    public int recoverStale(Duration olderThan) {
        Instant border = Instant.now().minus(olderThan);
        List<VpnKey> stale = vpnKeyRepository.findStale(border);

        int ok = 0;
        for (VpnKey k : stale) {
            try {
                finalizeIssueOutsideTx(k.getId());
            } catch (Exception e) {
                log.warn("Recovery не удался для keyId={}: {}", k.getId(), safeMsg(e));
            }
        }
        return ok;
    }



    /**==========================================================
     * ====================ВНУТРЕННЯЯ ЛОГИКА=====================
     * ==========================================================
     */

    private VpnKey createOrGetActiveOrPendingTx(Long userId) {
        userRepository.lockUser(userId);

        Optional<VpnKey> existing = vpnKeyRepository.findActiveOrPending(userId);
        if (existing.isPresent())
            return existing.get();

        VpnKey pending = new VpnKey();
        pending.setUser(userRepository.getReferenceById(userId));

        UUID clientUuid = UUID.randomUUID();
        pending.setClientUuid(clientUuid);

        // лучше стабильно, чтобы в панели было понятно чей ключ
        // telegramId у тебя есть в User
        Long tg = userRepository.getReferenceById(userId).getTelegramId();
        pending.setClientEmail("tg_" + tg);
        pending.setStatus(VpnKey.Status.PENDING);
        pending.setRevoked(false);

        pending.setKeyValue("PENDING:" + clientUuid);

        try {
            return vpnKeyRepository.save(pending);
        } catch (DataIntegrityViolationException e) {
            // Кто-то параллельно успел создать PENDING/ACTIVE (твой unique index из V4)
            return vpnKeyRepository.findActiveOrPending(userId)
                    .orElseThrow(() -> e);
        }


    }


    private VpnKey revokeTx(long userId) {
        userRepository.lockUser(userId);

        Optional<VpnKey> active = vpnKeyRepository.findActiveKey(userId);
        if (active.isEmpty()) return null;

        VpnKey key = active.get();
        if (key.getStatus() == VpnKey.Status.REVOKED || key.isRevoked()) return key;

        key.markRevoked();
        key.setLastError(null);
        return vpnKeyRepository.save(key);
    }

    private VpnKey revokeByIdTx(long vpnKeyId) {
        VpnKey key = vpnKeyRepository.findById(vpnKeyId).orElse(null);
        if (key == null) return null;

        userRepository.lockUser(key.getUser().getId());

        if (key.getStatus() == VpnKey.Status.REVOKED || key.isRevoked()) return key;

        key.markRevoked();
        key.setLastError(null);
        return vpnKeyRepository.save(key);
    }

    private Void markErrorTx(long vpnKeyId, String err) {
        VpnKey key = vpnKeyRepository.findById(vpnKeyId).orElse(null);
        if (key == null) return null;

        key.setLastError(err);
        return null;
    }

    private VpnKey markFailedTx(long vpnKeyId, String err) {
        VpnKey key = vpnKeyRepository.findById(vpnKeyId).orElseThrow();
        key.markFailed(err);
        return vpnKeyRepository.save(key);
    }

    private VpnKey activateTx(long vpnKeyId, String keyValue) {
        VpnKey key = vpnKeyRepository.findById(vpnKeyId).orElseThrow();
        key.markActive(keyValue);
        return vpnKeyRepository.save(key);
    }

    /** ======================================================================
     *  ================== ВНЕ TX: 3x-ui + построение ссылки =================
     *  ======================================================================

     /**
     * Доводим ключ до ACTIVE:
     *  - addClient (если он уже есть — можно обработать как идемпотентность)
     *  - getInbound
     *  - build vless://
     *  - сохранить ACTIVE
     *
     * Важно: не держим транзакцию на время HTTP запросов.
     */
    private VpnKey finalizeIssueOutsideTx(long keyId) {
        VpnKey key = vpnKeyRepository.findById(keyId).orElseThrow();

        // Если уже ACTIVE — вернуть
        if (key.getStatus() == VpnKey.Status.ACTIVE && !key.isRevoked()) {
            return key;
        }

        // Если отозван — не выдаём
        if (key.isRevoked() || key.getStatus() == VpnKey.Status.REVOKED) {
            throw new IllegalStateException("Ключ отозван");
        }

        try {
            // 1) создаём клиента в 3x-ui
            xuiClient.addClient(key.getInboundId(), key.getClientUuid(), key.getClientEmail());

            // 2) берём inbound json (твой inbound содержит streamSettings/settings)
            String inboundJson = xuiClient.getInbound(key.getInboundId());

            // 3) строим ссылку vless://... на основе inbound
            String vlessLink = linkBuilder.buildRealityLink(
                    inboundJson,
                    publicHost,
                    publicPort,
                    key.getClientUuid(),
                    linkTag
            );

            // 4) финализируем в БД
            return tx.execute(status -> activateTx(keyId, vlessLink));

        } catch (Exception e) {
            log.error("Ошибка выпуска ключа keyId={} inbound={} uuid={}", keyId, key.getInboundId(), key.getClientUuid(), e);

            // помечаем FAILED
            tx.execute(status -> markFailedTx(keyId, safeMsg(e)));

            // компенсация (по желанию):
            // можно delete или disable — чаще disable безопаснее
            try {
                xuiClient.disableClient(key.getInboundId(), key.getClientUuid());
            } catch (Exception ignored) {
            }

            throw new IllegalStateException("Не удалось выпустить ключ: " + safeMsg(e), e);
        }
    }

    private String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }
}
