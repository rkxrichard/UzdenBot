package ru.uzden.uzdenbot.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.uzden.uzdenbot.utils.BotMessageFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class UpdateGuardService {

    private final RateLimiterService rateLimiterService;
    private final IdempotencyService idempotencyService;
    private final Duration updateIdempotencyTtl;

    public UpdateGuardService(
            RateLimiterService rateLimiterService,
            IdempotencyService idempotencyService,
            @Value("${app.idempotency.update-ttl-seconds:600}") long updateTtlSeconds) {
        this.rateLimiterService = rateLimiterService;
        this.idempotencyService = idempotencyService;
        this.updateIdempotencyTtl = Duration.ofSeconds(updateTtlSeconds);
    }

    public GuardResult guard(Update update) {
        GuardContext ctx = GuardContext.from(update);
        if (ctx.userId != null && ctx.chatId != null) {
            try {
                String key = "rl:user:" + ctx.userId;
                if (!rateLimiterService.allow(key)) {
                    List<BotApiMethod<?>> responses = new ArrayList<>();
                    if (ctx.callbackId != null) {
                        AnswerCallbackQuery ack = BotMessageFactory.callbackAnswer(
                                ctx.callbackId,
                                "Слишком часто. Подождите пару секунд."
                        );
                        responses.add(ack);
                    } else {
                        SendMessage sm = BotMessageFactory.simpleMessage(ctx.chatId, "Слишком часто. Подождите пару секунд.");
                        responses.add(sm);
                    }
                    return GuardResult.blocked(responses);
                }
            } catch (Exception e) {
                log.warn("Rate limit check failed: {}", e.getMessage());
            }
        }

        Integer updateId = update == null ? null : update.getUpdateId();
        if (updateId != null) {
            try {
                boolean ok = idempotencyService.tryAcquire("idemp:update:" + updateId, updateIdempotencyTtl);
                if (!ok) {
                    return GuardResult.blocked(List.of());
                }
            } catch (Exception e) {
                log.warn("Update idempotency check failed: {}", e.getMessage());
            }
        }

        return GuardResult.allowed();
    }

    public record GuardResult(boolean blocked, List<BotApiMethod<?>> responses) {
        public static GuardResult allowed() {
            return new GuardResult(false, List.of());
        }

        public static GuardResult blocked(List<BotApiMethod<?>> responses) {
            return new GuardResult(true, responses == null ? List.of() : responses);
        }
    }

    private static final class GuardContext {
        final Long chatId;
        final Long userId;
        final String callbackId;

        private GuardContext(Long chatId, Long userId, String callbackId) {
            this.chatId = chatId;
            this.userId = userId;
            this.callbackId = callbackId;
        }

        static GuardContext from(Update update) {
            if (update == null) return new GuardContext(null, null, null);
            if (update.hasMessage() && update.getMessage().getFrom() != null) {
                return new GuardContext(
                        update.getMessage().getChatId(),
                        update.getMessage().getFrom().getId(),
                        null
                );
            }
            if (update.hasCallbackQuery() && update.getCallbackQuery().getFrom() != null) {
                return new GuardContext(
                        update.getCallbackQuery().getMessage().getChatId(),
                        update.getCallbackQuery().getFrom().getId(),
                        update.getCallbackQuery().getId()
                );
            }
            return new GuardContext(null, null, null);
        }
    }
}
