package top.aiolife.query;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class QueryErrors {
    @ExceptionHandler(QueryException.class)
    public ResponseEntity<?> query(QueryException error, HttpServletRequest request) {
        if (request.getRequestURI().equals("/graphql")) {
            return ResponseEntity.status(error.status()).header("Cache-Control", "no-store")
                    .contentType(MediaType.parseMediaType(GraphqlResponse.contentType(request)))
                    .body(GraphqlResponse.error(error.code(), error.getMessage()));
        }
        return ResponseEntity.status(error.status()).header("Cache-Control", "no-store")
                .body(Map.of("rscode", error.code(), "result", error.getMessage()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> invalid(HttpServletRequest request) { return query(QueryException.invalid(), request); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> dependency(Exception error, HttpServletRequest request) {
        if (request.getRequestURI().equals("/graphql") && error instanceof ErrorResponse httpError) {
            return ResponseEntity.status(httpError.getStatusCode()).headers(httpError.getHeaders())
                    .header("Cache-Control", "no-store")
                    .contentType(MediaType.parseMediaType(GraphqlResponse.contentType(request)))
                    .body(GraphqlResponse.error("HTTP_REQUEST_INVALID", "HTTP 方法、请求格式或响应类型不受支持"));
        }
        return query(new QueryException("DEPENDENCY_FAILURE", 503, "查询依赖不可用"), request);
    }
}
