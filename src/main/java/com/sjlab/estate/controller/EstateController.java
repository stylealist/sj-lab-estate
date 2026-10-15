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

    /** 시군구 목록. 항목마다 시도 코드·이름이 함께 있어 화면이 시도 목록도 이 응답으로 만든다. */
    @GetMapping("/{propertyType}/regions")
    public Map<String, Object> regions(@PathVariable String propertyType) {
        checkPropertyType(propertyType);
        return items(estateService.getRegions());
    }

    /** 그 시군구의 읍면동 목록. 시군구를 고른 뒤 다음 단계를 그릴 때 부른다. */
    @GetMapping("/{propertyType}/regions/emd")
    public Map<String, Object> emdList(
            @PathVariable String propertyType, @RequestParam String sggCd) {
        checkPropertyType(propertyType);
        checkSggCd(sggCd);
        if (sggCd == null || sggCd.isBlank()) {
            throw new BadRequestException("MISSING_PARAMETER", "sggCd 는 필수입니다(숫자 5자리).");
        }
        return items(estateService.getEmdList(sggCd));
    }

    @GetMapping("/{propertyType}/trade/monthly")
    public Map<String, Object> tradeMonthly(
            @PathVariable String propertyType,
            @RequestParam(required = false) String sggCd,
            @RequestParam(required = false) String umdNm,
            @RequestParam(required = false) Integer fromYm,
            @RequestParam(required = false) Integer toYm) {
        checkPropertyType(propertyType);
        checkSggCd(sggCd);
        checkUmdNm(sggCd, umdNm);
        checkYm(fromYm, "fromYm");
        checkYm(toYm, "toYm");
        return items(estateService.getTradeMonthly(sggCd, umdNm, fromYm, toYm));
    }

    @GetMapping("/{propertyType}/rent/monthly")
    public Map<String, Object> rentMonthly(
            @PathVariable String propertyType,
            @RequestParam(required = false) String sggCd,
            @RequestParam(required = false) String umdNm,
            @RequestParam(required = false) Integer fromYm,
            @RequestParam(required = false) Integer toYm) {
        checkPropertyType(propertyType);
        checkSggCd(sggCd);
        checkUmdNm(sggCd, umdNm);
        checkYm(fromYm, "fromYm");
        checkYm(toYm, "toYm");
        return items(estateService.getRentMonthly(sggCd, umdNm, fromYm, toYm));
    }

    @GetMapping("/{propertyType}/trade")
    public Map<String, Object> trades(
            @PathVariable String propertyType,
            @RequestParam(required = false) String sggCd,
            @RequestParam(required = false) String umdNm,
            @RequestParam(required = false) Integer fromYm,
            @RequestParam(required = false) Integer toYm,
            @RequestParam(required = false) String aptNm,
            @RequestParam(required = false) Integer limit) {
        checkPropertyType(propertyType);
        checkSggCd(sggCd);
        checkUmdNm(sggCd, umdNm);
        checkYm(fromYm, "fromYm");
        checkYm(toYm, "toYm");
        if (aptNm != null && aptNm.length() > 100) {
            throw new BadRequestException("INVALID_PARAMETER", "aptNm 은 100자 이하여야 합니다.");
        }
        int size = clamp(limit, EstateService.LIST_LIMIT_DEFAULT, 1, EstateService.LIST_LIMIT_MAX, "limit");
        return items(estateService.getTrades(sggCd, umdNm, fromYm, toYm, aptNm, size));
    }

    /**
     * 지도용 지역 경계 + 평균가(GeoJSON).
     *
     * <p>좌표가 없어도 되는 지도다 — 행정구역 경계가 이미 DB 에 있어 **지역별 평균가를 색으로**
     * 칠한다. 단지 핀은 좌표가 모이면 같은 지도 위에 얹는다.
     *
     * <p>`level=sgg` 는 시군구(시도를 주면 그 안만), `level=emd` 는 한 시군구 안의 읍면동이다.
     * **전국 시군구는 경계가 크고 집계도 무겁다** — 화면은 기본으로 시도를 지정해 부른다.
     *
     * <p>응답은 GeoJSON 이라 지도 라이브러리가 그대로 읽는다(오류도 JSON 형식을 지킨다).
     */
    @GetMapping(value = "/{propertyType}/map/regions", produces = "application/json")
    public ResponseEntity<String> mapRegions(
            @PathVariable String propertyType,
            @RequestParam(defaultValue = "sgg") String level,
            @RequestParam(required = false) String sidoCd,
            @RequestParam(required = false) String sggCd,
            @RequestParam(required = false) Integer fromYm,
            @RequestParam(required = false) Integer toYm,
            @RequestParam(required = false, defaultValue = "1") String stats) {
        checkPropertyType(propertyType);
        checkYm(fromYm, "fromYm");
        checkYm(toYm, "toYm");

        String geojson;
        if ("emd".equalsIgnoreCase(level)) {
            checkSggCd(sggCd);
            if (sggCd == null || sggCd.isBlank()) {
                throw new BadRequestException("MISSING_PARAMETER", "level=emd 는 sggCd 가 필요합니다.");
            }
            geojson = estateService.getMapRegionsEmd(sggCd, fromYm, toYm);
        } else if ("sgg".equalsIgnoreCase(level)) {
            checkSidoCd(sidoCd);
            geojson = estateService.getMapRegionsSgg(sidoCd, fromYm, toYm);
        } else if ("sido".equalsIgnoreCase(level)) {
            // stats=0 은 "경계만" — 지도가 어느 시도를 보고 있는지 판단할 때 쓴다(집계는 무겁다)
            geojson = estateService.getMapRegionsSido(fromYm, toYm, !"0".equals(stats));
        } else {
            throw new BadRequestException("INVALID_PARAMETER", "level 은 sido, sgg, emd 중 하나입니다.");
        }

        // 경계는 자주 바뀌지 않는다 — 브라우저가 잠시 들고 있게 한다(지도를 움직일 때마다 받지 않도록)
        return ResponseEntity.ok()
                .header("Cache-Control", "public, max-age=300")
                .body(geojson);
    }

    /**
     * 화면 영역 안의 단지 핀(GeoJSON Point). 호갱노노의 아파트 핀에 해당한다.
     *
     * <p>좌표는 실거래 원천에 없어 따로 모은다(`estate.apt_complex`). 아직 비어 있으면
     * <b>빈 목록 + `coordinates_ready:false`</b> 로 답한다 — 화면이 "핀 없음"과 "준비 전"을
     * 구분해 안내할 수 있어야 한다. 오류로 답하면 지도 전체가 깨진 것처럼 보인다.
     */
    @GetMapping(value = "/{propertyType}/map/complexes", produces = "application/json")
    public ResponseEntity<String> mapComplexes(
            @PathVariable String propertyType,
            @RequestParam String bbox,
            @RequestParam(required = false) Integer fromYm,
            @RequestParam(required = false) Integer toYm,
            @RequestParam(required = false) Integer limit) {
        checkPropertyType(propertyType);
        checkYm(fromYm, "fromYm");
        checkYm(toYm, "toYm");

        double[] box = parseBbox(bbox);
        String geojson = estateService.getMapComplexPins(
                box[0], box[1], box[2], box[3], fromYm, toYm, limit == null ? 300 : limit);

        // 지도를 움직이는 동안 같은 영역을 다시 부르는 일이 잦다 — 짧게 들고 있게 한다.
        return ResponseEntity.ok()
                .header("Cache-Control", "public, max-age=60")
                .body(geojson);
    }

    /**
     * `bbox=minX,minY,maxX,maxY`(EPSG:3857, 지도 뷰와 같은 좌표계)를 숫자 4개로 바꾼다.
     * 지도 레이어(mapservice-rest)와 같은 형식·같은 좌표계로 맞춘 것이다.
     */
    private double[] parseBbox(String bbox) {
        if (bbox == null || bbox.isBlank()) {
            throw new BadRequestException("MISSING_PARAMETER", "bbox 는 필수입니다(minX,minY,maxX,maxY).");
        }
        String[] parts = bbox.split(",");
        if (parts.length != 4) {
            throw new BadRequestException("INVALID_PARAMETER", "bbox 는 쉼표로 나눈 숫자 4개여야 합니다.");
        }
        double[] values = new double[4];
        for (int i = 0; i < 4; i++) {
            try {
                values[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("INVALID_PARAMETER", "bbox 에 숫자가 아닌 값이 있습니다.");
            }
        }
        if (values[0] >= values[2] || values[1] >= values[3]) {
            throw new BadRequestException("INVALID_PARAMETER", "bbox 의 최소값이 최대값보다 커서는 안 됩니다.");
        }
        return values;
    }

    // ── 단지(아파트) 단위 — 호갱노노식 단지 패널에 쓰는 것들 ─────────────────
    // 단지 키 apt_seq 는 "11680-218" 처럼 앞 5자리가 시군구 코드다(서비스가 잘라 쓴다).
    // 읍면동(umdNm)을 함께 주면 조회가 더 좁아진다 — 화면은 이미 알고 있으므로 넘겨 준다.

    /** 지역 안의 단지 목록(거래 많은 순). `q` 로 단지명 일부 검색. */
    @GetMapping("/{propertyType}/complexes")
    public Map<String, Object> complexes(
            @PathVariable String propertyType,
            @RequestParam String sggCd,
            @RequestParam(required = false) String umdNm,
            @RequestParam(required = false, name = "q") String keyword,
            @RequestParam(required = false) Integer limit) {
        checkPropertyType(propertyType);
        checkSggCd(sggCd);
        if (sggCd == null || sggCd.isBlank()) {
            throw new BadRequestException("MISSING_PARAMETER", "sggCd 는 필수입니다(숫자 5자리).");
        }
        checkUmdNm(sggCd, umdNm);
        if (keyword != null && keyword.length() > 40) {
            throw new BadRequestException("INVALID_PARAMETER", "q 는 40자 이하여야 합니다.");
        }
        int size = clamp(limit, 100, 1, 500, "limit");
        return items(estateService.getComplexes(sggCd, umdNm, keyword, size));
    }

    /** 단지 기본 정보 + 평형별 요약(평형은 **전용면적** 기준). */
    @GetMapping("/{propertyType}/complexes/{aptSeq}")
    public Map<String, Object> complexDetail(
            @PathVariable String propertyType,
            @PathVariable String aptSeq,
            @RequestParam(required = false) String umdNm) {
        checkPropertyType(propertyType);
        checkAptSeq(aptSeq);
        Map<String, Object> detail = estateService.getComplexDetail(aptSeq, umdNm);
        if (detail.isEmpty()) {
            throw new NotFoundException("COMPLEX_NOT_FOUND", "그 단지의 거래 기록을 찾지 못했습니다: " + aptSeq);
        }
        return detail;
    }

    /** 단지(평형) 월별 매매 추세. */
    @GetMapping("/{propertyType}/complexes/{aptSeq}/monthly")
    public Map<String, Object> complexMonthly(
            @PathVariable String propertyType,
            @PathVariable String aptSeq,
            @RequestParam(required = false) String umdNm,
            @RequestParam(required = false) Integer pyeong,
            @RequestParam(required = false) Integer fromYm,
            @RequestParam(required = false) Integer toYm) {
        checkPropertyType(propertyType);
        checkAptSeq(aptSeq);
        checkYm(fromYm, "fromYm");
        checkYm(toYm, "toYm");
        return items(estateService.getComplexMonthly(aptSeq, umdNm, pyeong, fromYm, toYm));
    }

    /** 단지(평형) 월별 전월세. */
    @GetMapping("/{propertyType}/complexes/{aptSeq}/rent/monthly")
    public Map<String, Object> complexRentMonthly(
            @PathVariable String propertyType,
            @PathVariable String aptSeq,
            @RequestParam(required = false) String umdNm,
            @RequestParam(required = false) Integer pyeong,
            @RequestParam(required = false) Integer fromYm,
            @RequestParam(required = false) Integer toYm) {
        checkPropertyType(propertyType);
        checkAptSeq(aptSeq);
        checkYm(fromYm, "fromYm");
        checkYm(toYm, "toYm");
        return items(estateService.getComplexRentMonthly(aptSeq, umdNm, pyeong, fromYm, toYm));
    }

    /** 단지(평형) 최근 거래 목록. */
    @GetMapping("/{propertyType}/complexes/{aptSeq}/trades")
    public Map<String, Object> complexTrades(
            @PathVariable String propertyType,
            @PathVariable String aptSeq,
            @RequestParam(required = false) String umdNm,
            @RequestParam(required = false) Integer pyeong,
            @RequestParam(required = false) Integer limit) {
        checkPropertyType(propertyType);
        checkAptSeq(aptSeq);
        int size = clamp(limit, 50, 1, 500, "limit");
        return items(estateService.getComplexTrades(aptSeq, umdNm, pyeong, size));
    }

    @GetMapping("/{propertyType}/trade/ranking")
    public Map<String, Object> tradeRanking(
            @PathVariable String propertyType,
            @RequestParam(required = false) String sidoCd,
            @RequestParam(required = false) Integer fromYm,
            @RequestParam(required = false) Integer toYm,
            @RequestParam(required = false) Integer limit) {
        checkPropertyType(propertyType);
        checkSidoCd(sidoCd);
        checkYm(fromYm, "fromYm");
        checkYm(toYm, "toYm");
        int size = clamp(limit, 20, 1, 100, "limit");
        return items(estateService.getTradeRanking(sidoCd, fromYm, toYm, size));
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

    /** 단지 키는 "11680-218" 형태다(앞 5자리가 시군구 코드). */
    private void checkAptSeq(String aptSeq) {
        if (aptSeq == null || !aptSeq.matches("\\d{5}-\\S{1,20}")) {
            throw new BadRequestException("INVALID_PARAMETER", "aptSeq 형식이 올바르지 않습니다(예: 11680-218).");
        }
    }

    private void checkSidoCd(String sidoCd) {
        if (sidoCd != null && !sidoCd.isBlank() && !sidoCd.matches("\\d{2}")) {
            throw new BadRequestException("INVALID_PARAMETER", "sidoCd 는 숫자 2자리입니다(예: 11).");
        }
    }

    /**
     * 읍면동은 **이름**으로 받는다(두 표에 공통으로 있는 키가 이름뿐이다).
     *
     * <p>시군구 없이 읍면동만 주면 같은 이름이 전국에 여러 개 있어 엉뚱한 결과가 나오므로 400 이다.
     */
    private void checkUmdNm(String sggCd, String umdNm) {
        if (umdNm == null || umdNm.isBlank()) {
            return;
        }
        if (sggCd == null || sggCd.isBlank()) {
            throw new BadRequestException(
                    "MISSING_PARAMETER", "umdNm 을 쓸 때는 sggCd 도 함께 주세요(같은 동 이름이 여러 지역에 있습니다).");
        }
        if (umdNm.length() > 40) {
            throw new BadRequestException("INVALID_PARAMETER", "umdNm 은 40자 이하여야 합니다.");
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

    /** 404 — 그런 단지가 없다(거래 기록이 없다). */
    static class NotFoundException extends RuntimeException {
        final String code;

        NotFoundException(String code, String message) {
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
