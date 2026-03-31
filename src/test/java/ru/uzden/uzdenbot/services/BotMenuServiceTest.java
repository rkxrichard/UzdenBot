package ru.uzden.uzdenbot.services;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import ru.uzden.uzdenbot.config.SubscriptionPlansProperties;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.repositories.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class BotMenuServiceTest {

    @Test
    void mainMenuWithKeysShowsDirectKeysEntry() {
        UserRepository userRepository = mock(UserRepository.class);
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        PaymentService paymentService = mock(PaymentService.class);
        VpnKeyService vpnKeyService = mock(VpnKeyService.class);

        SubscriptionPlansProperties plans = new SubscriptionPlansProperties();
        BotMenuService service = new BotMenuService(
                userRepository,
                subscriptionService,
                plans,
                paymentService,
                vpnKeyService
        );

        User user = new User();
        user.setId(1L);

        VpnKey key = new VpnKey();
        key.setId(10L);
        key.setCreatedAt(Instant.now());

        when(subscriptionService.getLastSubscription(user)).thenReturn(Optional.empty());
        when(vpnKeyService.listUserKeys(user)).thenReturn(List.of(key));

        SendMessage message = service.mainMenu(1L, false, user);
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) message.getReplyMarkup();
        List<String> callbacks = callbackData(markup);

        assertTrue(callbacks.contains("MENU_KEYS"));
        assertFalse(callbacks.contains("MENU_SUBSCRIPTION"));
        assertTrue(message.getText().contains("Откройте «Мои ключи»"));
    }

    @Test
    void myKeysMenuShowsActionsWithoutKeySelectionScreen() {
        UserRepository userRepository = mock(UserRepository.class);
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        PaymentService paymentService = mock(PaymentService.class);
        VpnKeyService vpnKeyService = mock(VpnKeyService.class);

        SubscriptionPlansProperties plans = new SubscriptionPlansProperties();
        BotMenuService service = new BotMenuService(
                userRepository,
                subscriptionService,
                plans,
                paymentService,
                vpnKeyService
        );

        User user = new User();
        user.setId(1L);

        VpnKey key = new VpnKey();
        key.setId(10L);
        key.setCreatedAt(Instant.now());
        key.setStatus(VpnKey.Status.ACTIVE);

        when(vpnKeyService.listUserKeys(user)).thenReturn(List.of(key));
        when(vpnKeyService.getMaxKeysPerUser()).thenReturn(3);
        when(vpnKeyService.requiresActiveSubscription(key)).thenReturn(true);
        when(subscriptionService.hasActiveSubscriptionForKey(key)).thenReturn(true);
        when(subscriptionService.getActiveSubscription(key)).thenReturn(Optional.empty());
        when(subscriptionService.getLastSubscription(key)).thenReturn(Optional.empty());

        SendMessage message = service.myKeysMenu(1L, user);
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) message.getReplyMarkup();
        List<String> callbacks = callbackData(markup);

        assertTrue(callbacks.contains("KEY_GET:10"));
        assertTrue(callbacks.contains("KEY_RENEW:10"));
        assertTrue(callbacks.contains("KEY_REPLACE:10"));
        assertFalse(callbacks.stream().anyMatch(data -> data.startsWith("KEY_SELECT:")));
        assertTrue(message.getText().contains("сразу под нужным ключом"));
    }

    @Test
    void myKeysMenuHidesRenewForRuEuTestKey() {
        UserRepository userRepository = mock(UserRepository.class);
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        PaymentService paymentService = mock(PaymentService.class);
        VpnKeyService vpnKeyService = mock(VpnKeyService.class);

        SubscriptionPlansProperties plans = new SubscriptionPlansProperties();
        BotMenuService service = new BotMenuService(
                userRepository,
                subscriptionService,
                plans,
                paymentService,
                vpnKeyService
        );

        User user = new User();
        user.setId(1L);

        VpnKey key = new VpnKey();
        key.setId(15L);
        key.setBackend(VpnKey.Backend.RU_EU);
        key.setCreatedAt(Instant.now());
        key.setStatus(VpnKey.Status.ACTIVE);

        when(vpnKeyService.listUserKeys(user)).thenReturn(List.of(key));
        when(vpnKeyService.getMaxKeysPerUser()).thenReturn(3);
        when(vpnKeyService.requiresActiveSubscription(key)).thenReturn(false);
        when(subscriptionService.hasActiveSubscriptionForKey(key)).thenReturn(false);

        SendMessage message = service.myKeysMenu(1L, user);
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) message.getReplyMarkup();
        List<String> callbacks = callbackData(markup);

        assertTrue(callbacks.contains("KEY_GET:15"));
        assertTrue(callbacks.contains("KEY_REPLACE:15"));
        assertTrue(callbacks.contains("KEY_DELETE:15"));
        assertFalse(callbacks.contains("KEY_RENEW:15"));
        assertTrue(message.getText().contains("тестовый доступ"));
    }

    private List<String> callbackData(InlineKeyboardMarkup markup) {
        return markup.getKeyboard().stream()
                .flatMap(List::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .filter(data -> data != null && !data.isBlank())
                .toList();
    }
}
