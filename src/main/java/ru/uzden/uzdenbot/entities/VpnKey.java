package ru.uzden.uzdenbot.entities;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "vpn_keys",
        indexes = {
                @Index(name = "idx_vpn_keys_user_id", columnList = "user_id"),
                @Index(name = "idx_vpn_keys_inbound_id", columnList = "inbound_id"),
                @Index(name = "idx_vpn_keys_client_uuid", columnList = "client_uuid"),
                @Index(name = "idx_vpn_keys_status", columnList = "status"),
                @Index(name = "idx_vpn_keys_updated_at", columnList = "updated_at")
        }
)
@Data
public class VpnKey {

    public enum Status {
        PENDING,   // начали выпуск, но ещё не финализировали
        ACTIVE,    // ключ готов и выдан
        REVOKED,   // ключ отозван
        FAILED     // выпуск/операция сорвалась (можно ретраить)
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* ===== связь с пользователем ===== */

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /* ===== ключ для пользователя ===== */

    /**
     * Готовая ссылка vless://... (или заглушка "PENDING:<uuid>" пока ключ в процессе).
     * В БД поле unique + not null, поэтому заглушка должна быть уникальной.
     */
    @Column(name = "key_value", nullable = false, unique = true, columnDefinition = "text")
    private String keyValue;

    /**
     * Логический отзыв ключа (на стороне бота).
     * Обычно вместе со статусом REVOKED.
     */
    @Column(name = "is_revoked", nullable = false)
    private boolean revoked = false;

    /* ===== связь с 3x-ui/Xray ===== */

    /**
     * ID inbound в 3x-ui (у тебя сейчас 1).
     */
    @Column(name = "inbound_id", nullable = false)
    private Long inboundId = 1L;

    /**
     * UUID клиента (clients[].id) — главный идентификатор клиента в 3x-ui.
     */
    @Column(name = "client_uuid", nullable = false, unique = true)
    private UUID clientUuid;

    /**
     * Email / comment клиента в 3x-ui (удобно делать tg_<telegramId>).
     */
    @Column(name = "client_email", nullable = false)
    private String clientEmail;

    /* ===== устойчивость / восстановление ===== */

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.PENDING;

    /**
     * Время последнего изменения записи (для recovery-job).
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Последняя ошибка (если выпуск/операция упала).
     */
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    /* ===== служебные ===== */

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;

        // Если запись создаётся как PENDING — подставим уникальную заглушку, чтобы not null/unique не мешали.
        if ((keyValue == null || keyValue.isBlank()) && clientUuid != null) {
            keyValue = "PENDING:" + clientUuid;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /* ===== удобные хелперы (необязательно) ===== */

    public boolean isActive() {
        return status == Status.ACTIVE && !revoked;
    }

    public void markFailed(String error) {
        this.status = Status.FAILED;
        this.lastError = error;
    }

    public void markActive(String keyValue) {
        this.status = Status.ACTIVE;
        this.revoked = false;
        this.lastError = null;
        this.keyValue = keyValue;
    }

    public void markRevoked() {
        this.status = Status.REVOKED;
        this.revoked = true;
    }
}
