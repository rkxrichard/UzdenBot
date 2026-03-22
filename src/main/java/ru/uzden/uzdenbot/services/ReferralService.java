package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.uzden.uzdenbot.entities.ReferralLink;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.repositories.ReferralLinkRepository;
import ru.uzden.uzdenbot.repositories.UserRepository;
import ru.uzden.uzdenbot.repositories.VpnKeyRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class ReferralService {

    private static final char[] TRACKED_CODE_ALPHABET = "abcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    private final UserRepository userRepository;
    private final ReferralLinkRepository referralLinkRepository;
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

        ReferralTarget target = resolveReferralTarget(code);
        if (target == null) {
            return ReferralResult.invalidCode();
        }

        User referrer = target.referrer();
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
        lockedNewUser.setReferredLinkId(target.referralLinkId());
        userRepository.save(lockedNewUser);

        extendForUser(lockedNewUser, referredDays);
        extendForUser(lockedReferrer, referrerDays);

        // Привязываем новые дни к ключам, если нужно
        vpnKeyService.ensureKeyForActiveSubscription(lockedNewUser);
        vpnKeyService.ensureKeyForActiveSubscription(lockedReferrer);

        return ReferralResult.applied(lockedReferrer.getTelegramId(), referredDays, referrerDays);
    }

    @Transactional
    public CreatedReferralLink createTrackedLink(User referrer) {
        if (referrer == null || referrer.getId() == null) {
            throw new IllegalArgumentException("Referrer user is required");
        }

        ReferralLink link = new ReferralLink();
        link.setReferrerUser(referrer);
        link.setCode(generateUniqueTrackedCode());
        link = referralLinkRepository.save(link);

        return new CreatedReferralLink(link.getId(), referrer.getId(), link.getCode(), link.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public ReferralLinksStats getTrackedLinksStats(User referrer) {
        if (referrer == null || referrer.getId() == null) {
            return new ReferralLinksStats(0, 0, List.of());
        }

        List<ReferralLink> links = referralLinkRepository.findByReferrerUserIdOrderByCreatedAtDesc(referrer.getId());
        List<TrackedReferralLinkStat> stats = new ArrayList<>();
        long trackedInvited = 0;
        for (ReferralLink link : links) {
            long invited = userRepository.countByReferredLinkId(link.getId());
            trackedInvited += invited;
            stats.add(new TrackedReferralLinkStat(
                    link.getId(),
                    referrer.getId(),
                    link.getCode(),
                    link.getCreatedAt(),
                    invited
            ));
        }

        long totalInvited = userRepository.countByReferredBy(referrer.getId());
        long regularInvited = Math.max(0, totalInvited - trackedInvited);
        return new ReferralLinksStats(totalInvited, regularInvited, stats);
    }

    @Transactional(readOnly = true)
    public Optional<TrackedReferralLinkStat> findTrackedLinkStats(String rawCode) {
        String code = normalizeCode(rawCode);
        if (code == null) {
            return Optional.empty();
        }

        return referralLinkRepository.findByCodeIgnoreCase(code)
                .map(link -> new TrackedReferralLinkStat(
                        link.getId(),
                        link.getReferrerUser() == null ? null : link.getReferrerUser().getId(),
                        link.getCode(),
                        link.getCreatedAt(),
                        userRepository.countByReferredLinkId(link.getId())
                ));
    }

    public String buildReferralUrl(String botUsername, String code) {
        String bot = botUsername == null ? "" : botUsername.trim();
        if (bot.startsWith("@")) {
            bot = bot.substring(1);
        }
        if (bot.isBlank()) {
            return "ref_" + code;
        }
        return "https://t.me/" + bot + "?start=ref_" + code;
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

    private ReferralTarget resolveReferralTarget(String code) {
        Optional<ReferralLink> linkOpt = referralLinkRepository.findByCodeIgnoreCase(code);
        if (linkOpt.isPresent()) {
            ReferralLink link = linkOpt.get();
            User referrer = link.getReferrerUser();
            if (referrer == null || referrer.getId() == null) {
                return null;
            }
            return new ReferralTarget(referrer, link.getId());
        }

        return userRepository.findByReferralCodeIgnoreCase(code)
                .map(user -> new ReferralTarget(user, null))
                .orElse(null);
    }

    private String normalizeCode(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isBlank()) return null;
        int startIdx = t.indexOf("start=");
        if (startIdx >= 0) {
            t = t.substring(startIdx + 6);
        }
        int ampIdx = t.indexOf('&');
        if (ampIdx >= 0) {
            t = t.substring(0, ampIdx);
        }
        if (t.startsWith("ref_")) t = t.substring(4);
        if (t.startsWith("ref")) t = t.substring(3);
        t = t.trim();
        return t.isBlank() ? null : t;
    }

    private String generateUniqueTrackedCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = "r" + randomTrackedCode(9);
            if (!referralLinkRepository.existsByCodeIgnoreCase(code)) {
                return code;
            }
        }
        return "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    private String randomTrackedCode(int length) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(TRACKED_CODE_ALPHABET[random.nextInt(TRACKED_CODE_ALPHABET.length)]);
        }
        return sb.toString();
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

    public record CreatedReferralLink(
            Long linkId,
            Long referrerUserId,
            String code,
            LocalDateTime createdAt
    ) {
    }

    public record TrackedReferralLinkStat(
            Long linkId,
            Long referrerUserId,
            String code,
            LocalDateTime createdAt,
            long invitedCount
    ) {
    }

    public record ReferralLinksStats(
            long totalInvitedCount,
            long regularInvitedCount,
            List<TrackedReferralLinkStat> links
    ) {
    }

    private record ReferralTarget(User referrer, Long referralLinkId) {
    }
}
