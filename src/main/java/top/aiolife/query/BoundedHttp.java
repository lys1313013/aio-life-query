package top.aiolife.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** 限制接收字节，截止时间包含响应正文，不跟随重定向转发凭据。 */
@Component
public class BoundedHttp {
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public BoundedHttp(ObjectMapper json) { this.json = json; }
    public record Response(int status, JsonNode body) {}

    public Response post(URI uri, Map<String, String> headers, Object body, int maxBytes, int timeoutSeconds) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json").header("Accept-Encoding", "identity")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            headers.forEach(builder::header);
            pending = client.sendAsync(builder.build(), info -> new LimitedBody(maxBytes));
            var response = pending.get(timeoutSeconds, TimeUnit.SECONDS);
            JsonNode parsed;
            try { parsed = json.readTree(response.body()); }
            catch (java.io.IOException error) {
                if (response.statusCode() == 401 || response.statusCode() == 403) parsed = null;
                else throw error;
            }
            return new Response(response.statusCode(), parsed);
        } catch (Exception e) {
            if (pending != null) pending.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new QueryException("UPSTREAM_UNAVAILABLE", 503, "查询依赖超时、不可用或响应超限");
        }
    }

    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int max;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int max) { this.max = max; }
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > max - out.size()) {
                    subscription.cancel(); body.completeExceptionally(new IllegalStateException("response limit")); return;
                }
                byte[] bytes = new byte[chunk.remaining()]; chunk.get(bytes); out.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(out.toByteArray()); }
    }
}
