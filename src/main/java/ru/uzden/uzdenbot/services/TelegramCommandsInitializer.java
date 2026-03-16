package ru.uzden.uzdenbot.services;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChat;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import ru.uzden.uzdenbot.bots.MainBot;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramCommandsInitializer {

    private final MainBot mainBot;
    private final AdminService adminService;

    @PostConstruct
    public void registerCommands() {
        registerDefaultCommands();
        registerAdminCommands();
    }

    private void registerDefaultCommands() {
        try {
            SetMyCommands commandRequest = new SetMyCommands();
            commandRequest.setScope(new BotCommandScopeDefault());
            commandRequest.setCommands(List.of(
                    new BotCommand("start", "Открыть меню")
            ));
            mainBot.executeMethod(commandRequest);
        } catch (Exception e) {
            log.warn("Failed to register default bot commands: {}", e.getMessage());
        }
    }

    private void registerAdminCommands() {
        for (Long adminId : adminService.getAdminIds()) {
            if (adminId == null) continue;
            try {
                SetMyCommands commandRequest = new SetMyCommands();
                commandRequest.setScope(new BotCommandScopeChat(adminId.toString()));
                commandRequest.setCommands(List.of(
                        new BotCommand("start", "Открыть меню"),
                        new BotCommand("admin", "Открыть админ-панель"),
                        new BotCommand("cancel", "Отменить действие")
                ));
                mainBot.executeMethod(commandRequest);
            } catch (Exception e) {
                log.warn("Failed to register admin commands for {}: {}", adminId, e.getMessage());
            }
        }
    }
}
