package ru.uzden.uzdenbot.services;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.CopyMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import ru.uzden.uzdenbot.entities.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
}
