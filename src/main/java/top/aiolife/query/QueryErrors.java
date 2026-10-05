package top.aiolife.query;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class QueryErrors {
    @ExceptionHandler(QueryException.class)
    public ResponseEntity<?> query(QueryException error) {
        return ResponseEntity.status(error.status()).header("Cache-Control", "no-store")
                .body(Map.of("rscode", error.code(), "result", error.getMessage()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> invalid() { return query(QueryException.invalid()); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> dependency() { return query(new QueryException("DEPENDENCY_FAILURE", 503, "查询依赖不可用")); }
}
