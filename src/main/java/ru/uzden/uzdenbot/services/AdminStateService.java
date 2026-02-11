package ru.uzden.uzdenbot.services;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AdminStateService {

    private final Map<Long, AdminAction> pending = new ConcurrentHashMap<>();

    public Optional<AdminAction> get(Long chatId) {
        return Optional.ofNullable(pending.get(chatId));
    }

    public void set(Long chatId, AdminAction action) {
        if (chatId == null || action == null) return;
        pending.put(chatId, action);
    }

    public void clear(Long chatId) {
        if (chatId == null) return;
        pending.remove(chatId);
    }
}
