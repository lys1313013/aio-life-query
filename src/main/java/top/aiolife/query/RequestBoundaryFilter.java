package top.aiolife.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;

@Component
@Order(0)
public class RequestBoundaryFilter extends OncePerRequestFilter {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if (request.getRequestURI().startsWith("/api/") || request.getRequestURI().equals("/mcp")
                || request.getRequestURI().equals("/graphql")) {
            boolean injected = Collections.list(request.getHeaderNames()).stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).startsWith("x-hasura-"));
            if (injected) { reject(request, response, 403, "ACCESS_DENIED"); return; }
        }
        if ("POST".equals(request.getMethod())) {
            if (request.getHeader("Content-Encoding") != null || request.getContentLengthLong() > 65536) {
                reject(request, response, 413, "QUERY_LIMIT_EXCEEDED"); return;
            }
            byte[] bytes = request.getInputStream().readNBytes(65537);
            if (bytes.length > 65536) { reject(request, response, 413, "QUERY_LIMIT_EXCEEDED"); return; }
            var input = new ByteArrayInputStream(bytes);
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    return new ServletInputStream() {
                        public int read() { return input.read(); }
                        public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
                        public boolean isFinished() { return input.available() == 0; }
                        public boolean isReady() { return true; }
                        public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous request only"); }
                    };
                }
            }, response);
        } else chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, int status, String code) throws IOException {
        if (request.getRequestURI().equals("/graphql")) {
            response.setStatus(status); response.setContentType(GraphqlResponse.contentType(request)); response.setCharacterEncoding("UTF-8");
            JSON.writeValue(response.getWriter(), GraphqlResponse.error(code, "请求不符合访问边界"));
            return;
        }
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"rscode\":\"" + code + "\",\"result\":\"请求不符合访问边界\"}");
    }
}
