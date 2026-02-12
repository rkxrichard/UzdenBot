package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.utils.BotMessageFactory;
import ru.uzden.uzdenbot.utils.BotTextUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BotUpdateHandler {

    private final BotMenuService botMenuService;
    private final AdminService adminService;
    private final AdminStateService adminStateService;
    private final AdminFlowService adminFlowService;
    private final UserService userService;
    private final SubscriptionService subscriptionService;
    private final VpnKeyService vpnKeyService;
    private final IdempotencyService idempotencyService;
    private final PaymentService paymentService;

    @Value("${app.idempotency.ttl-seconds:10}")
    private long idempotencyTtlSeconds;

    public List<BotApiMethod<?>> handle(Update update) {
        if (update == null) return List.of();
        if (update.hasMessage() && update.getMessage().hasText()) {
            return handleMessage(update);
        }
        if (update.hasCallbackQuery()) {
            return handleCallback(update);
        }
        return List.of();
    }

    private List<BotApiMethod<?>> handleMessage(Update update) {
        List<BotApiMethod<?>> out = new ArrayList<>();
        String text = update.getMessage().getText();
        Long chatId = update.getMessage().getChatId();
        var from = update.getMessage().getFrom();
        boolean isAdmin = adminService.isAdmin(from.getId());

        User user = userService.registerOrUpdate(from);

        if (isAdmin) {
            if ("/admin".equalsIgnoreCase(text.trim())) {
                adminStateService.clear(chatId);
                out.add(botMenuService.adminMenu(chatId));
                out.add(botMenuService.commandKeyboardMessage(chatId, true));
                return out;
            }
            Optional<AdminAction> pending = adminStateService.get(chatId);
            if (pending.isPresent() && !"/start".equals(text)) {
                if ("/cancel".equalsIgnoreCase(text.trim())) {
                    adminStateService.clear(chatId);
                    out.add(BotMessageFactory.simpleMessage(chatId, "✅ Действие отменено."));
                    return out;
                }
                List<SendMessage> adminResponses = adminFlowService.handleAdminInput(chatId, text, pending.get());
                out.addAll(adminResponses);
                return out;
            }
        }

        if (user.isDisabled() && !isAdmin) {
            out.add(BotMessageFactory.simpleMessage(chatId, "🚫 Ваш доступ отключён. Обратитесь к администратору."));
            return out;
        }

        if ("/start".equals(text)) {
            out.add(botMenuService.mainMenu(chatId, isAdmin));
            out.add(botMenuService.commandKeyboardMessage(chatId, isAdmin));
        }
        return out;
    }

    private List<BotApiMethod<?>> handleCallback(Update update) {
        List<BotApiMethod<?>> out = new ArrayList<>();
        var cq = update.getCallbackQuery();
        String data = cq.getData();
        Long chatId = cq.getMessage().getChatId();
        Integer messageId = cq.getMessage().getMessageId();
        String callbackId = cq.getId();
        boolean isAdmin = adminService.isAdmin(cq.getFrom().getId());
        boolean answered = false;

        User user = userService.registerOrUpdate(cq.getFrom());
        if (user.isDisabled() && !isAdmin) {
            out.add(BotMessageFactory.simpleMessage(chatId, "🚫 Ваш доступ отключён. Обратитесь к администратору."));
            out.add(BotMessageFactory.callbackAnswer(callbackId, null));
            return out;
        }

        switch (data) {
            case "MENU_SUBSCRIPTION" -> out.add(BotMessageFactory.editFromSendMessage(
                    botMenuService.subscriptionMenu(chatId), chatId, messageId));
            case "MENU_HELP" -> out.add(BotMessageFactory.editFromSendMessage(
                    botMenuService.instructionsMenu(chatId), chatId, messageId));
            case "MENU_BACK" -> {
                adminStateService.clear(chatId);
                out.add(BotMessageFactory.editFromSendMessage(
                        botMenuService.mainMenu(chatId, isAdmin), chatId, messageId));
            }
            case "MENU_ADMIN" -> {
                if (isAdmin) {
                    adminStateService.clear(chatId);
                    out.add(BotMessageFactory.editFromSendMessage(
                            botMenuService.adminMenu(chatId), chatId, messageId));
                }
            }
            case "MENU_BUY" -> out.add(BotMessageFactory.editFromSendMessage(
                    botMenuService.subscriptionPlanMenu(chatId), chatId, messageId));
            case "BUY_1M" -> answered = handlePlanPurchase(out, chatId, callbackId, cq.getFrom(), 30, 199, "1 месяц");
            case "BUY_3M" -> answered = handlePlanPurchase(out, chatId, callbackId, cq.getFrom(), 90, 399, "3 месяца");
            case "BUY_6M" -> answered = handlePlanPurchase(out, chatId, callbackId, cq.getFrom(), 180, 699, "6 месяцев");
            case "BUY_12M" -> answered = handlePlanPurchase(out, chatId, callbackId, cq.getFrom(), 365, 1199, "12 месяцев");
            case "MENU_GET_KEY" -> answered = handleGetKey(out, chatId, callbackId, user);
            case "MENU_REPLACE_KEY" -> answered = handleReplaceKey(out, chatId, callbackId, user);
            case "ADMIN_ADD_SUB" -> {
                if (isAdmin) {
                    adminStateService.set(chatId, AdminAction.ADD_SUBSCRIPTION);
                    out.add(BotMessageFactory.simpleMessage(chatId,
                            "Отправьте @username и количество дней через пробел, например:\n\n@user 30\n\n/cancel — отмена."));
                }
            }
            case "ADMIN_CHECK_SUB" -> {
                if (isAdmin) {
                    adminStateService.set(chatId, AdminAction.CHECK_SUBSCRIPTION);
                    out.add(BotMessageFactory.simpleMessage(chatId,
                            "Отправьте @username для проверки подписки.\n\n/cancel — отмена."));
                }
            }
            case "ADMIN_REVOKE_SUB" -> {
                if (isAdmin) {
                    adminStateService.set(chatId, AdminAction.REVOKE_SUBSCRIPTION);
                    out.add(BotMessageFactory.simpleMessage(chatId,
                            "Отправьте @username, чтобы отключить подписку.\n\n/cancel — отмена."));
                }
            }
            case "ADMIN_DISABLE_USER" -> {
                if (isAdmin) {
                    adminStateService.set(chatId, AdminAction.DISABLE_USER);
                    out.add(BotMessageFactory.simpleMessage(chatId,
                            "Отправьте @username, чтобы отключить пользователя.\n\n/cancel — отмена."));
                }
            }
            case "ADMIN_ENABLE_USER" -> {
                if (isAdmin) {
                    adminStateService.set(chatId, AdminAction.ENABLE_USER);
                    out.add(BotMessageFactory.simpleMessage(chatId,
                            "Отправьте @username, чтобы включить пользователя.\n\n/cancel — отмена."));
                }
            }
            default -> {
            }
        }

        if (!answered) {
            out.add(BotMessageFactory.callbackAnswer(callbackId, null));
        }
        return out;
    }

    private boolean handleGetKey(List<BotApiMethod<?>> out, Long chatId, String callbackId, User user) {
        if (!subscriptionService.hasActiveSubscription(user)) {
            out.add(BotMessageFactory.simpleMessage(chatId,
                    "❌ У вас нет активной подписки. Сначала купите/продлите подписку."));
            out.add(botMenuService.subscriptionMenu(chatId));
            return false;
        }

        if (!acquireIdempotency(out, callbackId, "get_key:" + user.getId())) {
            return true;
        }

        try {
            var key = vpnKeyService.issueKey(user);
            String msg = "🔑 Ваш VPN-ключ:\n\n" +
                    "<code>" + BotTextUtils.escapeHtml(key.getKeyValue()) + "</code>\n\n" +
                    "📌 Скопируйте ссылку и импортируйте в клиент (Hiddify / v2rayNG / Shadowrocket и т.д.).";
            SendMessage sm = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(msg)
                    .parseMode("HTML")
                    .build();
            out.add(sm);
        } catch (Exception e) {
            out.add(BotMessageFactory.simpleMessage(chatId, "❌ Не удалось выдать ключ: " + e.getMessage()));
        }

        out.add(botMenuService.subscriptionMenu(chatId));
        return false;
    }

    private boolean handleReplaceKey(List<BotApiMethod<?>> out, Long chatId, String callbackId, User user) {
        if (!subscriptionService.hasActiveSubscription(user)) {
            out.add(BotMessageFactory.simpleMessage(chatId,
                    "❌ У вас нет активной подписки. Сначала купите/продлите подписку."));
            out.add(botMenuService.subscriptionMenu(chatId));
            return false;
        }

        if (!acquireIdempotency(out, callbackId, "replace_key:" + user.getId())) {
            return true;
        }

        try {
            var key = vpnKeyService.replaceKey(user);
            String msg = "🔄 Ваш VPN-ключ заменён. Новый ключ:\n\n" +
                    "<code>" + BotTextUtils.escapeHtml(key.getKeyValue()) + "</code>\n\n" +
                    "📌 Старый ключ отключён.";
            SendMessage sm = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(msg)
                    .parseMode("HTML")
                    .build();
            out.add(sm);
        } catch (Exception e) {
            out.add(BotMessageFactory.simpleMessage(chatId, "❌ Не удалось заменить ключ: " + e.getMessage()));
        }

        out.add(botMenuService.subscriptionMenu(chatId));
        return false;
    }

    private boolean handlePlanPurchase(List<BotApiMethod<?>> out, Long chatId, String callbackId,
                                    org.telegram.telegrambots.meta.api.objects.User from,
                                    int days, int price, String label) {
        User user = userService.registerOrUpdate(from);
        if (!acquireIdempotency(out, callbackId, "plan:" + days + ":" + user.getId())) {
            return true;
        }
        try {
            PaymentService.PaymentInitResult init = paymentService.createPayment(user, days, price, label);
            String url = init.confirmationUrl();
            if (url != null && !url.isBlank()) {
                String msg = "💳 Счет на " + label + " создан.\n" +
                        "Сумма: " + price + "₽\n\n" +
                        "Оплатить: <a href=\"" + BotTextUtils.escapeHtml(url) + "\">перейти к оплате</a>\n\n" +
                        "После оплаты подписка активируется автоматически.";
                SendMessage sm = SendMessage.builder()
                        .chatId(chatId.toString())
                        .text(msg)
                        .parseMode("HTML")
                        .build();
                out.add(sm);
            } else {
                String msg = "💳 Счет на " + label + " создан.\n" +
                        "Сумма: " + price + "₽\n" +
                        "Ссылка на оплату пока недоступна. Попробуйте еще раз чуть позже.";
                out.add(BotMessageFactory.simpleMessage(chatId, msg));
            }
        } catch (Exception e) {
            String msg = "❌ Не удалось создать платеж. Попробуйте еще раз позже.";
            out.add(BotMessageFactory.simpleMessage(chatId, msg));
        }
        out.add(botMenuService.subscriptionMenu(chatId));
        return false;
    }

    private boolean acquireIdempotency(List<BotApiMethod<?>> out, String callbackId, String key) {
        Duration ttl = Duration.ofSeconds(idempotencyTtlSeconds);
        try {
            if (idempotencyService.tryAcquire("idemp:" + key, ttl)) {
                return true;
            }
        } catch (Exception e) {
            log.warn("Idempotency check failed: {}", e.getMessage());
            return true;
        }
        AnswerCallbackQuery notice = BotMessageFactory.callbackAnswer(
                callbackId,
                "Запрос уже выполняется. Подождите немного."
        );
        out.add(notice);
        return false;
    }
}
