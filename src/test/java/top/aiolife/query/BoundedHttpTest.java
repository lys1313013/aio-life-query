package top.aiolife.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BoundedHttpTest {
    @Test void preservesPlainTextUnauthorizedStatusFromAioLife() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth", exchange -> {
            byte[] body = "未授权: token无效".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var response = new BoundedHttp(new ObjectMapper()).post(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/auth"), Map.of(), Map.of(), 16384, 2);
            assertEquals(401, response.status()); assertNull(response.body());
        } finally { server.stop(0); }
    }
    @Test void rejectsLargeBodyWhileReadingAndDoesNotFollowRedirect() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/large", exchange -> {
            byte[] body = ("{\"data\":\"" + "x".repeat(10000) + "\"}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/large");
            exchange.sendResponseHeaders(302, 2);
            try (var output = exchange.getResponseBody()) { output.write("{}".getBytes()); }
        });
        server.start();
        try {
            var http = new BoundedHttp(new ObjectMapper());
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThrows(QueryException.class, () -> http.post(URI.create(base + "/large"), Map.of(), Map.of(), 100, 2));
            assertEquals(302, http.post(URI.create(base + "/redirect"), Map.of(), Map.of(), 100, 2).status());
        } finally { server.stop(0); }
    }
}
