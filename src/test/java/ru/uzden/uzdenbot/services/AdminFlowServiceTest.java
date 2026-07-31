package ru.uzden.uzdenbot.services;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.CopyMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import ru.uzden.uzdenbot.entities.Subscription;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.entities.User;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class AdminFlowServiceTest {

    @Test
    void broadcastPhotoCreatesCopyMessagesForUsers() {
        AdminStateService adminStateService = mock(AdminStateService.class);
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        UserService userService = mock(UserService.class);
        VpnKeyService vpnKeyService = mock(VpnKeyService.class);
        ReferralService referralService = mock(ReferralService.class);

        AdminFlowService service = new AdminFlowService(
                adminStateService,
                subscriptionService,
                userService,
                vpnKeyService,
                referralService
        );

        User u1 = new User();
        u1.setTelegramId(101L);
        User u2 = new User();
        u2.setTelegramId(202L);
        when(userService.listAll()).thenReturn(List.of(u1, u2));

        Message adminMessage = new Message();
        adminMessage.setMessageId(77);
        adminMessage.setPhoto(List.of(new PhotoSize()));
        Chat chat = new Chat();
        chat.setId(1L);
        adminMessage.setChat(chat);

        List<BotApiMethod<?>> result = service.handleAdminMessage(1L, adminMessage, AdminAction.BROADCAST);

        assertEquals(3, result.size());
        assertInstanceOf(SendMessage.class, result.get(0));
        assertInstanceOf(CopyMessage.class, result.get(1));
        assertInstanceOf(CopyMessage.class, result.get(2));

        CopyMessage firstCopy = (CopyMessage) result.get(1);
        assertEquals("101", firstCopy.getChatId());
        assertEquals("1", firstCopy.getFromChatId());
        assertEquals(77, firstCopy.getMessageId());

        verify(adminStateService).clear(1L);
    }

    @Test
    void createRuEuKeyFindsUserByTelegramId() {
        AdminStateService adminStateService = mock(AdminStateService.class);
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        UserService userService = mock(UserService.class);
        VpnKeyService vpnKeyService = mock(VpnKeyService.class);
        ReferralService referralService = mock(ReferralService.class);

        AdminFlowService service = new AdminFlowService(
                adminStateService,
                subscriptionService,
                userService,
                vpnKeyService,
                referralService
        );

        User user = new User();
        user.setId(55L);
        user.setTelegramId(123456L);
        user.setUsername("bridge_user");
        when(userService.findByTelegramId(123456L)).thenReturn(java.util.Optional.of(user));
        when(subscriptionService.getActiveSubscription(user)).thenReturn(java.util.Optional.empty());

        VpnKey key = new VpnKey();
        key.setId(77L);
        key.setBackend(VpnKey.Backend.RU_EU);
        when(vpnKeyService.issueRuEuKey(user)).thenReturn(key);

        Message message = new Message();
        message.setText("123456");

        List<BotApiMethod<?>> result = service.handleAdminMessage(1L, message, AdminAction.CREATE_RU_EU_KEY);

        assertEquals(1, result.size());
        assertInstanceOf(SendMessage.class, result.get(0));
        SendMessage reply = (SendMessage) result.get(0);
        assertTrue(reply.getText().contains("RU+EU ключ создан"));
        assertTrue(reply.getText().contains("@bridge_user"));
        verify(vpnKeyService).issueRuEuKey(user);
        verify(adminStateService).clear(1L);
    }

    @Test
    void createAdminKeyBuildsSubscriptionLinkForAdmin() {
        AdminStateService adminStateService = mock(AdminStateService.class);
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        UserService userService = mock(UserService.class);
        VpnKeyService vpnKeyService = mock(VpnKeyService.class);
        ReferralService referralService = mock(ReferralService.class);

        AdminFlowService service = new AdminFlowService(
                adminStateService, subscriptionService, userService, vpnKeyService, referralService);

        User admin = new User();
        admin.setId(1L);
        admin.setTelegramId(500L);
        when(userService.findByTelegramId(500L)).thenReturn(java.util.Optional.of(admin));

        VpnKey key = new VpnKey();
        key.setId(77L);
        key.setName("Клиент Иван");
        key.setKeyValue("https://sub.example/default/abc");
        when(vpnKeyService.issueAdminKey(admin, "Клиент Иван")).thenReturn(key);

        Subscription sub = new Subscription();
        sub.setEndDate(LocalDateTime.now().plusDays(30));
        when(subscriptionService.extendSubscriptionForKey(admin, key, 30)).thenReturn(sub);

        Message message = new Message();
        message.setText("30 Клиент Иван");

        List<BotApiMethod<?>> result = service.handleAdminMessage(500L, message, AdminAction.CREATE_ADMIN_KEY);

        assertEquals(1, result.size());
        assertInstanceOf(SendMessage.class, result.get(0));
        SendMessage reply = (SendMessage) result.get(0);
        assertTrue(reply.getText().contains("https://sub.example/default/abc"));
        assertTrue(reply.getText().contains("77"));
        assertEquals("HTML", reply.getParseMode());
        verify(vpnKeyService).issueAdminKey(admin, "Клиент Иван");
        verify(subscriptionService).extendSubscriptionForKey(admin, key, 30);
        verify(adminStateService).clear(500L);
    }

    @Test
    void renewAdminKeyByIdExtendsSubscription() {
        AdminStateService adminStateService = mock(AdminStateService.class);
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        UserService userService = mock(UserService.class);
        VpnKeyService vpnKeyService = mock(VpnKeyService.class);
        ReferralService referralService = mock(ReferralService.class);

        AdminFlowService service = new AdminFlowService(
                adminStateService, subscriptionService, userService, vpnKeyService, referralService);

        VpnKey key = new VpnKey();
        key.setId(42L);
        key.setName("Клиент Иван");
        key.setKeyValue("https://sub.example/default/abc");
        when(vpnKeyService.renewAdminKey(42L, 30)).thenReturn(key);

        Subscription sub = new Subscription();
        sub.setEndDate(LocalDateTime.now().plusDays(30));
        when(subscriptionService.getActiveSubscription(key)).thenReturn(java.util.Optional.of(sub));

        Message message = new Message();
        message.setText("42 30");

        List<BotApiMethod<?>> result = service.handleAdminMessage(500L, message, AdminAction.RENEW_ADMIN_KEY);

        assertEquals(2, result.size());
        assertInstanceOf(SendMessage.class, result.get(0));
        SendMessage reply = (SendMessage) result.get(0);
        assertTrue(reply.getText().contains("продлён"));
        assertTrue(reply.getText().contains("42"));
        verify(vpnKeyService).renewAdminKey(42L, 30);
        verify(adminStateService).clear(500L);
    }
}
