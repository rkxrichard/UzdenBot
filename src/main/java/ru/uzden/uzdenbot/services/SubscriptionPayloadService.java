package ru.uzden.uzdenbot.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SubscriptionPayloadService {

    private final String profileTitle;

    public SubscriptionPayloadService(@Value("${app.subscription-proxy.title:WayGuard}") String profileTitle) {
        this.profileTitle = (profileTitle == null || profileTitle.isBlank()) ? "WayGuard" : profileTitle;
    }

    public String profileTitle() {
        return profileTitle;
    }

    public String profileTitleHeaderValue() {
        return "base64:" + Base64.getEncoder().encodeToString(profileTitle.getBytes(StandardCharsets.UTF_8));
    }

    public String rewrite(String rawPayload) {
        PayloadFormat format = detectFormat(rawPayload);
        String plainPayload = switch (format) {
            case BASE64 -> new String(Base64.getDecoder().decode(rawPayload.trim()), StandardCharsets.UTF_8);
            case PLAIN -> rawPayload == null ? "" : rawPayload;
        };

        List<String> rewritten = new ArrayList<>();
        int counter = 1;
        for (String line : plainPayload.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            rewritten.add(rewriteLine(trimmed, counter++));
        }

        String joined = String.join("\n", rewritten);
        return format == PayloadFormat.BASE64
                ? Base64.getEncoder().encodeToString(joined.getBytes(StandardCharsets.UTF_8))
                : joined;
    }

    private String rewriteLine(String line, int index) {
        int fragmentIdx = line.indexOf('#');
        String main = fragmentIdx >= 0 ? line.substring(0, fragmentIdx) : line;

        int queryIdx = main.indexOf('?');
        String prefix = queryIdx >= 0 ? main.substring(0, queryIdx) : main;
        String rawQuery = queryIdx >= 0 ? main.substring(queryIdx + 1) : "";

        LinkedHashMap<String, String> params = parseQuery(rawQuery);
        params.put("group", profileTitle);

        String protocolLabel = resolveProtocolLabel(prefix, params);
        String rebuilt = prefix;
        String query = buildQuery(params);
        if (!query.isBlank()) {
            rebuilt += "?" + query;
        }
        return rebuilt + "#" + encodeFragment(index + ". " + protocolLabel);
    }

    private String resolveProtocolLabel(String prefix, LinkedHashMap<String, String> params) {
        String lowerPrefix = prefix.toLowerCase();
        if (lowerPrefix.startsWith("trojan://")) {
            return "Trojan";
        }
        String type = params.get("type");
        if (type == null || type.isBlank()) {
            return "VLESS";
        }
        return switch (type.trim().toLowerCase()) {
            case "xhttp" -> "XHTTP";
            case "grpc" -> "gRPC";
            case "tcp" -> "VLESS";
            case "trojan" -> "Trojan";
            default -> type.trim().toUpperCase();
        };
    }

    private LinkedHashMap<String, String> parseQuery(String rawQuery) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) continue;
            int idx = pair.indexOf('=');
            String rawKey = idx >= 0 ? pair.substring(0, idx) : pair;
            String rawValue = idx >= 0 ? pair.substring(idx + 1) : "";
            String key = decode(rawKey);
            String value = decode(rawValue);
            out.put(key, value);
        }
        return out;
    }

    private String buildQuery(LinkedHashMap<String, String> params) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            parts.add(encodeQuery(entry.getKey()) + "=" + encodeQuery(entry.getValue()));
        }
        return String.join("&", parts);
    }

    private PayloadFormat detectFormat(String payload) {
        if (payload == null || payload.isBlank()) {
            return PayloadFormat.PLAIN;
        }
        String trimmed = payload.trim();
        if (trimmed.contains("://")) {
            return PayloadFormat.PLAIN;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(trimmed), StandardCharsets.UTF_8);
            return decoded.contains("://") ? PayloadFormat.BASE64 : PayloadFormat.PLAIN;
        } catch (IllegalArgumentException e) {
            return PayloadFormat.PLAIN;
        }
    }

    private String decode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String encodeQuery(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String encodeFragment(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private enum PayloadFormat {
        BASE64,
        PLAIN
    }
}
