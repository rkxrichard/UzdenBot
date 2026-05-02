package ru.uzden.uzdenbot.services;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.transaction.support.TransactionTemplate;
import ru.uzden.uzdenbot.config.RuEuXuiProperties;
import ru.uzden.uzdenbot.config.XuiProperties;
import ru.uzden.uzdenbot.repositories.SubscriptionRepository;
import ru.uzden.uzdenbot.repositories.UserRepository;
import ru.uzden.uzdenbot.repositories.VpnKeyRepository;
import ru.uzden.uzdenbot.xui.ThreeXuiClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class VpnKeyServiceTest {

    @Test
    void replaceAlternatesBetweenPrimaryAndXhttpInbound() throws Exception {
        VpnKeyService service = new VpnKeyService(
                mock(VpnKeyRepository.class),
                mock(UserRepository.class),
                mock(SubscriptionService.class),
                mock(SubscriptionRepository.class),
                mock(ThreeXuiClient.class),
                mock(TransactionTemplate.class),
                new XuiProperties(
                        "http://example.com",
                        "/panel",
                        "",
                        "user",
                        "pass",
                        1L,
                        4L,
                        List.of(1L, 4L)
                ),
                new RuEuXuiProperties(
                        "",
                        "",
                        "",
                        "",
                        "",
                        0L,
                        0L,
                        List.of()
                ),
                RestClient.builder(),
                new ObjectMapper()
        );

        Method method = VpnKeyService.class.getDeclaredMethod("resolveReplacementInbound", ru.uzden.uzdenbot.entities.VpnKey.Backend.class, Long.class);
        method.setAccessible(true);

        assertEquals(4L, method.invoke(service, ru.uzden.uzdenbot.entities.VpnKey.Backend.DEFAULT, 1L));
        assertEquals(1L, method.invoke(service, ru.uzden.uzdenbot.entities.VpnKey.Backend.DEFAULT, 4L));
    }
}
