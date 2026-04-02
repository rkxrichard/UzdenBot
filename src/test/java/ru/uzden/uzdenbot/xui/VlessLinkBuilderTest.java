package ru.uzden.uzdenbot.xui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VlessLinkBuilderTest {

    @Test
    void buildLinkSupportsXhttpRealityInbound() {
        VlessLinkBuilder builder = new VlessLinkBuilder(
                "fallback-pbk",
                "fallback.example",
                "fallback.example:443",
                "WayGuard"
        );

        String uuid = "d4115e5c-a9b5-4da3-8af6-49293e38262a";
        String inboundJson = "{"
                + "\"settings\":\"{\\n  \\\"clients\\\": [\\n    {\\\"id\\\": \\\"" + uuid + "\\\", \\\"flow\\\": \\\"\\\"}\\n  ],\\n  \\\"decryption\\\": \\\"none\\\"}\","
                + "\"streamSettings\":\"{"
                + "\\\"network\\\":\\\"xhttp\\\","
                + "\\\"security\\\":\\\"reality\\\","
                + "\\\"realitySettings\\\":{"
                + "\\\"dest\\\":\\\"stats.vk-portal.net:443\\\","
                + "\\\"serverNames\\\":[\\\"stats.vk-portal.net\\\"],"
                + "\\\"shortIds\\\":[\\\"9f1e80dbaf\\\"],"
                + "\\\"settings\\\":{"
                + "\\\"publicKey\\\":\\\"NcLdVzwDsWwz2cfbdSHG3q57_pp8sCkBAscwCbVsfUo\\\","
                + "\\\"fingerprint\\\":\\\"firefox\\\","
                + "\\\"spiderX\\\":\\\"/\\\""
                + "}"
                + "},"
                + "\\\"xhttpSettings\\\":{"
                + "\\\"path\\\":\\\"/ru\\\","
                + "\\\"mode\\\":\\\"packet-up\\\""
                + "}"
                + "}\""
                + "}";

        String link = builder.buildLink(
                inboundJson,
                "62.60.229.102",
                8443,
                uuid,
                "WayGuard XHTTP"
        );

        assertTrue(link.contains("type=xhttp"));
        assertTrue(link.contains("security=reality"));
        assertTrue(link.contains("path=%2Fru"));
        assertTrue(link.contains("mode=packet-up"));
        assertTrue(link.contains("pbk=NcLdVzwDsWwz2cfbdSHG3q57_pp8sCkBAscwCbVsfUo"));
        assertTrue(link.contains("fp=firefox"));
        assertTrue(link.contains("sni=stats.vk-portal.net"));
        assertTrue(link.contains("sid=9f1e80dbaf"));
        assertFalse(link.contains("flow="));
    }

    @Test
    void buildLinkUsesExplicitFallbacksForBackendSpecificReality() {
        VlessLinkBuilder builder = new VlessLinkBuilder(
                "default-pbk",
                "default.example",
                "default.example:443",
                "WayGuard"
        );

        String uuid = "d4115e5c-a9b5-4da3-8af6-49293e38262a";
        String inboundJson = "{"
                + "\"settings\":\"{\\\"clients\\\":[{\\\"id\\\":\\\"" + uuid + "\\\"}],\\\"decryption\\\":\\\"none\\\"}\","
                + "\"streamSettings\":\"{"
                + "\\\"network\\\":\\\"xhttp\\\","
                + "\\\"security\\\":\\\"reality\\\","
                + "\\\"realitySettings\\\":{"
                + "\\\"shortIds\\\":[\\\"6ba85179e30d4fc2\\\"]"
                + "},"
                + "\\\"xhttpSettings\\\":{"
                + "\\\"path\\\":\\\"/ru\\\","
                + "\\\"mode\\\":\\\"packet-up\\\""
                + "}"
                + "}\""
                + "}";

        String link = builder.buildLink(
                inboundJson,
                "158.160.68.102",
                8443,
                uuid,
                "WayGuard RU+EU",
                new VlessLinkBuilder.LinkFallbacks(
                        "GTaF4zIww2fxExEpW8_V3KI93xwpqRlsazUWpFYT_BA",
                        "stats.vk-portal.net",
                        "stats.vk-portal.net:443",
                        "WayGuard RU+EU"
                )
        );

        assertTrue(link.contains("pbk=GTaF4zIww2fxExEpW8_V3KI93xwpqRlsazUWpFYT_BA"));
        assertTrue(link.contains("sni=stats.vk-portal.net"));
        assertTrue(link.contains("sid=6ba85179e30d4fc2"));
        assertTrue(link.contains("path=%2Fru"));
        assertTrue(link.contains("mode=packet-up"));
    }
}
