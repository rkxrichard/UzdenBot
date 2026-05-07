package ru.uzden.uzdenbot.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import ru.uzden.uzdenbot.config.RuEuXuiProperties;
import ru.uzden.uzdenbot.config.SubscriptionProxyProperties;
import ru.uzden.uzdenbot.config.XuiProperties;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.xui.ThreeXuiClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class SubscriptionProxyService {

    private final SubscriptionProxyProperties properties;
    private final SubscriptionPayloadService payloadService;
    private final ThreeXuiClient defaultClient;
    private final ThreeXuiClient ruEuClient;

    public SubscriptionProxyService(
            SubscriptionProxyProperties properties,
            SubscriptionPayloadService payloadService,
            ThreeXuiClient defaultClient,
            XuiProperties xuiProperties,
            RuEuXuiProperties ruEuXuiProperties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.payloadService = payloadService;
        this.defaultClient = defaultClient;
        if (ruEuXuiProperties != null && ruEuXuiProperties.configured()) {
            this.ruEuClient = new ThreeXuiClient(restClientBuilder, ruEuXuiProperties.toXuiProperties(), objectMapper);
        } else {
            this.ruEuClient = null;
        }
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public String buildSubscriptionUrl(VpnKey.Backend backend, String subId) {
        if (!enabled()) {
            return clientFor(backend).buildSubscriptionUrl(subId);
        }
        return normalizeBaseUrl(properties.baseUrl()) + "/" + backendPath(backend) + "/" + urlPath(subId);
    }

    public ProxiedSubscription fetchAndRewrite(VpnKey.Backend backend, String subId) {
        ResponseEntity<String> upstream = clientFor(backend).fetchSubscription(subId);
        String body = payloadService.rewrite(upstream.getBody());
        return new ProxiedSubscription(body, upstream.getHeaders());
    }

    public String profileTitleHeaderValue() {
        return payloadService.profileTitleHeaderValue();
    }

    public String profileTitle() {
        return payloadService.profileTitle();
    }

    public VpnKey.Backend parseBackend(String raw) {
        if (raw == null || raw.isBlank() || "default".equalsIgnoreCase(raw)) {
            return VpnKey.Backend.DEFAULT;
        }
        if ("ru-eu".equalsIgnoreCase(raw) || "ru_eu".equalsIgnoreCase(raw)) {
            return VpnKey.Backend.RU_EU;
        }
        throw new IllegalStateException("Unknown subscription backend: " + raw);
    }

    private ThreeXuiClient clientFor(VpnKey.Backend backend) {
        VpnKey.Backend resolved = backend == null ? VpnKey.Backend.DEFAULT : backend;
        return switch (resolved) {
            case DEFAULT -> defaultClient;
            case RU_EU -> {
                if (ruEuClient == null) {
                    throw new IllegalStateException("RU+EU панель не настроена");
                }
                yield ruEuClient;
            }
        };
    }

    private String backendPath(VpnKey.Backend backend) {
        return backend == VpnKey.Backend.RU_EU ? "ru-eu" : "default";
    }

    private String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String urlPath(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record ProxiedSubscription(String body, HttpHeaders upstreamHeaders) {}
}
