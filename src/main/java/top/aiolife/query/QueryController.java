package top.aiolife.query;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class QueryController {
    private final QueryService service;
    private final QueryProperties properties;
    public QueryController(QueryService service, QueryProperties properties) { this.service = service; this.properties = properties; }

    @ModelAttribute
    public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @GetMapping("/api/v1/datasets")
    public Object list(@RequestHeader(value = "Authorization", required = false) String auth) { return success(service.catalog(bearer(auth), null)); }

    @GetMapping("/api/v1/datasets/{name}")
    public Object describe(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String name) {
        return success(service.catalog(bearer(auth), name));
    }

    @PostMapping("/api/v1/queries/validate")
    public Object validate(@RequestHeader(value = "Authorization", required = false) String auth, @RequestBody QueryCompiler.QueryRequest body) {
        return success(service.validate(bearer(auth), body));
    }

    @PostMapping("/api/v1/queries/execute")
    public Object execute(@RequestHeader(value = "Authorization", required = false) String auth, @RequestBody QueryCompiler.QueryRequest body) {
        return success(service.execute(bearer(auth), body));
    }

    @PostMapping("/internal/hasura/auth")
    public Object webhook(@RequestHeader(value = "X-AIO-Webhook-Key", required = false) String key, @RequestBody JsonNode body) {
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), properties.webhookKey().getBytes(StandardCharsets.UTF_8))) throw QueryException.denied();
        JsonNode headers = body.path("headers");
        if (!headers.isObject()) throw QueryException.denied();
        String authorization = null;
        for (var iterator = headers.fields(); iterator.hasNext();) {
            var item = iterator.next();
            if (item.getKey().equalsIgnoreCase("authorization")) {
                if (authorization != null || !item.getValue().isTextual()) throw QueryException.denied();
                authorization = item.getValue().asText();
            }
        }
        return service.webhook(bearer(authorization));
    }

    static Map<String, Object> success(Object data) {
        var response = new LinkedHashMap<String, Object>();
        response.put("rscode", "0"); response.put("result", null); response.put("data", data); return response;
    }

    static String bearer(String auth) {
        if (auth == null || !auth.startsWith("Bearer ") || auth.length() > 4103 || auth.length() <= 7
                || !auth.substring(7).matches("[!-~]+")) {
            throw new QueryException("AUTH_REQUIRED", 401, "需要 AIO Life 登录 Token 或 API Key");
        }
        return auth.substring(7);
    }
}
