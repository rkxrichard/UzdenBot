package ru.uzden.uzdenbot.xui;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import ru.uzden.uzdenbot.config.XuiProperties;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class ThreeXuiClient {

    private final RestClient rest;
    private final XuiProperties props;

    // cookie вида "3x-ui=...." или "session=...."
    private volatile String authCookie;
    private final ReentrantLock loginLock = new ReentrantLock();

    // В разных форках/версиях могут отличаться пути для getInbound/del/update
    // addClient обычно стабилен.
    private static final String LOGIN_PATH = "/login/";
    private static final String ADD_CLIENT_PATH = "/panel/api/inbounds/addClient";

    // Попробуем несколько вариантов get inbound
    private static final List<String> GET_INBOUND_CANDIDATES = List.of(
            "/panel/api/inbounds/get/%d",
            "/panel/api/inbounds/get/%d/",

            // иногда бывает list + фильтрация, но это уже другой код
            // оставляем как кандидаты, если у тебя окажется так:
            "/panel/api/inbounds/get/%d?full=true"
    );

    // update enable (часто так)
    private static final List<String> UPDATE_CLIENT_CANDIDATES = List.of(
            "/panel/api/inbounds/updateClient/%s",
            "/panel/api/inbounds/updateClient/%s/"
    );

    // disable через updateClient: enable=false
    // (удаление можешь добавить позже, когда подтвердим endpoint)

    public ThreeXuiClient(RestClient.Builder builder, XuiProperties props) {
        this.props = props;

        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        rf.setReadTimeout((int) Duration.ofSeconds(10).toMillis());

        this.rest = builder
                .baseUrl(Objects.requireNonNull(props.baseUrl(), "xui.base-url is required"))
                .requestFactory(rf)
                .build();
    }



    /* ============================ public API ============================ */

    public void addClient(long inboundId, UUID clientUuid, String email) {
        ensureLoggedIn();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inboundId", inboundId);

        Map<String, Object> client = new LinkedHashMap<>();
        client.put("id", clientUuid.toString());
        client.put("email", email);
        client.put("enable", true);
        client.put("expiryTime", 0);
        client.put("flow", "xtls-rprx-vision");
        client.put("limitIp", 0);
        client.put("totalGB", 0);

        body.put("client", client);

        // addClient идемпотентность: если уже есть такой UUID, панель может вернуть ошибку.
        // Это нормально — твоя логика в сервисе должна уметь с этим жить (recover).
        postWithAuth(ADD_CLIENT_PATH, body);
    }

    public void disableClient(long inboundId, UUID clientUuid) {
        ensureLoggedIn();

        // Во многих 3x-ui updateClient принимает payload inboundId + client с enable=false
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inboundId", inboundId);

        Map<String, Object> client = new LinkedHashMap<>();
        client.put("id", clientUuid.toString());
        client.put("enable", false);

        body.put("client", client);

        // Попробуем несколько вариантов endpoint
        HttpClientErrorException last = null;
        for (String pattern : UPDATE_CLIENT_CANDIDATES) {
            String path = String.format(pattern, clientUuid);
            try {
                postWithAuth(path, body);
                return;
            } catch (HttpClientErrorException e) {
                last = e;
                // если 401/403 — перелогинимся и повторим на этом же path
                if (isAuthError(e)) {
                    reloginAndRetry(() -> postWithAuth(path, body));
                    return;
                }
                // иначе пробуем следующий кандидат
            }
        }
        if (last != null) throw last;
        throw new IllegalStateException("No updateClient endpoint candidates matched");
    }

    /**
     * Возвращает inbound JSON (как строку). Именно этот JSON ты потом парсишь в VlessLinkBuilder.
     */
    public String getInbound(long inboundId) {
        ensureLoggedIn();

        HttpClientErrorException last = null;
        for (String pattern : GET_INBOUND_CANDIDATES) {
            String path = String.format(pattern, inboundId);
            try {
                return getWithAuth(path);
            } catch (HttpClientErrorException e) {
                last = e;
                if (isAuthError(e)) {
                    // ре-логин и повтор именно этого path
                    return reloginAndRetry(() -> getWithAuth(path));
                }
                // пробуем следующий кандидат
            }
        }
        if (last != null) throw last;
        throw new IllegalStateException("No getInbound endpoint candidates matched");
    }

    /* ============================ auth ============================ */

    private void ensureLoggedIn() {
        if (authCookie != null) return;
        login();
    }

    private void login() {
        loginLock.lock();
        try {
            if (authCookie != null) return;

            Map<String, Object> body = Map.of(
                    "username", props.username(),
                    "password", props.password()
            );

            var resp = rest.post()
                    .uri(LOGIN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);

            String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
            if (setCookie == null || setCookie.isBlank()) {
                throw new IllegalStateException("3x-ui login failed: Set-Cookie not found");
            }

            // Берём первый cookie до ';'
            authCookie = setCookie.split(";", 2)[0];
            log.info("3x-ui login ok, cookieName={}", authCookie.split("=", 2)[0]);

        } finally {
            loginLock.unlock();
        }
    }

    private <T> T reloginAndRetry(SupplierWithException<T> supplier) {
        // сбросим cookie и перелогинимся
        authCookie = null;
        login();
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void reloginAndRetry(RunnableWithException runnable) {
        authCookie = null;
        login();
        try {
            runnable.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isAuthError(HttpClientErrorException e) {
        HttpStatus s = (HttpStatus) e.getStatusCode();
        return s == HttpStatus.UNAUTHORIZED || s == HttpStatus.FORBIDDEN;
    }

    /* ============================ HTTP helpers ============================ */

    private void postWithAuth(String path, Object body) {
        try {
            rest.post()
                    .uri(path)
                    .header(HttpHeaders.COOKIE, authCookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (isAuthError(e)) {
                reloginAndRetry(() -> postWithAuth(path, body));
                return;
            }
            throw e;
        }
    }

    private String getWithAuth(String path) {
        try {
            return rest.get()
                    .uri(path)
                    .header(HttpHeaders.COOKIE, authCookie)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException e) {
            if (isAuthError(e)) {
                return reloginAndRetry(() -> getWithAuth(path));
            }
            throw e;
        }
    }

    /* ============================ small functional ============================ */

    @FunctionalInterface
    private interface SupplierWithException<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface RunnableWithException {
        void run() throws Exception;
    }
}
