package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.repositories.UserRepository;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReferralService {

    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;
    private final VpnKeyService vpnKeyService;

    @Value("${app.referral.referrer-days:7}")
    private int referrerDays;

    @Value("${app.referral.referred-days:3}")
    private int referredDays;

    public ReferralResult applyReferral(User newUser, String rawCode) {
        String code = normalizeCode(rawCode);
        if (code == null) {
            return ReferralResult.noCode();
        }
        if (newUser == null || newUser.getId() == null) {
            return ReferralResult.invalidCode();
        }

        Optional<User> refOpt = userRepository.findByReferralCodeIgnoreCase(code);
        if (refOpt.isEmpty()) {
            return ReferralResult.invalidCode();
        }

        User referrer = refOpt.get();
        if (referrer.getId() == null) {
            return ReferralResult.invalidCode();
        }
        if (referrer.getId().equals(newUser.getId())) {
            return ReferralResult.selfRef();
        }

        ReferralResult result = applyReferralTx(newUser.getId(), referrer.getId());
        if (result.status == ReferralStatus.APPLIED) {
            // Привязываем новые дни к ключам, если нужно
            vpnKeyService.ensureKeyForActiveSubscription(newUser);
            vpnKeyService.ensureKeyForActiveSubscription(referrer);
        }
        return result;
    }

    @Transactional
    protected ReferralResult applyReferralTx(Long newUserId, Long referrerId) {
        if (newUserId == null || referrerId == null) {
            return ReferralResult.invalidCode();
        }

        Long firstId = Math.min(newUserId, referrerId);
        Long secondId = Math.max(newUserId, referrerId);

        User first = userRepository.lockUser(firstId);
        User second = userRepository.lockUser(secondId);

        User newUser = first.getId().equals(newUserId) ? first : second;
        User referrer = first.getId().equals(referrerId) ? first : second;

        if (referrer.getId().equals(newUser.getId())) {
            return ReferralResult.selfRef();
        }
        if (newUser.getReferredBy() != null) {
            return ReferralResult.alreadyReferred();
        }

        newUser.setReferredBy(referrer.getId());
        newUser.setReferredAt(LocalDateTime.now());
        userRepository.save(newUser);

        subscriptionService.extendSubscription(newUser, referredDays);
        subscriptionService.extendSubscription(referrer, referrerDays);

        return ReferralResult.applied(referrer.getTelegramId(), referredDays, referrerDays);
    }

    private String normalizeCode(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isBlank()) return null;
        if (t.startsWith("ref_")) t = t.substring(4);
        if (t.startsWith("ref")) t = t.substring(3);
        t = t.trim();
        return t.isBlank() ? null : t;
    }

    public enum ReferralStatus {
        NO_CODE,
        APPLIED,
        INVALID_CODE,
        SELF_REF,
        ALREADY_REFERRED
    }

    public static final class ReferralResult {
        public final ReferralStatus status;
        public final Long referrerTelegramId;
        public final int referredDays;
        public final int referrerDays;

        private ReferralResult(ReferralStatus status, Long referrerTelegramId, int referredDays, int referrerDays) {
            this.status = status;
            this.referrerTelegramId = referrerTelegramId;
            this.referredDays = referredDays;
            this.referrerDays = referrerDays;
        }

        public static ReferralResult noCode() {
            return new ReferralResult(ReferralStatus.NO_CODE, null, 0, 0);
        }

        public static ReferralResult invalidCode() {
            return new ReferralResult(ReferralStatus.INVALID_CODE, null, 0, 0);
        }

        public static ReferralResult selfRef() {
            return new ReferralResult(ReferralStatus.SELF_REF, null, 0, 0);
        }

        public static ReferralResult alreadyReferred() {
            return new ReferralResult(ReferralStatus.ALREADY_REFERRED, null, 0, 0);
        }

        public static ReferralResult applied(Long referrerTelegramId, int referredDays, int referrerDays) {
            return new ReferralResult(ReferralStatus.APPLIED, referrerTelegramId, referredDays, referrerDays);
        }
    }
}
