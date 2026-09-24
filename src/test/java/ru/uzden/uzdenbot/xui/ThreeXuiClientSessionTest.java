package ru.uzden.uzdenbot.xui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import ru.uzden.uzdenbot.config.XuiProperties;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Эмулирует поведение 3x-ui v2.x: запрос к /panel/api/* с невалидной сессией → пустой 404
 * (а не 401). Клиент должен перелогиниться и повторить запрос.
 */
class ThreeXuiClientSessionTest {

    private static final String BASE_PATH = "/secretpath";
    private static final String INBOUND_JSON =
            "{\"id\":1,\"protocol\":\"vless\",\"settings\":\"{\\\"clients\\\":[]}\",\"streamSettings\":\"{}\"}";

    private HttpServer server;
    private final AtomicInteger logins = new AtomicInteger();
    private volatile String validCookie;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(BASE_PATH + "/login", ex -> {
            int n = logins.incrementAndGet();
            validCookie = "3x-ui=session" + n;
            ex.getResponseHeaders().add("Set-Cookie", validCookie + "; Path=/; HttpOnly");
            send(ex, 200, "{\"success\":true,\"msg\":\"ok\",\"obj\":null}");
        });
        server.createContext(BASE_PATH + "/panel/api/inbounds/get/1", ex -> {
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            if (cookie == null || !cookie.equals(validCookie)) {
                send(ex, 404, "");
                return;
            }
            send(ex, 200, "{\"success\":true,\"msg\":\"\",\"obj\":" + INBOUND_JSON + "}");
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ThreeXuiClient client() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        XuiProperties props = new XuiProperties(base, BASE_PATH, base + "/sub", "u", "p", 1, 0, List.of(1L));
        return new ThreeXuiClient(RestClient.builder(), props, new ObjectMapper());
    }

    @Test
    void reloginsWhenPanelAnswers404ForExpiredSession() {
        ThreeXuiClient c = client();

        String first = c.getInbound(1);
        assertTrue(first.contains("\"protocol\":\"vless\""));
        assertEquals(1, logins.get());

        // панель перезапустилась / сессия истекла — старая кука больше не валидна
        validCookie = "3x-ui=rotated";

        String second = c.getInbound(1);
        assertTrue(second.contains("\"protocol\":\"vless\""));
        assertEquals(2, logins.get(), "должен был перелогиниться ровно один раз");
    }

    @Test
    void genuine404IsStillPropagatedAfterSingleRelogin() {
        server.createContext(BASE_PATH + "/panel/api/inbounds/list", ex -> send(ex, 404, ""));
        ThreeXuiClient c = client();
        // inbound 99 не существует ни по одному пути → 404 даже после свежего логина
        assertThrows(HttpClientErrorException.NotFound.class, () -> c.getInbound(99));
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }
}
