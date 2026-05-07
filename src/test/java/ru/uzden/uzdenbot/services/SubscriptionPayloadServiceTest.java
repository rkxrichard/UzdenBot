package ru.uzden.uzdenbot.services;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionPayloadServiceTest {

    @Test
    void rewriteRenamesServersAndSetsWayGuardGroup() {
        SubscriptionPayloadService service = new SubscriptionPayloadService("WayGuard");
        String plain = String.join("\n",
                "vless://u1@example.com:8443?type=xhttp&security=reality&group=1.2.3.4#old",
                "vless://u2@example.com:443?type=tcp&security=reality#old2",
                "vless://u3@example.com:443?type=grpc&security=reality#old3",
                "trojan://p4@example.com:443?security=tls#old4"
        );
        String encoded = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));

        String rewritten = service.rewrite(encoded);
        String decoded = new String(Base64.getDecoder().decode(rewritten), StandardCharsets.UTF_8);

        assertTrue(decoded.contains("group=WayGuard"));
        assertTrue(decoded.contains("#XHTTP%201"));
        assertTrue(decoded.contains("#TCP%202"));
        assertTrue(decoded.contains("#GRPC%203"));
        assertTrue(decoded.contains("#TROJAN%204"));
    }
}
