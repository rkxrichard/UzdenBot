package ru.uzden.uzdenbot.services;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.uzden.uzdenbot.config.SubscriptionPlansProperties;
import ru.uzden.uzdenbot.entities.User;

import java.util.List;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BotUpdateHandlerTest {

    @Test
    void startMessageShowsMainMenu() throws Exception {
        BotMenuService botMenuService = mock(BotMenuService.class);
        AdminService adminService = mock(AdminService.class);
        AdminStateService adminStateService = mock(AdminStateService.class);
        AdminFlowService adminFlowService = mock(AdminFlowService.class);
        UserService userService = mock(UserService.class);
        VpnKeyService vpnKeyService = mock(VpnKeyService.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        PaymentService paymentService = mock(PaymentService.class);
        SubscriptionPlansProperties plans = new SubscriptionPlansProperties();
        ReferralService referralService = mock(ReferralService.class);

        BotUpdateHandler handler = new BotUpdateHandler(
                botMenuService,
                adminService,
                adminStateService,
                adminFlowService,
                userService,
                vpnKeyService,
                idempotencyService,
                paymentService,
                plans,
                referralService
        );
        setIdempotencyTtl(handler, 10L);

        when(adminService.isAdmin(anyLong())).thenReturn(false);
        User user = new User();
        user.setDisabled(false);
        when(userService.registerOrUpdate(any(org.telegram.telegrambots.meta.api.objects.User.class))).thenReturn(user);

        SendMessage menu = SendMessage.builder().chatId("1").text("main").build();
        when(botMenuService.mainMenu(eq(1L), eq(false), eq(user))).thenReturn(menu);
        SendMessage cmd = SendMessage.builder().chatId("1").text("\u200B").build();
        when(botMenuService.commandKeyboardMessage(eq(1L), eq(false))).thenReturn(cmd);

        Update update = messageUpdate(1L, 100L, "/start");
        List<BotApiMethod<?>> result = handler.handle(update);

        assertEquals(2, result.size());
        SendMessage out = (SendMessage) result.get(0);
        SendMessage out2 = (SendMessage) result.get(1);
        assertEquals("main", out.getText());
        assertEquals("\u200B", out2.getText());
    }

    @Test
    void buyPlanBlockedByIdempotency() throws Exception {
        BotMenuService botMenuService = mock(BotMenuService.class);
        AdminService adminService = mock(AdminService.class);
        AdminStateService adminStateService = mock(AdminStateService.class);
        AdminFlowService adminFlowService = mock(AdminFlowService.class);
        UserService userService = mock(UserService.class);
        VpnKeyService vpnKeyService = mock(VpnKeyService.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        PaymentService paymentService = mock(PaymentService.class);
        SubscriptionPlansProperties plans = new SubscriptionPlansProperties();
        ReferralService referralService = mock(ReferralService.class);

        BotUpdateHandler handler = new BotUpdateHandler(
                botMenuService,
                adminService,
                adminStateService,
                adminFlowService,
                userService,
                vpnKeyService,
                idempotencyService,
                paymentService,
                plans,
                referralService
        );
        setIdempotencyTtl(handler, 10L);

        when(adminService.isAdmin(anyLong())).thenReturn(false);
        User user = new User();
        user.setId(42L);
        when(userService.registerOrUpdate(any(org.telegram.telegrambots.meta.api.objects.User.class))).thenReturn(user);
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(false);

        Update update = callbackUpdate(1L, 100L, "cb1", "BUY_1M");
        List<BotApiMethod<?>> result = handler.handle(update);

        assertEquals(1, result.size());
        assertInstanceOf(AnswerCallbackQuery.class, result.get(0));
        verify(paymentService, never()).createPayment(any(), anyInt(), anyInt(), any());
    }

    @Test
    void getKeyWithoutSubscriptionShowsWarning() throws Exception {
        BotMenuService botMenuService = mock(BotMenuService.class);
        AdminService adminService = mock(AdminService.class);
        AdminStateService adminStateService = mock(AdminStateService.class);
        AdminFlowService adminFlowService = mock(AdminFlowService.class);
        UserService userService = mock(UserService.class);
        VpnKeyService vpnKeyService = mock(VpnKeyService.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        PaymentService paymentService = mock(PaymentService.class);
        SubscriptionPlansProperties plans = new SubscriptionPlansProperties();
        ReferralService referralService = mock(ReferralService.class);

        BotUpdateHandler handler = new BotUpdateHandler(
                botMenuService,
                adminService,
                adminStateService,
                adminFlowService,
                userService,
                vpnKeyService,
                idempotencyService,
                paymentService,
                plans,
                referralService
        );
        setIdempotencyTtl(handler, 10L);

        when(adminService.isAdmin(anyLong())).thenReturn(false);
        User user = new User();
        user.setId(7L);
        when(userService.registerOrUpdate(any(org.telegram.telegrambots.meta.api.objects.User.class))).thenReturn(user);
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(true);
        SendMessage menu = SendMessage.builder().chatId("1").text("keys").build();
        when(botMenuService.myKeysMenu(1L, user)).thenReturn(menu);
        when(vpnKeyService.getKeyForUser(eq(user), eq(1L)))
                .thenThrow(new IllegalStateException("Нет активной подписки"));

        Update update = callbackUpdate(1L, 100L, "cb2", "KEY_GET:1");
        List<BotApiMethod<?>> result = handler.handle(update);

        assertEquals(3, result.size());
        assertInstanceOf(SendMessage.class, result.get(0));
        assertInstanceOf(SendMessage.class, result.get(1));
        assertInstanceOf(AnswerCallbackQuery.class, result.get(2));
    }

    @Test
    void disabledUserGetsBlockedMessageOnCallback() throws Exception {
        BotMenuService botMenuService = mock(BotMenuService.class);
        AdminService adminService = mock(AdminService.class);
        AdminStateService adminStateService = mock(AdminStateService.class);
        AdminFlowService adminFlowService = mock(AdminFlowService.class);
        UserService userService = mock(UserService.class);
        VpnKeyService vpnKeyService = mock(VpnKeyService.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        PaymentService paymentService = mock(PaymentService.class);
        SubscriptionPlansProperties plans = new SubscriptionPlansProperties();
        ReferralService referralService = mock(ReferralService.class);

        BotUpdateHandler handler = new BotUpdateHandler(
                botMenuService,
                adminService,
                adminStateService,
                adminFlowService,
                userService,
                vpnKeyService,
                idempotencyService,
                paymentService,
                plans,
                referralService
        );
        setIdempotencyTtl(handler, 10L);

        when(adminService.isAdmin(anyLong())).thenReturn(false);
        User user = new User();
        user.setDisabled(true);
        when(userService.registerOrUpdate(any(org.telegram.telegrambots.meta.api.objects.User.class))).thenReturn(user);

        Update update = callbackUpdate(1L, 100L, "cb3", "MENU_SUBSCRIPTION");
        List<BotApiMethod<?>> result = handler.handle(update);

        assertEquals(2, result.size());
        assertInstanceOf(SendMessage.class, result.get(0));
        assertInstanceOf(AnswerCallbackQuery.class, result.get(1));
    }

    private static Update messageUpdate(long chatId, long userId, String text) {
        Update update = new Update();
        Message msg = new Message();
        msg.setText(text);
        Chat chat = new Chat();
        chat.setId(chatId);
        msg.setChat(chat);
        org.telegram.telegrambots.meta.api.objects.User from = new org.telegram.telegrambots.meta.api.objects.User();
        from.setId(userId);
        msg.setFrom(from);
        update.setMessage(msg);
        return update;
    }

    private static Update callbackUpdate(long chatId, long userId, String callbackId, String data) {
        Update update = new Update();
        CallbackQuery cq = new CallbackQuery();
        cq.setId(callbackId);
        cq.setData(data);
        Message msg = new Message();
        msg.setMessageId(1);
        Chat chat = new Chat();
        chat.setId(chatId);
        msg.setChat(chat);
        cq.setMessage(msg);
        org.telegram.telegrambots.meta.api.objects.User from = new org.telegram.telegrambots.meta.api.objects.User();
        from.setId(userId);
        cq.setFrom(from);
        update.setCallbackQuery(cq);
        return update;
    }

    private static void setIdempotencyTtl(BotUpdateHandler handler, long seconds) throws Exception {
        Field f = BotUpdateHandler.class.getDeclaredField("idempotencyTtlSeconds");
        f.setAccessible(true);
        f.set(handler, seconds);
    }
}
