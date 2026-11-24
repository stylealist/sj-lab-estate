package com.sjlab.estate.controller;

import com.sjlab.estate.service.EstateService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
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

    /** 401 — 회원 기능에만 있다. 조회 API 는 로그인을 요구하지 않는다. */
    @ExceptionHandler(EstateController.UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(
            EstateController.UnauthorizedException e) {
        return body(HttpStatus.UNAUTHORIZED, e.code, e.getMessage());
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

    /**
     * 필수 파라미터를 아예 보내지 않은 경우.
     *
     * <p>이것을 따로 받지 않으면 맨 아래 포괄 처리로 떨어져 <b>502 QUERY_FAILED</b> 가 된다 —
     * 부르는 쪽 잘못인데 "서버가 조회에 실패했다"로 보여 원인을 찾기 어렵다(2026-11-17 운영
     * 확인 중 발견: `/complexes/search` 를 `q` 없이 부르면 502 였다). 빈 값으로 보낸 경우는
     * 컨트롤러가 이미 같은 코드(`MISSING_PARAMETER`)로 400 을 낸다 — 둘을 같게 맞춘다.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(
            MissingServletRequestParameterException e) {
        return body(
                HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", e.getParameterName() + " 는 필수입니다.");
    }

    /**
     * 요청 본문(JSON)을 읽을 수 없는 경우 — 회원 기능의 POST·PUT 에만 해당한다.
     *
     * <p>따로 받지 않으면 맨 아래 포괄 처리로 떨어져 <b>502 QUERY_FAILED</b> 가 된다. 부르는 쪽이
     * 본문을 잘못 보낸 것인데 "서버가 조회에 실패했다"로 보여 원인을 찾기 어렵다 — `q` 누락이
     * 502 였던 것(2026-11-17)과 같은 종류의 문제다. 실제로 UTF-8 이 아닌 본문을 보내 보고 확인했다
     * (2026-11-24, 한글 메모를 CP949 로 보낸 경우).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return body(
                HttpStatus.BAD_REQUEST,
                "INVALID_BODY",
                "요청 본문을 읽을 수 없습니다. JSON 형식과 UTF-8 인코딩을 확인해 주세요.");
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
