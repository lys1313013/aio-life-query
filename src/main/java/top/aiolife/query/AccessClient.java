package top.aiolife.query;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class AccessClient {
    private final BoundedHttp http;
    private final QueryProperties properties;
    public AccessClient(BoundedHttp http, QueryProperties properties) { this.http = http; this.properties = properties; }
    public record Access(String userId, String appId, String credentialId, String credentialType, long expiresAt) {
        public boolean sameAuthority(Access other) {
            return other != null && userId.equals(other.userId) && appId.equals(other.appId)
                    && credentialId.equals(other.credentialId) && credentialType.equals(other.credentialType);
        }
    }

    public Access evaluate(String token) {
        if (token == null || token.isEmpty() || token.length() > 4096 || !token.matches("[!-~]+")) {
            throw new QueryException("AUTH_REQUIRED", 401, "需要 AIO Life 登录 Token 或 API Key");
        }
        var response = http.post(properties.accessUrl(), Map.of("X-AIO-Query-Service-Key", properties.serviceKey(),
                        "Authorization", "Bearer " + token), Map.of("dataset", "time_record"), 16384, 2);
        var body = response.body();
        if (response.status() == 401) throw new QueryException("AUTH_REQUIRED", 401, "AIO Life 凭据已失效");
        if (response.status() >= 500) throw new QueryException("UPSTREAM_UNAVAILABLE", 503, "AIO Life 认证服务不可用");
        if (body != null && "2001".equals(body.path("rscode").asText())) {
            throw new QueryException("SECONDARY_LOCK_REQUIRED", 403, "请先在 AIO Life 解锁时迹及 AI 工具访问");
        }
        if (response.status() != 200 || body == null || !"0".equals(body.path("rscode").asText())) throw QueryException.denied();
        var data = body.path("data");
        if (!"ai_time_reader".equals(data.path("role").asText()) || !data.path("scopes").isArray()
                || data.path("scopes").size() != 1 || !"time.read".equals(data.path("scopes").get(0).asText())
                || !data.path("userId").asText().matches("[0-9]{1,19}")
                || !"aio-life-query".equals(data.path("appId").asText())
                || !data.path("credentialId").asText().matches("[a-f0-9]{32}")
                || !java.util.Set.of("login", "api_key").contains(data.path("credentialType").asText())
                || data.path("expiresAt").asLong() <= Instant.now().getEpochSecond()) throw QueryException.denied();
        return new Access(data.path("userId").asText(), data.path("appId").asText(), data.path("credentialId").asText(),
                data.path("credentialType").asText(), data.path("expiresAt").asLong());
    }
}
