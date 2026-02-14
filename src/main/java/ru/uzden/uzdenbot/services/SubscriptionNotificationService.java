package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import ru.uzden.uzdenbot.bots.MainBot;
import ru.uzden.uzdenbot.entities.Subscription;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.repositories.SubscriptionRepository;
import ru.uzden.uzdenbot.utils.BotTextUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionNotificationService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;
    private final MainBot mainBot;

    @Scheduled(fixedDelayString = "${app.subscriptions.notify-delay-ms:3600000}")
    @Transactional
    public void notifyExpiringSubscriptions() {
        List<Subscription> active = subscriptionRepository.findByEndDateAfter(LocalDateTime.now());
        for (Subscription sub : active) {
            User user = sub.getUser();
            if (user == null || user.isDisabled() || user.getTelegramId() == null) {
                continue;
            }
            long daysLeft = subscriptionService.getDaysLeft(sub);
            if (daysLeft == 2 && sub.getNotifiedTwoDaysAt() == null) {
                if (sendNotification(user, sub, "⏰ Подписка истекает через 2 дня.")) {
                    sub.setNotifiedTwoDaysAt(Instant.now());
                }
            } else if (daysLeft == 1 && sub.getNotifiedOneDayAt() == null) {
                if (sendNotification(user, sub, "⏰ Подписка истекает завтра.")) {
                    sub.setNotifiedOneDayAt(Instant.now());
                }
            }
        }
    }

    private boolean sendNotification(User user, Subscription sub, String title) {
        String until = BotTextUtils.formatDate(sub.getEndDate());
        String msg = title + "\n" +
                "🗓 Действует до: " + until + "\n" +
                "Продлите в разделе «Мои ключи».";
        try {
            SendMessage sm = SendMessage.builder()
                    .chatId(user.getTelegramId().toString())
                    .text(msg)
                    .build();
            mainBot.execute(sm);
            return true;
        } catch (Exception e) {
            log.warn("Failed to notify user {}: {}", user.getId(), e.getMessage());
            return false;
        }
    }
}
