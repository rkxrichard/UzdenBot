package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import ru.uzden.uzdenbot.entities.Subscription;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.utils.BotMessageFactory;
import ru.uzden.uzdenbot.utils.BotTextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminFlowService {

    private final AdminStateService adminStateService;
    private final SubscriptionService subscriptionService;
    private final UserService userService;
    private final VpnKeyService vpnKeyService;

    public List<SendMessage> handleAdminInput(Long chatId, String text, AdminAction action) {
        List<SendMessage> out = new ArrayList<>();
        String trimmed = text == null ? "" : text.trim();
        switch (action) {
            case ADD_SUBSCRIPTION -> handleAddSubscription(chatId, trimmed, out);
            case CHECK_SUBSCRIPTION -> handleCheckSubscription(chatId, trimmed, out);
            case REVOKE_SUBSCRIPTION -> handleRevokeSubscription(chatId, trimmed, out);
            case DISABLE_USER -> handleDisableUser(chatId, trimmed, out);
            case ENABLE_USER -> handleEnableUser(chatId, trimmed, out);
            default -> {
            }
        }
        return out;
    }

    private void handleAddSubscription(Long chatId, String text, List<SendMessage> out) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Нужно указать @username и число дней, например: @user 30"));
            return;
        }
        String username = normalizeUsername(parts[0]);
        Integer days = parseDays(parts[1]);
        if (username == null || days == null || days <= 0) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Некорректный формат. Пример: @user 30"));
            return;
        }
        Optional<User> userOpt = findUserByIdentifier(username);
        if (userOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }

        Subscription sub = subscriptionService.extendSubscription(userOpt.get(), days);
        adminStateService.clear(chatId);
        out.add(BotMessageFactory.simpleMessage(chatId, "✅ Подписка выдана до: " + BotTextUtils.formatDate(sub.getEndDate())));
    }

    private void handleCheckSubscription(Long chatId, String text, List<SendMessage> out) {
        String username = firstTokenUsername(text);
        if (username == null) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Нужно указать @username."));
            return;
        }
        Optional<User> userOpt = findUserByIdentifier(username);
        if (userOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }
        User user = userOpt.get();
        Optional<Subscription> subOpt = subscriptionService.getActiveSubscription(user);
        if (subOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "❌ Активной подписки нет."));
        } else {
            long daysLeft = subscriptionService.getDaysLeft(subOpt.get());
            out.add(BotMessageFactory.simpleMessage(chatId,
                    "✅ Активна. Осталось: " + daysLeft + " дн. До: " + BotTextUtils.formatDate(subOpt.get().getEndDate())));
        }
        adminStateService.clear(chatId);
    }

    private void handleRevokeSubscription(Long chatId, String text, List<SendMessage> out) {
        String username = firstTokenUsername(text);
        if (username == null) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Нужно указать @username."));
            return;
        }
        Optional<User> userOpt = findUserByIdentifier(username);
        if (userOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }
        Optional<Subscription> revoked = subscriptionService.revokeActiveSubscription(userOpt.get());
        adminStateService.clear(chatId);
        if (revoked.isPresent()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "🛑 Подписка отключена."));
        } else {
            out.add(BotMessageFactory.simpleMessage(chatId, "Активной подписки не было."));
        }
    }

    private void handleDisableUser(Long chatId, String text, List<SendMessage> out) {
        String username = firstTokenUsername(text);
        if (username == null) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Нужно указать @username."));
            return;
        }
        Optional<User> userOpt = findUserByIdentifier(username);
        if (userOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }
        User user = userService.setDisabled(userOpt.get(), true);
        try {
            vpnKeyService.revokeActiveKey(user);
        } catch (Exception e) {
            log.warn("Не удалось отозвать ключ для пользователя {}: {}", user.getId(), e.getMessage());
        }
        adminStateService.clear(chatId);
        out.add(BotMessageFactory.simpleMessage(chatId, "🚫 Пользователь отключён."));
    }

    private void handleEnableUser(Long chatId, String text, List<SendMessage> out) {
        String username = firstTokenUsername(text);
        if (username == null) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Нужно указать @username."));
            return;
        }
        Optional<User> userOpt = findUserByIdentifier(username);
        if (userOpt.isEmpty()) {
            out.add(BotMessageFactory.simpleMessage(chatId, "Пользователь не найден. Он должен сначала написать /start."));
            return;
        }
        userService.setDisabled(userOpt.get(), false);
        adminStateService.clear(chatId);
        out.add(BotMessageFactory.simpleMessage(chatId, "✅ Пользователь включён."));
    }

    private Optional<User> findUserByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) return Optional.empty();
        if (identifier.chars().allMatch(Character::isDigit)) {
            try {
                return userService.findByTelegramId(Long.parseLong(identifier));
            } catch (NumberFormatException ignore) {
                return Optional.empty();
            }
        }
        return userService.findByUsername(identifier);
    }

    private String normalizeUsername(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.startsWith("@")) t = t.substring(1);
        if (t.isBlank()) return null;
        return t;
    }

    private String firstTokenUsername(String raw) {
        if (raw == null) return null;
        String[] parts = raw.trim().split("\\s+");
        if (parts.length == 0) return null;
        return normalizeUsername(parts[0]);
    }

    private Integer parseDays(String raw) {
        if (raw == null) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
