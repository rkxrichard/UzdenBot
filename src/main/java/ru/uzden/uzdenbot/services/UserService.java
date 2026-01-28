package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.repositories.UserRepository;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public User registerOrUpdate(org.telegram.telegrambots.meta.api.objects.User tgUser) {
        Long telegramId = tgUser.getId();
        String username = tgUser.getUserName();

        if (userRepository.findUserByTelegramId(telegramId).isPresent()) {

        }

        return userRepository.findUserByTelegramId(telegramId)
                .map(u -> {
                    if (username != null && !username.equals(u.getUsername())) {
                        u.setUsername(username);
                    }
                    return u;
                }).orElseGet(() -> {
                    User u = new User();
                    u.setTelegramId(telegramId);
                    u.setUsername(username);
                    u.setCreatedAt(LocalDateTime.now());
                    return userRepository.save(u);
                });
    }
}
