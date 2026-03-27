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
        assertTrue(link.contains("fp=firefox"));
        assertFalse(link.contains("flow="));
    }
}
