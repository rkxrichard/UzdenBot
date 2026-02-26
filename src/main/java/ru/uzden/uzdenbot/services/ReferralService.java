package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.repositories.UserRepository;
import ru.uzden.uzdenbot.repositories.VpnKeyRepository;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReferralService {

    private final UserRepository userRepository;
    private final VpnKeyRepository vpnKeyRepository;
    private final SubscriptionService subscriptionService;
    private final VpnKeyService vpnKeyService;

    @Value("${app.referral.referrer-days:7}")
    private int referrerDays;

    @Value("${app.referral.referred-days:3}")
    private int referredDays;

    @Transactional
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

        Long newUserId = newUser.getId();
        Long referrerId = referrer.getId();

        Long firstId = Math.min(newUserId, referrerId);
        Long secondId = Math.max(newUserId, referrerId);

        User first = userRepository.lockUser(firstId);
        User second = userRepository.lockUser(secondId);

        User lockedNewUser = first.getId().equals(newUserId) ? first : second;
        User lockedReferrer = first.getId().equals(referrerId) ? first : second;

        if (lockedReferrer.getId().equals(lockedNewUser.getId())) {
            return ReferralResult.selfRef();
        }
        if (lockedNewUser.getReferredBy() != null) {
            return ReferralResult.alreadyReferred();
        }

        lockedNewUser.setReferredBy(lockedReferrer.getId());
        lockedNewUser.setReferredAt(LocalDateTime.now());
        userRepository.save(lockedNewUser);

        extendForUser(lockedNewUser, referredDays);
        extendForUser(lockedReferrer, referrerDays);

        // Привязываем новые дни к ключам, если нужно
        vpnKeyService.ensureKeyForActiveSubscription(lockedNewUser);
        vpnKeyService.ensureKeyForActiveSubscription(lockedReferrer);

        return ReferralResult.applied(lockedReferrer.getTelegramId(), referredDays, referrerDays);
    }

    private void extendForUser(User user, int days) {
        if (user == null || user.getId() == null) return;
        Optional<VpnKey> activeKey = vpnKeyRepository.findActiveKey(user.getId());
        if (activeKey.isPresent()) {
            subscriptionService.extendSubscriptionForKey(user, activeKey.get(), days);
        } else {
            subscriptionService.extendSubscription(user, days);
        }
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
