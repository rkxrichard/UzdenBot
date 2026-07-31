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
        assertTrue(decoded.contains("#1.%20XHTTP"));
        assertTrue(decoded.contains("#2.%20VLESS"));
        assertTrue(decoded.contains("#3.%20gRPC"));
        assertTrue(decoded.contains("#4.%20Trojan"));
    }
}
