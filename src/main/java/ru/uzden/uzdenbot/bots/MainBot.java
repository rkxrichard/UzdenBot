package ru.uzden.uzdenbot.bots;


import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.List;

@Component
public class MainBot implements LongPollingUpdateConsumer {
    @Override
    public void consume(List<Update> updates) {
        for (Update update : updates) {
            if (update.hasMessage() && update.getMessage().hasText()) {
                System.out.println(
                        "Message: " + update.getMessage().getText()
                );
            }
        }

    }
}
