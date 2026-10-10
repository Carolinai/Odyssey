package org.odyssey.api;

import org.odyssey.store.DuplicateEnvironmentException;
import org.odyssey.store.EnvironmentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一错误响应：JSON 格式 { "message": "..." }。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(EnvironmentNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(EnvironmentNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(DuplicateEnvironmentException.class)
    public ResponseEntity<Map<String, String>> duplicate(DuplicateEnvironmentException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Map<String, String>> ioError(UncheckedIOException e) {
        log.error("IO 错误", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "数据读写失败：" + e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        Map<String, String> map = new HashMap<>();
        map.put("message", message);
        return ResponseEntity.status(status).body(map);
    }
}
