package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.uzden.uzdenbot.entities.Payment;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.repositories.PaymentRepository;
import ru.uzden.uzdenbot.yookassa.YooKassaClient;
import ru.uzden.uzdenbot.yookassa.YooKassaConfirmation;
import ru.uzden.uzdenbot.yookassa.YooKassaCreatePaymentRequest;
import ru.uzden.uzdenbot.yookassa.YooKassaPayment;
import ru.uzden.uzdenbot.yookassa.YooKassaPaymentAmount;
import ru.uzden.uzdenbot.yookassa.YooKassaProperties;
import ru.uzden.uzdenbot.yookassa.YooKassaWebhook;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String PROVIDER = "YOOKASSA";

    private final PaymentRepository paymentRepository;
    private final SubscriptionService subscriptionService;
    private final YooKassaClient yooKassaClient;
    private final YooKassaProperties properties;

    @Transactional
    public PaymentInitResult createPayment(User user, int days, int price, String label) {
        BigDecimal amount = BigDecimal.valueOf(price).setScale(2);
        Payment payment = new Payment(user, amount, "pending");
        payment.setProvider(PROVIDER);
        payment.setPlanDays(days);
        payment.setPlanLabel(label);
        payment.setDescription("Подписка " + label);

        String idempotencyKey = UUID.randomUUID().toString();
        payment.setIdempotencyKey(idempotencyKey);
        payment = paymentRepository.save(payment);

        try {
            YooKassaCreatePaymentRequest request = buildRequest(payment, user);
            YooKassaPayment response = yooKassaClient.createPayment(request, idempotencyKey);
            if (response == null) {
                throw new IllegalStateException("Пустой ответ от YooKassa");
            }
            payment.setProviderPaymentId(response.getId());
            payment.setStatus(response.getStatus());
            if (response.getConfirmation() != null) {
                payment.setConfirmationUrl(response.getConfirmation().getConfirmationUrl());
            }
            payment = paymentRepository.save(payment);
            return new PaymentInitResult(payment, payment.getConfirmationUrl());
        } catch (Exception e) {
            payment.setStatus("failed");
            paymentRepository.save(payment);
            throw e;
        }
    }

    @Transactional
    public void handleWebhook(YooKassaWebhook webhook) {
        if (webhook == null || webhook.getObject() == null) return;
        YooKassaPayment payload = webhook.getObject();
        if (payload.getId() == null || payload.getId().isBlank()) return;

        Optional<Payment> paymentOpt = paymentRepository.findByProviderPaymentId(payload.getId());
        if (paymentOpt.isEmpty()) {
            log.warn("Webhook for unknown paymentId={}", payload.getId());
            return;
        }

        Payment payment = paymentOpt.get();
        YooKassaPayment verified = yooKassaClient.getPayment(payload.getId());
        if (verified == null) {
            log.warn("Verification failed for paymentId={}", payload.getId());
            return;
        }

        payment.setStatus(verified.getStatus());

        if ("succeeded".equalsIgnoreCase(verified.getStatus())) {
            if (payment.getProcessedAt() != null) {
                paymentRepository.save(payment);
                return;
            }

            if (!amountMatches(payment, verified)) {
                log.warn("Amount mismatch for paymentId={}", payload.getId());
                paymentRepository.save(payment);
                return;
            }

            int days = resolvePlanDays(payment, verified);
            if (days <= 0) {
                log.warn("Plan days missing for paymentId={}", payload.getId());
                paymentRepository.save(payment);
                return;
            }

            subscriptionService.extendSubscription(payment.getUser(), days);
            payment.setPaidAt(Instant.now());
            payment.setProcessedAt(Instant.now());
        }

        paymentRepository.save(payment);
    }

    private YooKassaCreatePaymentRequest buildRequest(Payment payment, User user) {
        YooKassaPaymentAmount amount = new YooKassaPaymentAmount();
        amount.setValue(formatAmount(payment.getAmount()));
        amount.setCurrency(resolveCurrency(payment.getCurrency()));

        YooKassaConfirmation confirmation = new YooKassaConfirmation();
        confirmation.setType("redirect");
        confirmation.setReturnUrl(resolveReturnUrl());

        YooKassaCreatePaymentRequest req = new YooKassaCreatePaymentRequest();
        req.setAmount(amount);
        req.setCapture(true);
        req.setConfirmation(confirmation);
        req.setDescription(payment.getDescription());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("user_id", user.getId());
        metadata.put("telegram_id", user.getTelegramId());
        metadata.put("plan_days", payment.getPlanDays());
        metadata.put("plan_label", payment.getPlanLabel());
        req.setMetadata(metadata);

        return req;
    }

    private boolean amountMatches(Payment payment, YooKassaPayment verified) {
        if (verified.getAmount() == null || verified.getAmount().getValue() == null) return false;
        if (verified.getAmount().getCurrency() != null
                && payment.getCurrency() != null
                && !verified.getAmount().getCurrency().equalsIgnoreCase(payment.getCurrency())) {
            return false;
        }
        try {
            BigDecimal verifiedAmount = new BigDecimal(verified.getAmount().getValue());
            return payment.getAmount().compareTo(verifiedAmount) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private int resolvePlanDays(Payment payment, YooKassaPayment verified) {
        if (payment.getPlanDays() != null) {
            return payment.getPlanDays();
        }
        if (verified.getMetadata() == null) return 0;
        Object fromMeta = verified.getMetadata().get("plan_days");
        if (fromMeta instanceof Number n) {
            return n.intValue();
        }
        if (fromMeta instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String resolveReturnUrl() {
        String url = properties.getReturnUrl();
        if (url == null || url.isBlank()) {
            return "https://t.me";
        }
        return url;
    }

    private String resolveCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return "RUB";
        }
        return currency;
    }

    public record PaymentInitResult(Payment payment, String confirmationUrl) {
    }
}
