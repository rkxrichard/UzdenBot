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
import java.util.function.Supplier;

@Slf4j
@Component
public class ThreeXuiClient {

    private final RestClient rest;
    private final XuiProperties props;

    // cookie вида "3x-ui=...." или "session=...."
    private volatile String authCookie;
    private final ReentrantLock loginLock = new ReentrantLock();

    private static final String LOGIN_PATH = "/login";
    private static final String ADD_CLIENT_PATH = "/panel/api/inbounds/addClient";

    private static final List<String> GET_INBOUND_CANDIDATES = List.of(
            "/panel/api/inbounds/get/%d",
            "/panel/api/inbounds/get/%d/",
            "/panel/api/inbounds/get/%d?full=true"
    );

    private static final List<String> LIST_INBOUNDS_CANDIDATES = List.of(
            "/panel/api/inbounds/list",
            "/panel/api/inbounds/list/"
    );

    private static final List<String> UPDATE_CLIENT_CANDIDATES = List.of(
            "/panel/api/inbounds/updateClient/%s",
            "/panel/api/inbounds/updateClient/%s/"
    );

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

    /**
     * Добавляет клиента в inbound через API 3x-ui. Важно: в 3x-ui client.id ДОЛЖЕН быть валидным UUID.
     * Делает запрос ровно как web-панель: application/x-www-form-urlencoded с полями id и settings.
     */
    public void addClient(long inboundId, UUID clientUuid, String email) {
        ensureLoggedIn();

        // settings={"clients":[{...}]}
        String subId = randomSubId(16);
        String settingsJson = buildAddClientSettingsJson(clientUuid.toString(), email, subId);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("id", String.valueOf(inboundId));
        form.add("settings", settingsJson);

        String body = postFormWithAuth(ADD_CLIENT_PATH, form);
        ApiEnvelope env = ApiEnvelope.parse(body);

        if (!env.success) {
            throw new IllegalStateException("3x-ui addClient failed: " + (env.msg == null ? body : env.msg));
        }

        // verify: реально ли клиент появился в inbound
        if (!clientExistsInInbound(inboundId, clientUuid.toString(), email)) {
            throw new IllegalStateException("3x-ui addClient returned success but client not persisted (uuid/email not found in inbound)");
        }
    }

    public void disableClient(long inboundId, UUID clientUuid) {
        ensureLoggedIn();

        // минимальный JSON (без ObjectMapper), достаточный для updateClient в большинстве сборок
        String bodyJson = "{"
                + "\"inboundId\":" + inboundId + ","
                + "\"client\":{\"id\":\"" + escapeJson(clientUuid.toString()) + "\",\"enable\":false}"
                + "}";

        HttpClientErrorException last = null;
        for (String pattern : UPDATE_CLIENT_CANDIDATES) {
            String path = String.format(pattern, clientUuid);
            try {
                postJsonWithAuth(path, bodyJson);
                return;
            } catch (HttpClientErrorException e) {
                last = e;
                if (isAuthError(e)) {
                    reloginAndRetry(() -> {
                        postJsonWithAuth(path, bodyJson);
                        return "";
                    });
                    return;
                }
            }
        }
        if (last != null) throw last;
        throw new IllegalStateException("No updateClient endpoint candidates matched");
    }

    /**
     * Возвращает inbound JSON (как строку) без envelope-обертки.
     */
    public String getInbound(long inboundId) {
        ensureLoggedIn();

        HttpClientErrorException last = null;
        for (String pattern : GET_INBOUND_CANDIDATES) {
            String path = String.format(pattern, inboundId);
            try {
                String body = getWithAuth(path);
                String unwrapped = unwrapObjIfNeeded(body);
                if (!looksLikeFullInbound(unwrapped)) {
                    String fromList = tryFindInboundFromList(inboundId);
                    if (fromList != null) return fromList;
                }
                return unwrapped;
            } catch (HttpClientErrorException e) {
                last = e;
                if (isAuthError(e)) {
                    String body = reloginAndRetry(() -> getWithAuth(path));
                    String unwrapped = unwrapObjIfNeeded(body);
                    if (!looksLikeFullInbound(unwrapped)) {
                        String fromList = tryFindInboundFromList(inboundId);
                        if (fromList != null) return fromList;
                    }
                    return unwrapped;
                }
            }
        }
        if (last != null) throw last;
        throw new IllegalStateException("No getInbound endpoint candidates matched");
    }

    /* ============================ helpers ============================ */

    private boolean clientExistsInInbound(long inboundId, String uuid, String email) {
        String inbound = getInbound(inboundId);
        if (inbound == null) return false;

        // settings и streamSettings в 3x-ui часто лежат строкой, поэтому просто проверяем наличие uuid/email в тексте
        String hay = inbound;
        return hay.contains(uuid) && (email == null || email.isBlank() || hay.contains(email));
    }

    private static boolean looksLikeFullInbound(String inboundJson) {
        if (inboundJson == null) return false;
        String t = inboundJson.trim();
        return t.contains("\"streamSettings\"") || t.contains("\"realitySettings\"");
    }

    private String tryFindInboundFromList(long inboundId) {
        for (String path : LIST_INBOUNDS_CANDIDATES) {
            try {
                String body = getWithAuth(path);
                String unwrapped = unwrapObjIfNeeded(body);
                // list: {"success":true,"obj":[{...},{...}]}
                String arr = unwrapped == null ? null : unwrapped.trim();
                if (arr == null || !arr.startsWith("[")) continue;

                // грубый поиск по "id":<inboundId>
                // найдём объект где "id": inboundId и вытащим его JSON
                int idx = indexOfId(arr, inboundId);
                if (idx >= 0) {
                    // от idx назад до '{' и вперёд до matching '}'
                    int objStart = arr.lastIndexOf('{', idx);
                    if (objStart >= 0) {
                        String obj = JsonMini.extractJsonValue(arr, objStart);
                        if (obj != null && obj.trim().startsWith("{")) return obj;
                    }
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    private static int indexOfId(String jsonArray, long id) {
        // ищем "id":<id> или "id": <id>
        String needle = "\"id\":" + id;
        int i = jsonArray.indexOf(needle);
        if (i >= 0) return i;
        needle = "\"id\": " + id;
        return jsonArray.indexOf(needle);
    }

    /**
     * 3x-ui API часто возвращает envelope: {"success":true,"obj":{...}}.
     * Мы вытаскиваем obj/data/inbound если они есть.
     */
    private static String unwrapObjIfNeeded(String body) {
        if (body == null || body.isBlank()) return body;
        String t = body.trim();
        if (!t.startsWith("{") && !t.startsWith("[")) return body;

        // если это array — уже ок
        if (t.startsWith("[")) return t;

        // envelope: попробуем obj, data, inbound
        String obj = JsonMini.extractFieldValue(t, "obj");
        if (obj != null) return obj;
        String data = JsonMini.extractFieldValue(t, "data");
        if (data != null) return data;
        String inbound = JsonMini.extractFieldValue(t, "inbound");
        if (inbound != null) return inbound;

        return body;
    }

    /* ============================ auth ============================ */

    private void ensureLoggedIn() {
        if (authCookie != null && !authCookie.isBlank()) return;

        loginLock.lock();
        try {
            if (authCookie != null && !authCookie.isBlank()) return;
            doLogin();
        } finally {
            loginLock.unlock();
        }
    }

    private void doLogin() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", Objects.requireNonNull(props.username(), "xui.username is required"));
        form.add("password", Objects.requireNonNull(props.password(), "xui.password is required"));
        // twoFactorCode отсутствует — оставляем пустым (если 2FA выключен)
        form.add("twoFactorCode", "");

        var resp = rest.post()
                .uri(url(LOGIN_PATH))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toEntity(String.class);

        String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        if (setCookie == null || setCookie.isBlank()) {
            // некоторые форки отдают успех/ошибку в body при 200
            String body = resp.getBody();
            ApiEnvelope env = ApiEnvelope.parse(body);
            if (env.msg != null && !env.msg.isBlank()) {
                throw new IllegalStateException("3x-ui login failed: " + env.msg);
            }
            throw new IllegalStateException("3x-ui login failed: Set-Cookie not found");
        }

        // возьмем только cookie до ';'
        String cookie = setCookie.split(";", 2)[0].trim();
        this.authCookie = cookie;
        log.info("3x-ui login ok, cookie name={}", cookie.contains("=") ? cookie.substring(0, cookie.indexOf('=')) : cookie);
    }

    private boolean isAuthError(HttpClientErrorException e) {
        return e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN;
    }

    private <T> T reloginAndRetry(Supplier<T> call) {
        loginLock.lock();
        try {
            // сбросим и перелогинимся
            this.authCookie = null;
            doLogin();
            return call.get();
        } finally {
            loginLock.unlock();
        }
    }

    /* ============================ http ============================ */

    private String getWithAuth(String path) {
        return rest.get()
                .uri(url(path))
                .header(HttpHeaders.COOKIE, authCookie)
                .retrieve()
                .body(String.class);
    }

    private String postFormWithAuth(String path, MultiValueMap<String, String> form) {
        return rest.post()
                .uri(url(path))
                .header(HttpHeaders.COOKIE, authCookie)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
    }

    private void postJsonWithAuth(String path, String jsonBody) {
        rest.post()
                .uri(url(path))
                .header(HttpHeaders.COOKIE, authCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .retrieve()
                .toBodilessEntity();
    }

    /* ============================ json mini ============================ */

    private static final class ApiEnvelope {
        final boolean success;
        final String msg;

        private ApiEnvelope(boolean success, String msg) {
            this.success = success;
            this.msg = msg;
        }

        static ApiEnvelope parse(String body) {
            if (body == null) return new ApiEnvelope(false, null);
            String t = body.trim();
            if (!t.startsWith("{")) return new ApiEnvelope(true, null); // не JSON — считаем успешным
            String s = JsonMini.extractFieldValue(t, "success");
            boolean ok = "true".equalsIgnoreCase(trimQuotes(s));
            String msg = trimQuotes(JsonMini.extractFieldValue(t, "msg"));
            return new ApiEnvelope(ok, msg);
        }
    }

    private static final class JsonMini {

        static String extractFieldValue(String jsonObject, String field) {
            if (jsonObject == null) return null;
            String t = jsonObject.trim();
            if (!t.startsWith("{")) return null;

            int idx = indexOfField(t, field);
            if (idx < 0) return null;

            int colon = t.indexOf(':', idx);
            if (colon < 0) return null;

            int valStart = colon + 1;
            while (valStart < t.length() && Character.isWhitespace(t.charAt(valStart))) valStart++;

            return extractJsonValue(t, valStart);
        }

        static int indexOfField(String json, String field) {
            // ищем "field"
            String needle = "\"" + field + "\"";
            int i = json.indexOf(needle);
            if (i < 0) return -1;
            return i + needle.length();
        }

        /**
         * Возвращает JSON-значение начиная с pos (может быть объект, массив, строка, число, true/false/null).
         */
        static String extractJsonValue(String s, int pos) {
            if (s == null || pos < 0 || pos >= s.length()) return null;
            int i = pos;
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= s.length()) return null;

            char c = s.charAt(i);
            if (c == '"') {
                int end = findStringEnd(s, i);
                if (end < 0) return null;
                return s.substring(i, end + 1);
            }
            if (c == '{' || c == '[') {
                int end = findMatchingBracket(s, i);
                if (end < 0) return null;
                return s.substring(i, end + 1);
            }
            // primitive
            int end = i;
            while (end < s.length() && ",}\n\r\t".indexOf(s.charAt(end)) < 0) end++;
            return s.substring(i, end).trim();
        }

        static int findStringEnd(String s, int startQuote) {
            boolean esc = false;
            for (int i = startQuote + 1; i < s.length(); i++) {
                char c = s.charAt(i);
                if (esc) {
                    esc = false;
                    continue;
                }
                if (c == '\\') {
                    esc = true;
                    continue;
                }
                if (c == '"') return i;
            }
            return -1;
        }

        static int findMatchingBracket(String s, int start) {
            char open = s.charAt(start);
            char close = (open == '{') ? '}' : ']';
            int depth = 0;
            boolean inStr = false;
            boolean esc = false;

            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);

                if (inStr) {
                    if (esc) {
                        esc = false;
                        continue;
                    }
                    if (c == '\\') {
                        esc = true;
                        continue;
                    }
                    if (c == '"') {
                        inStr = false;
                    }
                    continue;
                }

                if (c == '"') {
                    inStr = true;
                    continue;
                }
                if (c == open) depth++;
                else if (c == close) {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            return -1;
        }
    }

    /* ============================ misc ============================ */

    private static String randomSubId(int len) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        var r = new java.security.SecureRandom();
        var sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }

    private static String buildAddClientSettingsJson(String uuid, String email, String subId) {
        // максимально похоже на то, что шлёт панель (минимально нужные поля)
        return "{\"clients\":[{"
                + "\"id\":\"" + escapeJson(uuid) + "\","
                + "\"flow\":\"xtls-rprx-vision\","
                + "\"email\":\"" + escapeJson(email) + "\","
                + "\"limitIp\":0,"
                + "\"totalGB\":0,"
                + "\"expiryTime\":0,"
                + "\"enable\":true,"
                + "\"subId\":\"" + escapeJson(subId) + "\","
                + "\"reset\":0"
                + "}]}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\");
    }

    private static String trimQuotes(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) return t.substring(1, t.length() - 1);
        return t;
    }
}