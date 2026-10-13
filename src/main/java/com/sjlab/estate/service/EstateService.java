package com.sjlab.estate.service;

import com.sjlab.estate.mapper.EstateMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 부동산 실거래 조회.
 *
 * <p>금액 단위는 모두 <b>만원</b>이다(원천 공개데이터 그대로). 억/만원 표기는 화면이 한다.
 *
 * <p>매퍼를 {@code ObjectProvider} 로 받는다 — DB 접속 정보가 없으면 매퍼 빈이 아예 없는
 * 상태로 서비스가 뜨기 때문이다(그때는 조회만 503, {@code /status} 는 정상).
 */
@Service
public class EstateService {

    /** 한 번에 내려주는 거래 목록 상한. 더 필요하면 기간을 좁히게 한다. */
    public static final int LIST_LIMIT_DEFAULT = 100;
    public static final int LIST_LIMIT_MAX = 1000;

    private static final String TRADE_TABLE = "estate.apt_trade";
    private static final String RENT_TABLE = "estate.apt_rent";

    /**
     * 매물 종류와 서비스 여부. 표를 만들고 수집을 붙이면 이 값을 바꾼다 — 화면
     * (sj-lab-estate-web)의 PROPERTY_TYPES 와 id 가 같아야 한다.
     */
    public static final List<Map<String, Object>> PROPERTY_TYPES = List.of(
            propertyType("apt", "아파트", true),
            propertyType("offi", "오피스텔", false),
            propertyType("villa", "빌라 · 다가구", false));

    private static final Logger log = LoggerFactory.getLogger(EstateService.class);

    private final ObjectProvider<EstateMapper> mapperProvider;
    private final Environment environment;

    public EstateService(ObjectProvider<EstateMapper> mapperProvider, Environment environment) {
        this.mapperProvider = mapperProvider;
        this.environment = environment;
    }

    private static Map<String, Object> propertyType(String id, String label, boolean available) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("label", label);
        item.put("available", available);
        return item;
    }

    public boolean isReady() {
        return "READY".equals(readiness().get("reason"));
    }

    /**
     * 조회가 가능한 상태인지와 <b>아닐 때의 이유</b>를 돌려준다.
     *
     * <p>왜 이유까지 담는가: 운영에서 화면이 "준비되지 않았다"만 보여 주면 접속 정보가 없는 것인지,
     * 있는데 DB 에 못 붙는 것인지 구분할 수 없어 확인이 느려진다(2026-10-12 실제로 겪음).
     * 그래서 세 가지를 구분한다 — 설정 없음 / 붙지 못함 / 정상.
     *
     * <p><b>접속 문자열·비밀번호는 절대 담지 않는다.</b> 접속 주소가 설정됐는지(true/false)와
     * 실패한 예외의 종류 이름만 알려 준다.
     */
    public Map<String, Object> readiness() {
        Map<String, Object> result = new LinkedHashMap<>();
        String url = environment.getProperty("estate.datasource.url", "");
        boolean configured = url != null && !url.isBlank();
        result.put("configured", configured);

        EstateMapper mapper = mapperProvider.getIfAvailable();
        if (!configured || mapper == null) {
            result.put("reason", "NOT_CONFIGURED");
            result.put(
                    "detail",
                    "DB 접속 정보가 없습니다(ESTATE_DB_URL). 운영은 k8s Secret estate-db-credentials,"
                            + " 로컬은 settings.local.json 으로 넣습니다.");
            return result;
        }

        // 접속 정보는 있다 — 실제 표를 한 행 읽어 **접속과 조회 권한까지** 확인한다.
        // 통계(pg_class)만 읽으면 권한 없는 계정도 통과해 "정상"으로 보고된다(실제로 겪음).
        try {
            mapper.selectReadinessProbe();
            result.put("reason", "READY");
            return result;
        } catch (Exception e) {
            log.warn("부동산 DB 접속 확인 실패", e);
            result.put("reason", "CONNECT_FAILED");
            result.put(
                    "detail",
                    "접속 정보는 있지만 조회하지 못했습니다(" + e.getClass().getSimpleName()
                            + "). 접속 주소, 계정의 estate 스키마 읽기 권한, 표 존재를 확인하세요.");
            return result;
        }
    }

    public List<Map<String, Object>> propertyTypes() {
        return PROPERTY_TYPES;
    }

    /** 종류 정보. 모르는 종류면 빈 Map. */
    public Map<String, Object> findPropertyType(String id) {
        for (Map<String, Object> item : PROPERTY_TYPES) {
            if (item.get("id").equals(id)) {
                return item;
            }
        }
        return Map.of();
    }

    private EstateMapper mapper() {
        EstateMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            throw new EstateNotConfiguredException();
        }
        return mapper;
    }

    /**
     * 데이터 범위 요약. 화면 상단 요약 카드와 기간 선택 기본값으로 쓴다.
     *
     * <p>건수는 통계 기반 <b>추정치</b>이고 {@code rows_estimated: true} 로 밝힌다. 지역 수는
     * 세지 않는다({@code count(distinct)} 도 전건 훑기라 느리다) — 화면이 지역 목록 길이로 센다.
     */
    public Map<String, Object> getMeta() {
        EstateMapper mapper = mapper();
        Map<String, Object> meta = new LinkedHashMap<>(mapper.selectRowEstimates());

        Integer tradeFrom = periodBound(mapper, TRADE_TABLE, false);
        Integer tradeTo = periodBound(mapper, TRADE_TABLE, true);
        Integer rentFrom = periodBound(mapper, RENT_TABLE, false);
        Integer rentTo = periodBound(mapper, RENT_TABLE, true);

        meta.put("trade_last_load", lastLoad(mapper, TRADE_TABLE, tradeTo));
        meta.put("trade_from_ym", tradeFrom);
        meta.put("trade_to_ym", tradeTo);
        meta.put("rent_from_ym", rentFrom);
        meta.put("rent_to_ym", rentTo);
        meta.put("trade_sgg_count", null);
        meta.put("rows_estimated", true);
        return meta;
    }

    private Integer periodBound(EstateMapper mapper, String table, boolean desc) {
        Map<String, Object> row = mapper.selectPeriodBound(table, desc);
        if (row == null || row.get("deal_year") == null || row.get("deal_month") == null) {
            return null;
        }
        int year = ((Number) row.get("deal_year")).intValue();
        int month = ((Number) row.get("deal_month")).intValue();
        return year * 100 + month;
    }

    /**
     * 마지막 적재 시각.
     *
     * <p>{@code created_at} 에는 인덱스가 없어 {@code max(created_at)} 을 그냥 부르면 전건을
     * 훑는다(실측 23.4초). 가장 최근 거래 월로 범위를 좁히면 기간 인덱스로 그 달만 읽는다.
     * 수집이 최근 달을 다시 받는 구조라 마지막 적재는 늘 최근 달 안에 있다.
     */
    private Object lastLoad(EstateMapper mapper, String table, Integer latestYm) {
        if (latestYm == null) {
            return null;
        }
        Map<String, Object> row = mapper.selectLastLoad(table, latestYm / 100, latestYm % 100);
        return row == null ? null : row.get("last_load");
    }

    public List<Map<String, Object>> getRegions() {
        return mapper().selectRegions();
    }

    public List<Map<String, Object>> getTradeMonthly(String sggCd, Integer fromYm, Integer toYm) {
        EstateMapper mapper = mapper();
        if (sggCd != null && !sggCd.isBlank()) {
            return mapper.selectTradeMonthlyBySgg(sggCd, fromYm, toYm);
        }
        return mapper.selectTradeMonthlyNationwide(fromYm, toYm);
    }

    public List<Map<String, Object>> getRentMonthly(String sggCd, Integer fromYm, Integer toYm) {
        EstateMapper mapper = mapper();
        if (sggCd != null && !sggCd.isBlank()) {
            return mapper.selectRentMonthlyBySgg(sggCd, fromYm, toYm);
        }
        return mapper.selectRentMonthlyNationwide(fromYm, toYm);
    }

    public List<Map<String, Object>> getTrades(
            String sggCd, Integer fromYm, Integer toYm, String aptNm, int limit) {
        return new ArrayList<>(mapper().selectTrades(sggCd, fromYm, toYm, aptNm, limit));
    }

    public List<Map<String, Object>> getTradeRanking(Integer fromYm, Integer toYm, int limit) {
        return new ArrayList<>(mapper().selectTradeRanking(fromYm, toYm, limit));
    }

    /** DB 접속 설정이 없을 때. 컨트롤러가 503 + NOT_CONFIGURED 로 바꿔 답한다. */
    public static class EstateNotConfiguredException extends RuntimeException {
        public EstateNotConfiguredException() {
            super("부동산 데이터 접속 설정이 없습니다(ESTATE_DB_URL). 관리자에게 문의하세요.");
        }
    }
}
