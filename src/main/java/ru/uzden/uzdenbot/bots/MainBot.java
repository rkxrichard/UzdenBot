package ru.uzden.uzdenbot.bots;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import ru.uzden.uzdenbot.services.BotUpdateHandler;
import ru.uzden.uzdenbot.services.UpdateGuardService;

import java.util.List;

@Slf4j
@Component
public class MainBot extends TelegramLongPollingBot {

    private final UpdateGuardService updateGuardService;
    private final BotUpdateHandler botUpdateHandler;

    private final String token;
    private final String username;

    @Autowired
    public MainBot(
            UpdateGuardService updateGuardService,
            BotUpdateHandler botUpdateHandler,
            @Value("${telegram.bot.token}") String token,
            @Value("${telegram.bot.username}")String username) {
        this.updateGuardService = updateGuardService;
        this.botUpdateHandler = botUpdateHandler;
        this.token = token;
        this.username = username;
    }

    @Override
    public String getBotUsername() {
        return username;
    }

    @Override
    public String getBotToken() {
        return token;
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            UpdateGuardService.GuardResult guard = updateGuardService.guard(update);
            if (guard.blocked()) {
                executeAll(guard.responses());
                return;
            }

            executeAll(botUpdateHandler.handle(update));
        } catch (Exception e) {
            log.error("Ошибка в боте: ", e);
        }
    }

    private void executeAll(List<? extends BotApiMethod<?>> methods) throws Exception {
        for (BotApiMethod<?> method : methods) {
            execute(method);
        }
    }

}
