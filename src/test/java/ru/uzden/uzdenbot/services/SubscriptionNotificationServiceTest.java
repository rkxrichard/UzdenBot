package ru.uzden.uzdenbot.services;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import ru.uzden.uzdenbot.bots.MainBot;
import ru.uzden.uzdenbot.entities.Subscription;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.repositories.SubscriptionRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubscriptionNotificationServiceTest {

    @Test
    void expiringNotificationContainsRenewButtonForKey() throws Exception {
        SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        MainBot mainBot = mock(MainBot.class);

        SubscriptionNotificationService service = new SubscriptionNotificationService(
                subscriptionRepository,
                subscriptionService,
                mainBot
        );

        User user = new User();
        user.setId(1L);
        user.setTelegramId(12345L);

        VpnKey key = new VpnKey();
        key.setId(99L);

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setVpnKey(key);
        sub.setEndDate(LocalDateTime.now().plusDays(1));

        when(subscriptionRepository.findByEndDateAfter(any())).thenReturn(List.of(sub));
        when(subscriptionService.getDaysLeft(sub)).thenReturn(1L);
        when(mainBot.execute(any(SendMessage.class))).thenReturn(new org.telegram.telegrambots.meta.api.objects.Message());

        service.notifyExpiringSubscriptions();

        var captor = org.mockito.ArgumentCaptor.forClass(SendMessage.class);
        verify(mainBot).execute(captor.capture());

        SendMessage sent = captor.getValue();
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sent.getReplyMarkup();
        InlineKeyboardButton button = markup.getKeyboard().get(0).get(0);

        assertEquals("🔁 Продлить подписку", button.getText());
        assertEquals("KEY_RENEW:99", button.getCallbackData());
    }
}
