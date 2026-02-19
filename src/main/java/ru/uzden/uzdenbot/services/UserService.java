package ru.uzden.uzdenbot.services;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.uzden.uzdenbot.entities.User;
import ru.uzden.uzdenbot.repositories.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public User registerOrUpdate(org.telegram.telegrambots.meta.api.objects.User tgUser) {
        Long telegramId = tgUser.getId();
        String username = tgUser.getUserName();

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
                    try {
                        return userRepository.save(u);
                    } catch (DataIntegrityViolationException e) {
                        return userRepository.findUserByTelegramId(telegramId)
                                .orElseThrow(() -> e);
                    }
                });
    }

    @Transactional(readOnly = true)
    public Optional<User> findByTelegramId(Long telegramId) {
        return userRepository.findUserByTelegramId(telegramId);
    }

    @Transactional(readOnly = true)
    public Optional<User> findByUsername(String username) {
        if (username == null) return Optional.empty();
        return userRepository.findUserByUsernameIgnoreCase(username);
    }

    @Transactional
    public User setDisabled(User user, boolean disabled) {
        user.setDisabled(disabled);
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public List<User> listAll() {
        return userRepository.findAll();
    }
}
