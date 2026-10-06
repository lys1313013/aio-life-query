package top.aiolife.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 每个 MCP HTTP 请求独立认证，不能凭 initialize/session id 复用旧身份。 */
@Component
@Order(1)
public class McpAccessFilter extends OncePerRequestFilter {
    static final String TOKEN_ATTRIBUTE = McpAccessFilter.class.getName() + ".token";
    private final AccessClient access;
    private final ObjectMapper json;
    private final Set<String> origins;

    public McpAccessFilter(AccessClient access, ObjectMapper json,
                           @Value("${AIO_QUERY_MCP_ALLOWED_ORIGINS:}") String allowedOrigins) {
        this.access = access; this.json = json;
        this.origins = Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals("/mcp");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            var origin = request.getHeader("Origin");
            if (origin != null && !origins.contains(origin)) throw QueryException.denied();
            String token = QueryController.bearer(request.getHeader("Authorization"));
            access.evaluate(token, "catalog");
            request.setAttribute(TOKEN_ATTRIBUTE, token);
        } catch (QueryException error) {
            response.setStatus(error.status()); response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            if (error.status() == 401) response.setHeader("WWW-Authenticate", "Bearer");
            json.writeValue(response.getOutputStream(), Map.of("rscode", error.code(), "result", error.getMessage()));
            return;
        }
        chain.doFilter(request, response);
    }
}
