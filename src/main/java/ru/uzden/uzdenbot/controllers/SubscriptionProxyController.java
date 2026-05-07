package ru.uzden.uzdenbot.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.uzden.uzdenbot.entities.VpnKey;
import ru.uzden.uzdenbot.services.SubscriptionProxyService;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/sub")
@RequiredArgsConstructor
public class SubscriptionProxyController {

    private final SubscriptionProxyService subscriptionProxyService;

    @GetMapping("/{backend}/{subId}")
    public ResponseEntity<String> getSubscription(
            @PathVariable String backend,
            @PathVariable String subId,
            @RequestHeader(value = "Host", required = false) String host,
            @RequestHeader(value = "X-Forwarded-Proto", required = false) String forwardedProto
    ) {
        if (!subscriptionProxyService.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        VpnKey.Backend resolvedBackend;
        try {
            resolvedBackend = subscriptionProxyService.parseBackend(backend);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        SubscriptionProxyService.ProxiedSubscription proxied = subscriptionProxyService.fetchAndRewrite(resolvedBackend, subId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(subscriptionProxyService.profileTitle() + ".txt", StandardCharsets.UTF_8)
                .build()
                .toString());
        headers.set("Profile-Title", subscriptionProxyService.profileTitleHeaderValue());
        copyIfPresent(proxied.upstreamHeaders(), headers, "Subscription-Userinfo");
        copyIfPresent(proxied.upstreamHeaders(), headers, "Profile-Update-Interval");
        copyIfPresent(proxied.upstreamHeaders(), headers, "Support-Url");
        copyIfPresent(proxied.upstreamHeaders(), headers, "Announce");
        if (host != null && !host.isBlank()) {
            String scheme = (forwardedProto == null || forwardedProto.isBlank()) ? "https" : forwardedProto;
            headers.set("Profile-Web-Page-Url", scheme + "://" + host + "/sub/" + backend + "/" + subId);
        }

        return new ResponseEntity<>(proxied.body(), headers, HttpStatus.OK);
    }

    private void copyIfPresent(HttpHeaders source, HttpHeaders target, String name) {
        String value = source.getFirst(name);
        if (value != null && !value.isBlank()) {
            target.set(name, value);
        }
    }
}
