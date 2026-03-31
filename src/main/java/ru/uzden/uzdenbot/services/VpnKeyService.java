package ru.uzden.uzdenbot.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import ru.uzden.uzdenbot.config.RuEuXuiProperties;
import ru.uzden.uzdenbot.config.XuiProperties;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.entities.Subscription;
import ru.uzden.uzdenbot.repositories.UserRepository;
import ru.uzden.uzdenbot.repositories.VpnKeyRepository;
import ru.uzden.uzdenbot.repositories.SubscriptionRepository;
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
    private final SubscriptionRepository subscriptionRepository;
    private final VlessLinkBuilder linkBuilder;
    private final TransactionTemplate tx;

    private final BackendRuntime defaultBackend;
    private final BackendRuntime ruEuBackend;

    private static final int MAX_KEYS_PER_USER = 3;

    @Autowired
    public VpnKeyService(
            VpnKeyRepository vpnKeyRepository,
            UserRepository userRepository,
            SubscriptionService subscriptionService,
            SubscriptionRepository subscriptionRepository,
            ThreeXuiClient xuiClient,
            VlessLinkBuilder linkBuilder,
            TransactionTemplate tx,
            XuiProperties xuiProperties,
            RuEuXuiProperties ruEuXuiProperties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${xui.link-group:}") String linkGroup) {
        this.vpnKeyRepository = vpnKeyRepository;
        this.userRepository = userRepository;
        this.subscriptionService = subscriptionService;
        this.subscriptionRepository = subscriptionRepository;
        this.linkBuilder = linkBuilder;
        this.tx = tx;
        this.defaultBackend = buildBackendRuntime(VpnKey.Backend.DEFAULT, xuiProperties, xuiClient, linkGroup);
        if (ruEuXuiProperties != null && ruEuXuiProperties.configured()) {
            XuiProperties ruEuResolved = ruEuXuiProperties.toXuiProperties();
            ThreeXuiClient ruEuClient = new ThreeXuiClient(restClientBuilder, ruEuResolved, objectMapper);
            this.ruEuBackend = buildBackendRuntime(VpnKey.Backend.RU_EU, ruEuResolved, ruEuClient, linkGroup);
        } else {
            this.ruEuBackend = null;
        }
    }

    public int getMaxKeysPerUser() {
        return MAX_KEYS_PER_USER;
    }

    public long countActiveKeys(User user) {
        if (user == null || user.getId() == null) return 0;
        return vpnKeyRepository.countActiveKeys(user.getId());
    }

    public boolean canCreateNewKey(User user) {
        if (user == null || user.getId() == null) return false;
        long existing = vpnKeyRepository.countNonRevokedKeys(user.getId());
        return existing < MAX_KEYS_PER_USER;
    }

    public List<VpnKey> listUserKeys(User user) {
        if (user == null || user.getId() == null) return List.of();
        return vpnKeyRepository.findUserKeys(user.getId());
    }

    public VpnKey findKeyForUser(User user, long keyId) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User is required");
        }
        VpnKey key = vpnKeyRepository.findByIdAndUserId(keyId, user.getId())
                .orElseThrow(() -> new IllegalStateException("Ключ не найден"));
        if (key.isRevoked() || key.getStatus() == VpnKey.Status.REVOKED) {
            throw new IllegalStateException("Ключ отозван");
        }
        return key;
    }

    public Optional<Subscription> getActiveSubscriptionForKey(VpnKey key) {
        return subscriptionService.getActiveSubscription(key);
    }

    public boolean requiresActiveSubscription(VpnKey key) {
        if (key == null || key.getBackend() == null) {
            return true;
        }
        return key.getBackend() != VpnKey.Backend.RU_EU;
    }

    public boolean canDeleteKey(User user, long keyId) {
        VpnKey key = findKeyForUser(user, keyId);
        return !subscriptionService.hasActiveSubscriptionForKey(key);
    }

    public void ensureKeyForActiveSubscription(User user) {
        if (user == null || user.getId() == null) return;
        tx.execute(status -> ensureKeyForActiveSubscriptionTx(user.getId()));
    }

    public void ensureKeyForActiveSubscriptionByUserId(Long userId) {
        if (userId == null) return;
        tx.execute(status -> ensureKeyForActiveSubscriptionTx(userId));
    }

    public VpnKey replaceKeyForUser(User user, long keyId) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User is required");
        }
        ReplaceContext ctx = tx.execute(status -> replaceKeyForUserTx(user.getId(), keyId));

        VpnKey newKey = finalizeIssueOutsideTx(ctx.newKeyId);

        if (ctx.oldInboundId != null && ctx.oldClientUuid != null) {
            try {
                clientFor(ctx.oldBackend).disableClient(ctx.oldInboundId, ctx.oldClientUuid);
            } catch (Exception e) {
                log.error("Не удалось выключить старый ключ клиента: inbound:{}, uuid:{}", ctx.oldInboundId, ctx.oldClientUuid, e);
            }
        }

        return newKey;
    }

    public VpnKey getKeyForUser(User user, long keyId) {
        VpnKey key = findKeyForUser(user, keyId);
        if (requiresActiveSubscription(key) && !subscriptionService.hasActiveSubscriptionForKey(key)) {
            throw new IllegalStateException("Нет активной подписки для этого ключа");
        }

        if (key.getStatus() == VpnKey.Status.ACTIVE) {
            if (needsLinkRefresh(key)) {
                return refreshActiveLink(key);
            }
            return key;
        }

        return finalizeIssueOutsideTx(key.getId());
    }

    public VpnKey createPendingKey(User user) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User is required");
        }
        return tx.execute(status -> createNewPendingKeyTx(user.getId(), VpnKey.Backend.DEFAULT));
    }

    public VpnKey issueRuEuKey(User user) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User is required");
        }
        ensureBackendConfigured(VpnKey.Backend.RU_EU);
        VpnKey key = tx.execute(status -> createNewPendingKeyTx(user.getId(), VpnKey.Backend.RU_EU));
        return finalizeIssueOutsideTx(key.getId());
    }

    public void revokeKeyForUser(User user, long keyId) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User is required");
        }
        VpnKey key = tx.execute(status -> revokeByIdForUserTx(user.getId(), keyId));
        if (key == null) {
            throw new IllegalStateException("Ключ не найден");
        }

        try {
            clientFor(key.getBackend()).disableClient(key.getInboundId(), key.getClientUuid());
        } catch (Exception e) {
            log.error("Не удалось выключить ключ клиента: inbound:{}, uuid:{}", key.getInboundId(), key.getClientUuid());
            tx.execute(s -> markErrorTx(key.getId(), "disable failed: " + safeMsg(e)));
        }
    }

    /**
     * Выдать ключ пользователю.
     * Создаёт новый ключ (максимум 3 на пользователя).
     * Устойчиво: PENDING -> (3x-ui) -> ACTIVE, при ошибке FAILED.
     */
    public VpnKey issueKey(User user) {
        VpnKey key = tx.execute(status -> createNewPendingKeyTx(user.getId(), VpnKey.Backend.DEFAULT));
        return finalizeIssueOutsideTx(key.getId());
    }

    /**
     * Автовыдача ключа (после оплаты) без pessimistic-lock на user.
     * Уменьшает риск ошибки "no active transaction" в фоновом обработчике.
     */
    public VpnKey issueKeyAuto(User user) {
        VpnKey key = tx.execute(status -> createNewPendingKeyTx(user.getId(), VpnKey.Backend.DEFAULT));
        return finalizeIssueOutsideTx(key.getId());
    }


    /**
     * Отозвать ключ
     */
    public void revokeActiveKey(User user) {
        VpnKey key = tx.execute(status -> revokeTx(user.getId()));
        if (key == null) return;

        try {
            clientFor(key.getBackend()).disableClient(key.getInboundId(), key.getClientUuid());
        } catch (Exception e) {
            log.error("Не удалось выключить ключ клиента: inbound:{}, uuid:{}", key.getInboundId(), key.getClientUuid());
            tx.execute(s -> markErrorTx(key.getId(), "disable failed: " + safeMsg(e)));
        }
    }

    public int revokeAllKeys(User user) {
        if (user == null || user.getId() == null) return 0;
        List<VpnKey> keys = vpnKeyRepository.findUserKeys(user.getId());
        if (keys.isEmpty()) return 0;
        for (VpnKey key : keys) {
            tx.execute(status -> {
                VpnKey fresh = vpnKeyRepository.findById(key.getId()).orElse(null);
                if (fresh == null) return null;
                if (fresh.getStatus() == VpnKey.Status.REVOKED || fresh.isRevoked()) return null;
                fresh.markRevoked();
                fresh.setLastError(null);
                vpnKeyRepository.save(fresh);
                return null;
            });
            try {
                if (key.getInboundId() != null && key.getClientUuid() != null) {
                    clientFor(key.getBackend()).disableClient(key.getInboundId(), key.getClientUuid());
                }
            } catch (Exception e) {
                log.warn("Не удалось выключить ключ клиента keyId={}: {}", key.getId(), safeMsg(e));
            }
        }
        return keys.size();
    }


    /**
     * Заменить ключ
     * (сначала создается новый, потом отзывается старый)
     */
    public VpnKey replaceKey(User user) {
        ReplaceContext ctx = tx.execute(status -> replaceTx(user.getId()));

        VpnKey newKey = finalizeIssueOutsideTx(ctx.newKeyId);

        if (ctx.oldInboundId != null && ctx.oldClientUuid != null) {
            try {
                clientFor(ctx.oldBackend).disableClient(ctx.oldInboundId, ctx.oldClientUuid);
            } catch (Exception e) {
                log.error("Не удалось выключить старый ключ клиента: inbound:{}, uuid:{}", ctx.oldInboundId, ctx.oldClientUuid, e);
            }
        }

        return newKey;
    }


    /**
     * Отозвать ключ по id
     */
    public void revokeById(long vpnKeyId) {
        VpnKey key = tx.execute(status -> revokeByIdTx(vpnKeyId));

        try {
            clientFor(key.getBackend()).disableClient(key.getInboundId(), key.getClientUuid());
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

    /**
     * Удалить все отключённые ключи (revoked/REVOKED).
     * Возвращает количество удалённых записей.
     */
    public int purgeRevokedKeys() {
        List<VpnKey> revoked = vpnKeyRepository.findRevokedKeys();
        if (revoked.isEmpty()) {
            return 0;
        }
        for (VpnKey key : revoked) {
            try {
                if (key.getInboundId() != null && key.getClientUuid() != null) {
                    clientFor(key.getBackend()).disableClient(key.getInboundId(), key.getClientUuid());
                }
            } catch (Exception e) {
                log.warn("Не удалось выключить клиента keyId={}: {}", key.getId(), safeMsg(e));
            }
        }
        List<Long> ids = revoked.stream().map(VpnKey::getId).toList();
        tx.execute(status -> {
            vpnKeyRepository.deleteAllByIdInBatch(ids);
            return null;
        });
        return ids.size();
    }

    public int purgeDisabledUsers() {
        List<User> disabled = userRepository.findByDisabledTrue();
        if (disabled.isEmpty()) {
            return 0;
        }
        for (User user : disabled) {
            try {
                List<VpnKey> keys = vpnKeyRepository.findUserKeys(user.getId());
                for (VpnKey key : keys) {
                    try {
                        if (key.getInboundId() != null && key.getClientUuid() != null) {
                            clientFor(key.getBackend()).disableClient(key.getInboundId(), key.getClientUuid());
                        }
                    } catch (Exception e) {
                        log.warn("Не удалось выключить ключ клиента keyId={}: {}", key.getId(), safeMsg(e));
                    }
                }
            } catch (Exception e) {
                log.warn("Ошибка при обработке отключенного пользователя userId={}: {}", user.getId(), safeMsg(e));
            }
        }
        tx.execute(status -> {
            userRepository.deleteAllInBatch(disabled);
            return null;
        });
        return disabled.size();
    }



    /**==========================================================
     * ====================ВНУТРЕННЯЯ ЛОГИКА=====================
     * ==========================================================
     */

    private VpnKey createNewPendingKeyTx(Long userId, VpnKey.Backend backend) {
        userRepository.lockUser(userId);
        ensureKeyLimit(userId);
        return vpnKeyRepository.saveAndFlush(buildPendingKey(userId, backend));
    }

    private Void ensureKeyForActiveSubscriptionTx(Long userId) {
        userRepository.lockUser(userId);
        List<ru.uzden.uzdenbot.entities.Subscription> unassigned = subscriptionRepository.findActiveUnassigned(
                userRepository.getReferenceById(userId),
                java.time.LocalDateTime.now()
        );
        if (unassigned.isEmpty()) {
            return null;
        }

        VpnKey key = vpnKeyRepository.findUserKeys(userId).stream()
                .filter(existing -> existing.getBackend() == VpnKey.Backend.DEFAULT)
                .findFirst()
                .or(() -> vpnKeyRepository.findFirstNonRevoked(userId))
                .orElse(null);
        if (key == null) {
            ensureKeyLimit(userId);
            key = vpnKeyRepository.saveAndFlush(buildPendingKey(userId, VpnKey.Backend.DEFAULT));
        }

        for (ru.uzden.uzdenbot.entities.Subscription sub : unassigned) {
            sub.setVpnKey(key);
            subscriptionRepository.save(sub);
        }
        return null;
    }

    private VpnKey createOrGetActiveOrPendingTx(Long userId) {
        userRepository.lockUser(userId);

        Optional<VpnKey> existing = vpnKeyRepository.findActiveOrPending(userId);
        if (existing.isPresent())
            return existing.get();

        try {
            ensureKeyLimit(userId);
            return vpnKeyRepository.saveAndFlush(buildPendingKey(userId, VpnKey.Backend.DEFAULT));
        } catch (DataIntegrityViolationException e) {
            // Кто-то параллельно успел создать PENDING/ACTIVE
            return vpnKeyRepository.findActiveOrPending(userId)
                    .orElseThrow(() -> e);
        }


    }

    private VpnKey createOrGetActiveOrPendingNoLock(Long userId) {
        Optional<VpnKey> existing = vpnKeyRepository.findActiveOrPending(userId);
        if (existing.isPresent())
            return existing.get();

        try {
            ensureKeyLimit(userId);
            return vpnKeyRepository.saveAndFlush(buildPendingKey(userId, VpnKey.Backend.DEFAULT));
        } catch (DataIntegrityViolationException e) {
            return vpnKeyRepository.findActiveOrPending(userId)
                    .orElseThrow(() -> e);
        }
    }

    private ReplaceContext replaceTx(Long userId) {
        userRepository.lockUser(userId);

        VpnKey old = vpnKeyRepository.findActiveOrPending(userId).orElse(null);
        if (old != null) {
            old.markRevoked();
            old.setLastError(null);
            vpnKeyRepository.save(old);
            vpnKeyRepository.flush();
        } else {
            ensureKeyLimit(userId);
        }

        VpnKey.Backend backend = old == null ? VpnKey.Backend.DEFAULT : old.getBackend();
        Long targetInbound = old == null
                ? backendConfig(backend).inbound()
                : resolveReplacementInbound(backend, old.getInboundId());
        VpnKey pending = vpnKeyRepository.save(buildPendingKey(userId, backend, targetInbound));
        return new ReplaceContext(
                pending.getId(),
                old == null ? null : old.getBackend(),
                old == null ? null : old.getInboundId(),
                old == null ? null : old.getClientUuid()
        );
    }

    private ReplaceContext replaceKeyForUserTx(Long userId, long keyId) {
        userRepository.lockUser(userId);

        VpnKey old = vpnKeyRepository.findByIdAndUserId(keyId, userId).orElse(null);
        if (old == null) {
            throw new IllegalStateException("Ключ не найден");
        }
        if (old.getStatus() == VpnKey.Status.REVOKED || old.isRevoked()) {
            throw new IllegalStateException("Ключ отозван");
        }
        if (requiresActiveSubscription(old) && !subscriptionService.hasActiveSubscriptionForKey(old)) {
            throw new IllegalStateException("Нет активной подписки для этого ключа");
        }

        old.markRevoked();
        old.setLastError(null);
        vpnKeyRepository.save(old);
        vpnKeyRepository.flush();

        VpnKey pending = vpnKeyRepository.save(buildPendingKey(
                userId,
                old.getBackend(),
                resolveReplacementInbound(old.getBackend(), old.getInboundId())
        ));

        Subscription activeSub = subscriptionRepository
                .findTopByVpnKeyAndEndDateAfterOrderByEndDateDesc(old, java.time.LocalDateTime.now())
                .orElse(null);
        if (activeSub == null && requiresActiveSubscription(old)) {
            throw new IllegalStateException("Нет активной подписки для этого ключа");
        }
        if (activeSub != null) {
            activeSub.setVpnKey(pending);
            subscriptionRepository.save(activeSub);
        }

        return new ReplaceContext(
                pending.getId(),
                old.getBackend(),
                old.getInboundId(),
                old.getClientUuid()
        );
    }

    private VpnKey buildPendingKey(Long userId, VpnKey.Backend backend) {
        return buildPendingKey(userId, backend, backendConfig(backend).inbound());
    }

    private VpnKey buildPendingKey(Long userId, VpnKey.Backend backend, Long targetInbound) {
        BackendRuntime backendRuntime = backendConfig(backend);
        VpnKey pending = new VpnKey();
        User userRef = userRepository.getReferenceById(userId);
        pending.setUser(userRef);
        pending.setBackend(backend);
        pending.setInboundId(targetInbound == null ? backendRuntime.inbound() : targetInbound);

        UUID clientUuid = UUID.randomUUID();
        pending.setClientUuid(clientUuid);

        // лучше стабильно, чтобы в панели было понятно чей ключ
        // telegramId у тебя есть в User
        Long tg = userRef.getTelegramId();
        String uname = normalizeUsername(userRef.getUsername());
        // В 3x-ui поле email должно быть уникальным внутри inbound.
        // Поэтому добавляем кусок uuid, чтобы повторная/параллельная выдача не падала Duplicate email.
        String shortUuid = clientUuid.toString().substring(0, 8);
        String prefix = backend == VpnKey.Backend.RU_EU ? "rueu_tg_" : "tg_";
        String identity = (uname == null) ? prefix + tg : prefix + uname + "_" + tg;
        pending.setClientEmail(identity + "_" + shortUuid);
        pending.setStatus(VpnKey.Status.PENDING);
        pending.setRevoked(false);

        pending.setKeyValue("PENDING:" + clientUuid);
        return pending;
    }

    private void ensureKeyLimit(Long userId) {
        long existing = vpnKeyRepository.countNonRevokedKeys(userId);
        if (existing >= MAX_KEYS_PER_USER) {
            throw new IllegalStateException("Достигнут лимит ключей (" + MAX_KEYS_PER_USER + ")");
        }
    }

    private static final class ReplaceContext {
        final long newKeyId;
        final VpnKey.Backend oldBackend;
        final Long oldInboundId;
        final UUID oldClientUuid;

        private ReplaceContext(long newKeyId, VpnKey.Backend oldBackend, Long oldInboundId, UUID oldClientUuid) {
            this.newKeyId = newKeyId;
            this.oldBackend = oldBackend;
            this.oldInboundId = oldInboundId;
            this.oldClientUuid = oldClientUuid;
        }
    }

    private String normalizeUsername(String username) {
        if (username == null) return null;
        String trimmed = username.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append('_');
            }
        }
        String normalized = sb.toString().replaceAll("_+", "_");
        normalized = normalized.replaceAll("^_+", "").replaceAll("_+$", "");
        return normalized.isEmpty() ? null : normalized;
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

    private VpnKey revokeByIdForUserTx(long userId, long vpnKeyId) {
        VpnKey key = vpnKeyRepository.findByIdAndUserId(vpnKeyId, userId).orElse(null);
        if (key == null) return null;

        userRepository.lockUser(userId);

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

    private BackendRuntime buildBackendRuntime(
            VpnKey.Backend backend,
            XuiProperties props,
            ThreeXuiClient client,
            String linkGroup
    ) {
        long primaryInbound = props.inboundId();
        long effectiveXhttpInbound = props.xhttpInboundId() > 0 ? props.xhttpInboundId() : primaryInbound;
        return new BackendRuntime(
                backend,
                client,
                primaryInbound,
                effectiveXhttpInbound,
                props.publicHost(),
                props.publicPort(),
                props.xhttpPort(),
                props.linkTag(),
                props.xhttpLinkTag(),
                linkGroup
        );
    }

    private BackendRuntime backendConfig(VpnKey.Backend backend) {
        VpnKey.Backend resolved = backend == null ? VpnKey.Backend.DEFAULT : backend;
        return switch (resolved) {
            case DEFAULT -> defaultBackend;
            case RU_EU -> {
                ensureBackendConfigured(VpnKey.Backend.RU_EU);
                yield ruEuBackend;
            }
        };
    }

    private void ensureBackendConfigured(VpnKey.Backend backend) {
        if (backend == VpnKey.Backend.RU_EU && ruEuBackend == null) {
            throw new IllegalStateException("RU+EU панель не настроена");
        }
    }

    private ThreeXuiClient clientFor(VpnKey.Backend backend) {
        return backendConfig(backend).client();
    }

    private VpnKey finalizeIssueOutsideTx(long keyId) {
        VpnKey key = vpnKeyRepository.findById(keyId).orElseThrow();
        BackendRuntime backend = backendConfig(key.getBackend());

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
            backend.client().addClient(key.getInboundId(), key.getClientUuid(), key.getClientEmail());

            // 2) берём inbound json (твой inbound содержит streamSettings/settings)
            String inboundJson = backend.client().getInbound(key.getInboundId());
            LinkOptions linkOptions = resolveLinkOptions(backend, key.getInboundId());

            // 3) строим ссылку vless://... на основе inbound
            String vlessLink = linkBuilder.buildLink(
                    inboundJson,
                    linkOptions.host,
                    linkOptions.port,
                    key.getClientUuid(),
                    linkOptions.tag
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
                backend.client().disableClient(key.getInboundId(), key.getClientUuid());
            } catch (Exception ignored) {
            }

            throw new IllegalStateException("Не удалось выпустить ключ: " + safeMsg(e), e);
        }
    }

    private String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private boolean needsLinkRefresh(VpnKey key) {
        String v = key.getKeyValue();
        if (v == null || v.isBlank()) return false;
        if (!v.startsWith("vless://")) return false;
        if (v.contains("encryption=none") || !v.contains("encryption=")) return true;
        BackendRuntime backend = backendConfig(key.getBackend());
        LinkOptions linkOptions = resolveLinkOptions(backend, key.getInboundId());
        String expectedTag = encodeFragment((linkOptions.tag == null || linkOptions.tag.isBlank()) ? "vpn" : linkOptions.tag);
        String currentTag = extractFragment(v);
        if (currentTag == null || !currentTag.equals(expectedTag)) return true;
        return !hasExpectedGroup(v, backend);
    }

    private VpnKey refreshActiveLink(VpnKey key) {
        try {
            BackendRuntime backend = backendConfig(key.getBackend());
            String inboundJson = backend.client().getInbound(key.getInboundId());
            LinkOptions linkOptions = resolveLinkOptions(backend, key.getInboundId());
            String vlessLink = linkBuilder.buildLink(
                    inboundJson,
                    linkOptions.host,
                    linkOptions.port,
                    key.getClientUuid(),
                    linkOptions.tag
            );
            if (!vlessLink.equals(key.getKeyValue())) {
                return tx.execute(status -> activateTx(key.getId(), vlessLink));
            }
        } catch (Exception e) {
            log.warn("Не удалось обновить ссылку ACTIVE keyId={}: {}", key.getId(), safeMsg(e));
        }
        return key;
    }

    private String extractFragment(String url) {
        int idx = url.indexOf('#');
        if (idx < 0 || idx + 1 >= url.length()) return null;
        return url.substring(idx + 1);
    }

    private boolean hasExpectedGroup(String url, BackendRuntime backend) {
        if (backend.linkGroup() == null || backend.linkGroup().isBlank()) return true;
        String expected = "group=" + encodeQuery(backend.linkGroup());
        return url.contains(expected);
    }

    private Long resolveReplacementInbound(VpnKey.Backend backend, Long currentInbound) {
        BackendRuntime runtime = backendConfig(backend);
        if (currentInbound == null) {
            return runtime.inbound();
        }
        if (runtime.xhttpInbound() <= 0 || runtime.xhttpInbound().equals(runtime.inbound())) {
            return runtime.inbound();
        }
        if (currentInbound.equals(runtime.inbound())) {
            return runtime.xhttpInbound();
        }
        if (currentInbound.equals(runtime.xhttpInbound())) {
            return runtime.inbound();
        }
        return runtime.xhttpInbound();
    }

    private LinkOptions resolveLinkOptions(BackendRuntime backend, Long inboundId) {
        if (inboundId != null && inboundId.equals(backend.xhttpInbound())) {
            return new LinkOptions(backend.publicHost(), backend.xhttpPort(), backend.xhttpLinkTag());
        }
        return new LinkOptions(backend.publicHost(), backend.publicPort(), backend.linkTag());
    }

    private static final class LinkOptions {
        final String host;
        final int port;
        final String tag;

        private LinkOptions(String host, int port, String tag) {
            this.host = host;
            this.port = port;
            this.tag = tag;
        }
    }

    private static final class BackendRuntime {
        private final VpnKey.Backend backend;
        private final ThreeXuiClient client;
        private final Long inbound;
        private final Long xhttpInbound;
        private final String publicHost;
        private final int publicPort;
        private final int xhttpPort;
        private final String linkTag;
        private final String xhttpLinkTag;
        private final String linkGroup;

        private BackendRuntime(
                VpnKey.Backend backend,
                ThreeXuiClient client,
                Long inbound,
                Long xhttpInbound,
                String publicHost,
                int publicPort,
                int xhttpPort,
                String linkTag,
                String xhttpLinkTag,
                String linkGroup
        ) {
            this.backend = backend;
            this.client = client;
            this.inbound = inbound;
            this.xhttpInbound = xhttpInbound;
            this.publicHost = publicHost;
            this.publicPort = publicPort;
            this.xhttpPort = xhttpPort;
            this.linkTag = linkTag;
            this.xhttpLinkTag = xhttpLinkTag;
            this.linkGroup = linkGroup;
        }

        private VpnKey.Backend backend() {
            return backend;
        }

        private ThreeXuiClient client() {
            return client;
        }

        private Long inbound() {
            return inbound;
        }

        private Long xhttpInbound() {
            return xhttpInbound;
        }

        private String publicHost() {
            return publicHost;
        }

        private int publicPort() {
            return publicPort;
        }

        private int xhttpPort() {
            return xhttpPort;
        }

        private String linkTag() {
            return linkTag;
        }

        private String xhttpLinkTag() {
            return xhttpLinkTag;
        }

        private String linkGroup() {
            return linkGroup;
        }
    }

    private String encodeQuery(String s) {
        if (s == null) return "";
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private String encodeFragment(String s) {
        return encodeQuery(s);
    }
}
