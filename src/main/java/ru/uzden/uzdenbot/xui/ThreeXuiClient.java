package ru.uzden.uzdenbot.xui;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import ru.uzden.uzdenbot.config.XuiProperties;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class ThreeXuiClient {

    private static final com.fasterxml.jackson.databind.ObjectMapper OM = new com.fasterxml.jackson.databind.ObjectMapper();

    private final RestClient rest;
    private final XuiProperties props;

    // cookie вида "3x-ui=...." или "session=...."
    private volatile String authCookie;
    private final ReentrantLock loginLock = new ReentrantLock();

    // В разных форках/версиях могут отличаться пути для getInbound/del/update.
    // addClient обычно стабилен.
    private static final String LOGIN_PATH = "/login";
    private static final String ADD_CLIENT_PATH = "/panel/api/inbounds/addClient";

    // Попробуем несколько вариантов get inbound
    private static final List<String> GET_INBOUND_CANDIDATES = List.of(
            "/panel/api/inbounds/get/%d",
            "/panel/api/inbounds/get/%d/",

            // иногда бывает list + фильтрация, но это уже другой код
            // оставляем как кандидаты, если у тебя окажется так:
            "/panel/api/inbounds/get/%d?full=true"
    );

    private static final List<String> LIST_INBOUNDS_CANDIDATES = List.of(
            "/panel/api/inbounds/list",
            "/panel/api/inbounds/list/"
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

    private String url(String path) {
        String bp = props.basePath();
        if (bp == null) bp = "";
        bp = bp.trim();
        if (!bp.isEmpty() && !bp.startsWith("/")) bp = "/" + bp;
        if (bp.endsWith("/")) bp = bp.substring(0, bp.length() - 1);

        String p = (path == null) ? "" : path.trim();
        if (!p.startsWith("/")) p = "/" + p;
        return bp + p;
    }



    /* ============================ public API ============================ */

    public void addClient(long inboundId, UUID clientUuid, String email) {
        ensureLoggedIn();

        // В твоей сборке (2.8.9) фронт делает именно так:
        // POST {basePath}/panel/api/inbounds/addClient
        // Content-Type: application/x-www-form-urlencoded
        // body: id=<inboundId>&settings=<json>
        // где settings обычно содержит объект с clients: [{...}]
        // Делаем payload максимально похожим на то, что шлёт web‑панель, чтобы 3x-ui
        // точно создал клиента (в разных форках есть валидации на поля).
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("id", clientUuid.toString());
        client.put("security", "");
        client.put("password", "");
        client.put("flow", "xtls-rprx-vision");
        client.put("email", email);
        client.put("limitIp", 0);
        client.put("totalGB", 0);
        client.put("expiryTime", 0);
        client.put("enable", true);
        client.put("tgId", 0);
        client.put("subId", randomSubId());
        client.put("comment", "");
        client.put("reset", 0);

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("clients", List.of(client));

        String settingsJson;
        try {
            settingsJson = OM.writeValueAsString(settings);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize addClient settings", e);
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("id", String.valueOf(inboundId));
        form.add("settings", settingsJson);

        postFormWithAuth(ADD_CLIENT_PATH, form);
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
                String body = getWithAuth(path);
                String unwrapped = unwrapObjIfNeeded(body);
                // если эндпоинт вернул неполный объект (без streamSettings) — попробуем list
                if (!looksLikeFullInbound(unwrapped)) {
                    String fromList = tryFindInboundFromList(inboundId);
                    if (fromList != null) return fromList;
                }
                return unwrapped;
            } catch (HttpClientErrorException e) {
                last = e;
                if (isAuthError(e)) {
                    // ре-логин и повтор именно этого path
                    String body = reloginAndRetry(() -> getWithAuth(path));
                    String unwrapped = unwrapObjIfNeeded(body);
                    if (!looksLikeFullInbound(unwrapped)) {
                        String fromList = tryFindInboundFromList(inboundId);
                        if (fromList != null) return fromList;
                    }
                    return unwrapped;
                }
                // пробуем следующий кандидат
            }
        }
        if (last != null) throw last;
        throw new IllegalStateException("No getInbound endpoint candidates matched");
    }

    private static boolean looksLikeFullInbound(String inboundJson) {
        if (inboundJson == null) return false;
        String t = inboundJson.trim();
        // быстрый хак: полная запись обычно содержит streamSettings или хотя бы realitySettings
        return t.contains("\"streamSettings\"") || t.contains("\"realitySettings\"");
    }

    private String tryFindInboundFromList(long inboundId) {
        for (String path : LIST_INBOUNDS_CANDIDATES) {
            try {
                String body = getWithAuth(path);
                String unwrapped = unwrapObjIfNeeded(body);
                // list обычно: {"success":true,"obj":[{...},{...}]}
                var node = OM.readTree(unwrapped);
                if (node.isArray()) {
                    for (var it : node) {
                        if (it != null && it.has("id") && it.get("id").asLong() == inboundId) {
                            return it.toString();
                        }
                    }
                }
            } catch (Exception ignore) {
                // пробуем следующий кандидат
            }
        }
        return null;
    }

    /**
     * 3x-ui API часто возвращает обёртку вида: {"success":true,"obj":{...}}
     * или {"success":true,"data":{...}}.
     * Для построения ссылки нам нужен именно объект inbound.
     */
    private static String unwrapObjIfNeeded(String body) {
        if (body == null || body.isBlank()) return body;
        String t = body.trim();
        if (!t.startsWith("{") && !t.startsWith("[")) return body;
        try {
            com.fasterxml.jackson.databind.JsonNode root = OM.readTree(t);
            // наиболее частый кейс
            com.fasterxml.jackson.databind.JsonNode obj = root.get("obj");
            if (obj != null && !obj.isNull() && !obj.isMissingNode()) {
                return obj.toString();
            }
            // некоторые форки используют data
            com.fasterxml.jackson.databind.JsonNode data = root.get("data");
            if (data != null && !data.isNull() && !data.isMissingNode()) {
                return data.toString();
            }
            // иногда inbound лежит под inbound
            com.fasterxml.jackson.databind.JsonNode inbound = root.get("inbound");
            if (inbound != null && !inbound.isNull() && !inbound.isMissingNode()) {
                return inbound.toString();
            }
        } catch (Exception ignore) {
            // если не парсится — вернём как есть
        }
        return body;
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

            // 3x-ui forks differ: some expect JSON, some expect form-urlencoded.
            // We'll try JSON first, then fallback to form.

            var jsonBody = Map.of(
                    "username", props.username(),
                    "password", props.password()
            );

            var resp = rest.post()
                    .uri(url(LOGIN_PATH))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(jsonBody)
                    .retrieve()
                    .toEntity(String.class);

            String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
            if (setCookie == null || setCookie.isBlank()) {
                // fallback: form
                String form = "username=" + encode(props.username()) + "&password=" + encode(props.password());
                resp = rest.post()
                        .uri(url(LOGIN_PATH))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(form)
                        .retrieve()
                        .toEntity(String.class);
                setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
            }

            if (setCookie == null || setCookie.isBlank()) {
                throw new IllegalStateException("3x-ui login failed: Set-Cookie not found. status=" + resp.getStatusCode() + ", body=" + resp.getBody());
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
                    .uri(url(path))
                    .header(HttpHeaders.COOKIE, cookieHeader())
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
                    .uri(url(path))
                    .header(HttpHeaders.COOKIE, cookieHeader())
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

    private static String encode(String s) {
        if (s == null) return "";
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String randomSubId() {
        // субайди в 3x-ui — это просто строка; в UI обычно 16-20 символов.
        // Главное — уникальность в рамках inbound.
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private void postFormWithAuth(String path, MultiValueMap<String, String> form) {
        try {
            String body = rest.post()
                    .uri(url(path))
                    .header(HttpHeaders.COOKIE, cookieHeader())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            // 3x-ui часто отвечает 200 OK даже при ошибке, но success=false
            if (body != null && body.trim().startsWith("{")) {
                Map<String, Object> m = OM.readValue(body, Map.class);
                Object success = m.get("success");
                if (success instanceof Boolean b && !b) {
                    throw new IllegalStateException("3x-ui API error: " + m.get("msg"));
                }
            }
        } catch (HttpClientErrorException e) {
            if (isAuthError(e)) {
                reloginAndRetry(() -> postFormWithAuth(path, form));
                return;
            }
            throw e;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("3x-ui request failed", e);
        }
    }

    private String cookieHeader() {
        // фронт панельки обычно шлёт ещё lang=ru-RU; это безопасно и иногда влияет на поведение.
        if (authCookie == null || authCookie.isBlank()) return "lang=ru-RU";
        if (authCookie.startsWith("lang=")) return authCookie;
        return "lang=ru-RU; " + authCookie;
    }
}
