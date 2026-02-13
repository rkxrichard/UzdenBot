package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import ru.uzden.uzdenbot.bots.MainBot;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.repositories.UserRepository;
import ru.uzden.uzdenbot.utils.BotTextUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentNotificationListener {

    private final MainBot mainBot;
    private final BotMenuService botMenuService;
    private final UserRepository userRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentStatus(PaymentService.PaymentStatusEvent event) {
        if (event == null || event.telegramId() == null) {
            return;
        }
        User user = userRepository.findUserByTelegramId(event.telegramId()).orElse(null);
        if (user == null || user.isDisabled()) {
            return;
        }

        try {
            SendMessage statusMessage = buildStatusMessage(event);
            mainBot.execute(statusMessage);
            mainBot.execute(botMenuService.subscriptionMenu(event.telegramId()));
        } catch (Exception e) {
            log.warn("Failed to send payment notification for paymentId={}: {}", event.paymentId(), e.getMessage());
        }
    }

    private SendMessage buildStatusMessage(PaymentService.PaymentStatusEvent event) {
        String label = event.planLabel() == null || event.planLabel().isBlank()
                ? "подписка"
                : event.planLabel();
        String amount = event.amount() == null ? "" : event.amount().toPlainString() + "₽";
        String text;

        if ("succeeded".equalsIgnoreCase(event.status())) {
            String until = event.subscriptionEndDate() == null
                    ? "-"
                    : BotTextUtils.formatDate(event.subscriptionEndDate());
            text = "✅ Оплата прошла успешно.\n" +
                    "Тариф: " + label + "\n" +
                    (amount.isBlank() ? "" : "Сумма: " + amount + "\n") +
                    "🗓 Действует до: " + until;
        } else {
            text = "❌ Оплата не прошла или была отменена.\n" +
                    "Вы можете попробовать снова.";
        }

        return SendMessage.builder()
                .chatId(event.telegramId().toString())
                .text(text)
                .build();
    }
}
