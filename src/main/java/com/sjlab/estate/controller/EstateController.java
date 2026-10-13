package com.sjlab.estate.controller;

import com.sjlab.estate.service.EstateService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 부동산 실거래 조회 API.
 *
 * <p>context-path 가 {@code /estate} 이므로 외부 경로는 {@code /estate/...} 다.
 * 게이트웨이도 경로를 벗기지 않고 그대로 넘긴다.
 *
 * <p><b>매물 종류를 경로에 둔 이유</b>: 국토교통부가 아파트·오피스텔·연립다세대를 각각 다른
 * API 로 주고 컬럼도 달라 DB 표가 종류별로 나뉜다. 경로에 두면 새 종류를 열 때 경로를 바꾸지
 * 않고 표만 이어 붙이면 된다. 지금은 아파트만 서비스하고 나머지는 <b>503 + 이유</b>로 답한다
 * (404 로 두면 "주소가 틀렸나"로 읽혀 원인을 찾기 어렵다). 모르는 종류는 400.
 *
 * <p>응답 키는 <b>DB 컬럼명 그대로</b>이고, 오류는 {@code {"detail": {"code", "message"}}} 형태다
 * — 옛 구현(fast-api-ai)과 같은 계약을 유지해 화면을 고치지 않아도 되게 했다.
 */
@RestController
public class EstateController {

    private final EstateService estateService;

    public EstateController(EstateService estateService) {
        this.estateService = estateService;
    }

    /** 화면이 "데이터를 쓸 수 있는 상태인지" 확인할 때 쓴다. 설정이 없어도 부를 수 있다. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> readiness = estateService.readiness();
        String reason = String.valueOf(readiness.get("reason"));
        boolean ready = "READY".equals(reason);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", ready);
        body.put(
                "message",
                ready
                        ? "부동산 실거래 데이터를 조회할 수 있습니다."
                        : "부동산 데이터 접속이 준비되지 않았습니다.");
        // 준비되지 않은 이유를 함께 알려 준다 — "설정이 없다"와 "붙지 못한다"를 구분하지 못하면
        // 운영에서 무엇을 고쳐야 할지 알 수 없다. 접속 문자열·비밀번호는 담지 않는다.
        body.put("reason", reason);
        body.put("configured", readiness.get("configured"));
        if (readiness.get("detail") != null) {
            body.put("detail", readiness.get("detail"));
        }
        body.put("propertyTypes", estateService.propertyTypes());
        return body;
    }

    @GetMapping("/{propertyType}/meta")
    public Map<String, Object> meta(@PathVariable String propertyType) {
        checkPropertyType(propertyType);
        return estateService.getMeta();
    }

    @GetMapping("/{propertyType}/regions")
    public Map<String, Object> regions(@PathVariable String propertyType) {
        checkPropertyType(propertyType);
        return items(estateService.getRegions());
    }

    @GetMapping("/{propertyType}/trade/monthly")
    public Map<String, Object> tradeMonthly(
            @PathVariable String propertyType,
            @RequestParam(required = false) String sggCd,
            @RequestParam(required = false) Integer fromYm,
            @RequestParam(required = false) Integer toYm) {
        checkPropertyType(propertyType);
        checkSggCd(sggCd);
        checkYm(fromYm, "fromYm");
        checkYm(toYm, "toYm");
        return items(estateService.getTradeMonthly(sggCd, fromYm, toYm));
    }

    @GetMapping("/{propertyType}/rent/monthly")
    public Map<String, Object> rentMonthly(
            @PathVariable String propertyType,
            @RequestParam(required = false) String sggCd,
            @RequestParam(required = false) Integer fromYm,
            @RequestParam(required = false) Integer toYm) {
        checkPropertyType(propertyType);
        checkSggCd(sggCd);
        checkYm(fromYm, "fromYm");
        checkYm(toYm, "toYm");
        return items(estateService.getRentMonthly(sggCd, fromYm, toYm));
    }

    @GetMapping("/{propertyType}/trade")
    public Map<String, Object> trades(
            @PathVariable String propertyType,
            @RequestParam(required = false) String sggCd,
            @RequestParam(required = false) Integer fromYm,
            @RequestParam(required = false) Integer toYm,
            @RequestParam(required = false) String aptNm,
            @RequestParam(required = false) Integer limit) {
        checkPropertyType(propertyType);
        checkSggCd(sggCd);
        checkYm(fromYm, "fromYm");
        checkYm(toYm, "toYm");
        if (aptNm != null && aptNm.length() > 100) {
            throw new BadRequestException("INVALID_PARAMETER", "aptNm 은 100자 이하여야 합니다.");
        }
        int size = clamp(limit, EstateService.LIST_LIMIT_DEFAULT, 1, EstateService.LIST_LIMIT_MAX, "limit");
        return items(estateService.getTrades(sggCd, fromYm, toYm, aptNm, size));
    }

    @GetMapping("/{propertyType}/trade/ranking")
    public Map<String, Object> tradeRanking(
            @PathVariable String propertyType,
            @RequestParam(required = false) Integer fromYm,
            @RequestParam(required = false) Integer toYm,
            @RequestParam(required = false) Integer limit) {
        checkPropertyType(propertyType);
        checkYm(fromYm, "fromYm");
        checkYm(toYm, "toYm");
        int size = clamp(limit, 20, 1, 100, "limit");
        return items(estateService.getTradeRanking(fromYm, toYm, size));
    }

    /** 목록 응답은 {@code {"items": [...]}} 로 감싼다(옛 구현과 같은 형태). */
    private Map<String, Object> items(List<Map<String, Object>> rows) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows);
        return body;
    }

    private void checkPropertyType(String propertyType) {
        Map<String, Object> info = estateService.findPropertyType(propertyType);
        if (info.isEmpty()) {
            throw new BadRequestException(
                    "UNKNOWN_PROPERTY_TYPE", "매물 종류는 apt, offi, villa 중 하나입니다.");
        }
        if (!Boolean.TRUE.equals(info.get("available"))) {
            throw new NotReadyException(
                    "PROPERTY_TYPE_NOT_READY",
                    info.get("label")
                            + " 실거래는 아직 서비스 준비 중입니다. 현재는 아파트만 조회할 수 있습니다.");
        }
    }

    private void checkSggCd(String sggCd) {
        if (sggCd != null && !sggCd.isBlank() && !sggCd.matches("\\d{5}")) {
            throw new BadRequestException("INVALID_PARAMETER", "sggCd 는 숫자 5자리입니다(예: 11680).");
        }
    }

    /** 기간은 yyyyMM 숫자로 받는다. 틀린 값은 조용히 무시하지 않고 400 으로 알려 준다. */
    private void checkYm(Integer value, String name) {
        if (value == null) {
            return;
        }
        int month = value % 100;
        if (value < 200601 || value > 299912 || month < 1 || month > 12) {
            throw new BadRequestException(
                    "INVALID_PERIOD", name + " 은 yyyyMM 형식이어야 합니다(예: 202601).");
        }
    }

    private int clamp(Integer value, int defaultValue, int min, int max, String name) {
        if (value == null) {
            return defaultValue;
        }
        if (value < min || value > max) {
            throw new BadRequestException(
                    "INVALID_PARAMETER", name + " 은 " + min + "~" + max + " 사이여야 합니다.");
        }
        return value;
    }

    /** 400 — 호출하는 쪽이 고쳐야 하는 입력. */
    static class BadRequestException extends RuntimeException {
        final String code;

        BadRequestException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** 503 — 서버가 아직 준비되지 않은 상태(준비 중인 매물 종류 등). */
    static class NotReadyException extends RuntimeException {
        final String code;

        NotReadyException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** 응답을 직접 만들 일이 있을 때를 위한 헬퍼(지금은 예외 처리기가 쓴다). */
    static ResponseEntity<Map<String, Object>> error(int httpStatus, String code, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("message", message);
        return ResponseEntity.status(httpStatus).body(Map.of("detail", detail));
    }
}
