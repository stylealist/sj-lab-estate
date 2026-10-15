package com.sjlab.estate.controller;

import com.sjlab.estate.service.EstateService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 오류 응답 형태를 한 곳에서 정한다.
 *
 * <pre>{"detail": {"code": "...", "message": "..."}}</pre>
 *
 * <p>옛 구현(fast-api-ai)이 쓰던 형태를 그대로 유지한다 — 화면(`src/api.js`)이 이 모양에서
 * 메시지를 꺼내 보여 주므로, 바꾸면 사용자에게 "요청에 실패했습니다"만 남는다.
 *
 * <p>조회 실패는 <b>원인 종류만</b> 알려 준다. 예외 메시지를 그대로 내보내면 접속 문자열이나
 * 쿼리가 응답에 섞일 수 있다(자세한 내용은 서버 로그에 남는다).
 */
@RestControllerAdvice
public class EstateExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(EstateExceptionHandler.class);

    @ExceptionHandler(EstateController.BadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(EstateController.BadRequestException e) {
        return body(HttpStatus.BAD_REQUEST, e.code, e.getMessage());
    }

    @ExceptionHandler(EstateController.NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(EstateController.NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.code, e.getMessage());
    }

    @ExceptionHandler(EstateController.NotReadyException.class)
    public ResponseEntity<Map<String, Object>> handleNotReady(EstateController.NotReadyException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.code, e.getMessage());
    }

    @ExceptionHandler(EstateService.EstateNotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> handleNotConfigured(
            EstateService.EstateNotConfiguredException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED", e.getMessage());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "파라미터 형식이 올바르지 않습니다.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("부동산 조회 실패", e);
        return body(
                HttpStatus.BAD_GATEWAY,
                "QUERY_FAILED",
                "부동산 데이터를 조회하지 못했습니다: " + e.getClass().getSimpleName());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("message", message);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("detail", detail);
        return ResponseEntity.status(status).body(wrapper);
    }
}
