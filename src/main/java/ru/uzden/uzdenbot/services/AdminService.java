package ru.uzden.uzdenbot.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Service
public class AdminService {

    private final Set<Long> adminIds;

    public AdminService(@Value("${telegram.admin-ids:}") String adminIdsRaw) {
        if (adminIdsRaw == null || adminIdsRaw.isBlank()) {
            this.adminIds = Collections.emptySet();
            return;
        }
        Set<Long> ids = new HashSet<>();
        String[] parts = adminIdsRaw.split(",");
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            try {
                ids.add(Long.parseLong(t));
            } catch (NumberFormatException ignore) {
            }
        }
        this.adminIds = Collections.unmodifiableSet(ids);
    }

    public boolean isAdmin(Long telegramId) {
        return telegramId != null && adminIds.contains(telegramId);
    }
}
