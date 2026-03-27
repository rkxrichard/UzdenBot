package ru.uzden.uzdenbot.services;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import ru.uzden.uzdenbot.config.XuiProperties;
import ru.uzden.uzdenbot.repositories.SubscriptionRepository;
import ru.uzden.uzdenbot.repositories.UserRepository;
import ru.uzden.uzdenbot.repositories.VpnKeyRepository;
import ru.uzden.uzdenbot.xui.ThreeXuiClient;
import ru.uzden.uzdenbot.xui.VlessLinkBuilder;

import java.lang.reflect.Method;

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
                mock(VlessLinkBuilder.class),
                mock(TransactionTemplate.class),
                new XuiProperties(
                        "http://example.com",
                        "/panel",
                        "user",
                        "pass",
                        1L,
                        4L,
                        "62.60.229.102",
                        443,
                        8443,
                        "WayGuard",
                        "WayGuard XHTTP",
                        "pubkey"
                ),
                "WayGuard"
        );

        Method method = VpnKeyService.class.getDeclaredMethod("resolveReplacementInbound", Long.class);
        method.setAccessible(true);

        assertEquals(4L, method.invoke(service, 1L));
        assertEquals(1L, method.invoke(service, 4L));
    }
}
