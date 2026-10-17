package top.aiolife.query;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

final class GraphqlResponse {
    private GraphqlResponse() {}

    static String contentType(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("application/json") && !accept.contains("application/graphql-response+json")
                ? "application/json" : "application/graphql-response+json";
    }

    static Map<String, Object> error(String code, String message) {
        return Map.of("errors", List.of(Map.of("message", message, "extensions", Map.of("code", code))));
    }
}
