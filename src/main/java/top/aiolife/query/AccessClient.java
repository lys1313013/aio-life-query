package top.aiolife.query;

import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

@Component
public class AccessClient {
    private final BoundedHttp http;
    private final QueryProperties properties;
    public AccessClient(BoundedHttp http, QueryProperties properties) { this.http = http; this.properties = properties; }
    public record Access(String userId, String appId, String credentialId, String credentialType, long expiresAt) {}

    public Access evaluate(String token) { return evaluate(token, "time_record"); }

    public Access evaluate(String token, String dataset) {
        var definition = "catalog".equals(dataset) ? null : QueryDatasets.named(dataset);
        String role = definition == null ? "ai_query_catalog" : definition.role();
        String scope = definition == null ? "query.catalog" : definition.scope();
        if (token == null || token.isEmpty() || token.length() > 4096 || !token.matches("[!-~]+")) {
            throw new QueryException("AUTH_REQUIRED", 401, "需要 AIO Life 登录 Token 或 API Key");
        }
        var response = http.post(properties.accessUrl(), Map.of("X-AIO-Query-Service-Key", properties.serviceKey(),
                        "Authorization", "Bearer " + token), Map.of("dataset", dataset), 16384, 3);
        var body = response.body();
        if (response.status() == 401) throw new QueryException("AUTH_REQUIRED", 401, "AIO Life 凭据已失效");
        if (response.status() >= 500) throw new QueryException("UPSTREAM_UNAVAILABLE", 503, "AIO Life 认证服务不可用");
        String code = businessCode(body);
        if ("2001".equals(code)) {
            throw new QueryException("SECONDARY_LOCK_REQUIRED", 403, "请先在 AIO Life 解锁对应业务及 AI 工具访问");
        }
        if (response.status() != 200 || body == null || !"0".equals(code)) throw QueryException.denied();
        var data = body.path("data");
        if (!role.equals(data.path("role").asText()) || !data.path("scopes").isArray()
                || data.path("scopes").size() != 1 || !scope.equals(data.path("scopes").get(0).asText())
                || !data.path("userId").asText().matches("[0-9]{1,19}")
                || !"aio-life-query".equals(data.path("appId").asText())
                || !data.path("credentialId").asText().matches("[a-f0-9]{32}")
                || !java.util.Set.of("login", "api_key").contains(data.path("credentialType").asText())
                || data.path("expiresAt").asLong() <= Instant.now().getEpochSecond()) throw QueryException.denied();
        return new Access(data.path("userId").asText(), data.path("appId").asText(), data.path("credentialId").asText(),
                data.path("credentialType").asText(), data.path("expiresAt").asLong());
    }
    /** 授权服务滚动切换响应协议时兼容两种格式；同时出现但不一致必须拒绝。 */
    private String businessCode(JsonNode body) {
        if (body == null || !body.isObject()) return null;
        JsonNode modern = body.get("code"), legacy = body.get("rscode");
        if (modern != null && (!modern.isIntegralNumber() || !modern.canConvertToInt())) throw QueryException.denied();
        if (legacy != null && !legacy.isTextual()) throw QueryException.denied();
        if (modern != null && legacy != null && !modern.asText().equals(legacy.asText())) throw QueryException.denied();
        return modern != null ? modern.asText() : legacy == null ? null : legacy.asText();
    }

}
