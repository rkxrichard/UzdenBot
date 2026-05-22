package ru.uzden.uzdenbot.services;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import ru.uzden.uzdenbot.bots.MainBot;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.repositories.PaymentRepository;
import ru.uzden.uzdenbot.repositories.UserRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentNotificationListenerTest {

    @Test
    void renewalRefreshesExistingKeyWithoutSendingSubscriptionAgain() throws Exception {
        MainBot mainBot = mock(MainBot.class);
        BotMenuService botMenuService = mock(BotMenuService.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        VpnKeyService vpnKeyService = mock(VpnKeyService.class);

        PaymentNotificationListener listener = new PaymentNotificationListener(
                mainBot,
                botMenuService,
                paymentRepository,
                userRepository,
                vpnKeyService
        );

        User user = new User();
        user.setId(10L);
        user.setTelegramId(777L);
        user.setDisabled(false);

        VpnKey key = new VpnKey();
        key.setId(55L);
        key.setKeyValue("https://sub.example/key");

        when(userRepository.findUserByTelegramId(777L)).thenReturn(Optional.of(user));
        when(vpnKeyService.getKeyForUser(user, 55L)).thenReturn(key);
        when(botMenuService.myKeysMenu(777L, user))
                .thenReturn(SendMessage.builder().chatId("777").text("menu").build());

        listener.onPaymentStatus(new PaymentService.PaymentStatusEvent(
                1L,
                10L,
                777L,
                "succeeded",
                "1 month",
                BigDecimal.valueOf(299),
                LocalDateTime.now().plusDays(30),
                55L,
                false
        ));

        verify(vpnKeyService).getKeyForUser(user, 55L);
        verify(botMenuService, never()).keyDeliveryMessage(any(), any(), anyBoolean(), anyBoolean());
        verify(mainBot, times(2)).execute(any(SendMessage.class));
    }

    @Test
    void newKeyStillGetsDeliveredToUser() throws Exception {
        MainBot mainBot = mock(MainBot.class);
        BotMenuService botMenuService = mock(BotMenuService.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        VpnKeyService vpnKeyService = mock(VpnKeyService.class);

        PaymentNotificationListener listener = new PaymentNotificationListener(
                mainBot,
                botMenuService,
                paymentRepository,
                userRepository,
                vpnKeyService
        );

        User user = new User();
        user.setId(10L);
        user.setTelegramId(777L);
        user.setDisabled(false);

        VpnKey key = new VpnKey();
        key.setId(55L);
        key.setKeyValue("https://sub.example/key");

        when(userRepository.findUserByTelegramId(777L)).thenReturn(Optional.of(user));
        when(vpnKeyService.getKeyForUser(user, 55L)).thenReturn(key);
        when(paymentRepository.countByUserAndProcessedAtIsNotNullAndStatusIgnoreCase(user, "succeeded")).thenReturn(1L);
        when(botMenuService.keyDeliveryMessage(777L, "https://sub.example/key", true, false))
                .thenReturn(SendMessage.builder().chatId("777").text("link").build());
        when(botMenuService.myKeysMenu(777L, user))
                .thenReturn(SendMessage.builder().chatId("777").text("menu").build());

        listener.onPaymentStatus(new PaymentService.PaymentStatusEvent(
                1L,
                10L,
                777L,
                "succeeded",
                "1 month",
                BigDecimal.valueOf(299),
                LocalDateTime.now().plusDays(30),
                55L,
                true
        ));

        verify(vpnKeyService).getKeyForUser(user, 55L);
        verify(botMenuService).keyDeliveryMessage(777L, "https://sub.example/key", true, false);
        verify(mainBot, times(3)).execute(any(SendMessage.class));
    }
}
