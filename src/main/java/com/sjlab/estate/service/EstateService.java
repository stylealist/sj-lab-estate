package com.sjlab.estate.service;

import com.sjlab.estate.mapper.EstateMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    /**
     * 지역 목록 캐시 유지 시간.
     *
     * <p>이 조회는 <b>표 전체를 세기 때문에 5초가 걸린다</b>(9.9만→990만 행의 인덱스를 끝까지 읽는다.
     * 실측 5.1초, Parallel Index Only Scan). 그런데 결과는 <b>수집이 돌 때만</b> 바뀌고 수집은 하루
     * 한 번이다. 그래서 받아 두고 다시 쓰는 쪽이 맞다 — 첫 화면이 지역 목록 때문에 멈추던 문제가
     * 이것이었다(2026-10-13).
     *
     * <p>건수는 화면 선택 목록의 보조 표시이므로 몇 시간 뒤처져도 문제가 없다.
     */
    private static final Duration REGIONS_TTL = Duration.ofHours(6);

    private volatile List<Map<String, Object>> cachedRegions;
    private volatile Instant regionsLoadedAt;
    /** 갱신이 겹쳐 같은 무거운 조회를 여러 번 돌리지 않게 한다. */
    private final java.util.concurrent.atomic.AtomicBoolean regionsRefreshing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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

    /**
     * 거래가 있는 시군구 목록(시도 코드·이름 포함). 느린 조회라 받아 두고 다시 쓴다.
     *
     * <p><b>기한이 지나도 사용자를 기다리게 하지 않는다</b> — 가진 값을 바로 주고 갱신은 뒤에서
     * 한다(stale-while-revalidate). 만료된 순간에 들어온 사람만 5초를 기다리는 일이 없게 하려는
     * 것이다. 갱신이 겹치지 않도록 한 번에 하나만 돌린다.
     */
    public List<Map<String, Object>> getRegions() {
        List<Map<String, Object>> cached = cachedRegions;
        Instant loadedAt = regionsLoadedAt;
        boolean fresh = cached != null && loadedAt != null
                && Instant.now().isBefore(loadedAt.plus(REGIONS_TTL));
        if (fresh) {
            return cached;
        }
        if (cached != null) {
            refreshRegionsInBackground();
            return cached;
        }
        return loadRegions();
    }

    private void refreshRegionsInBackground() {
        if (!regionsRefreshing.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                loadRegions();
            } catch (Exception e) {
                log.warn("지역 목록 갱신 실패 — 이전 값을 계속 사용한다: {}", e.getClass().getSimpleName());
            } finally {
                regionsRefreshing.set(false);
            }
        }, "estate-regions-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    /** 시군구 건수 + 시도 이름을 합쳐 캐시에 넣는다. */
    private List<Map<String, Object>> loadRegions() {
        EstateMapper mapper = mapper();
        List<Map<String, Object>> rows = mapper.selectRegions();

        // 시도 이름은 17행짜리 표에서 따로 읽어 코드 앞 2자리로 붙인다
        // (이 조인을 무거운 집계 쿼리에 넣으면 5.1초 → 19.7초가 된다. 2026-10-13 실측)
        Map<String, String> sidoNames = new LinkedHashMap<>();
        try {
            for (Map<String, Object> sido : mapper.selectSidoNames()) {
                Object code = sido.get("sido_cd");
                Object name = sido.get("sido_nm");
                if (code != null) {
                    sidoNames.put(String.valueOf(code), name == null ? null : String.valueOf(name));
                }
            }
        } catch (RuntimeException e) {
            // 이름을 못 읽어도 코드로 묶으면 화면은 동작한다(권한 누락 등)
            log.warn("시도 이름을 읽지 못해 코드로 표시한다: {}", e.getClass().getSimpleName());
        }

        List<Map<String, Object>> merged = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            String sggCd = row.get("sgg_cd") == null ? "" : String.valueOf(row.get("sgg_cd"));
            String sidoCd = sggCd.length() >= 2 ? sggCd.substring(0, 2) : sggCd;
            String sidoNm = sidoNames.get(sidoCd);
            item.put("sido_cd", sidoCd);
            item.put("sido_nm", sidoNm != null ? sidoNm : sidoCd);
            merged.add(item);
        }

        cachedRegions = merged;
        regionsLoadedAt = Instant.now();
        return merged;
    }

    /**
     * 기동 직후 지역 목록을 미리 받아 둔다(예열).
     *
     * <p>예열하지 않으면 <b>첫 방문자가 5초를 기다린다.</b> 기다리는 쪽이 사람이 아니라 서버가
     * 되게 하는 것이 이 메서드의 목적이다. 별도 스레드로 돌려 기동을 늦추지 않고, DB 접속 정보가
     * 없거나 권한이 없으면 조용히 건너뛴다(그 상태는 /estate/status 가 알려 준다).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpRegions() {
        Thread worker = new Thread(() -> {
            try {
                long started = System.currentTimeMillis();
                int count = getRegions().size();
                log.info("지역 목록 예열 완료: {}개 ({}ms)", count, System.currentTimeMillis() - started);
            } catch (Exception e) {
                log.info("지역 목록 예열 건너뜀: {}", e.getClass().getSimpleName());
            }
        }, "estate-regions-warmup");
        worker.setDaemon(true);
        worker.start();
    }

    /** 그 시군구의 읍면동 목록. 자연키 인덱스를 타서 빠르므로 캐시하지 않는다(실측 51ms). */
    public List<Map<String, Object>> getEmdList(String sggCd) {
        return mapper().selectEmdList(sggCd);
    }

    /**
     * 매매 월별 추세. 범위에 따라 읽는 곳이 다르다.
     *
     * <ul>
     *   <li>읍면동까지 고르면 원본에서 계산한다 — 집계 뷰는 시군구 단위로 묶여 있어 쓸 수 없다.</li>
     *   <li>시군구만 고르면 미리 집계된 뷰를 쓴다(가장 빠르다).</li>
     *   <li>전국이면 원본에서 월별로 다시 합친다(뷰를 그대로 평균하면 틀린다).</li>
     * </ul>
     */
    public List<Map<String, Object>> getTradeMonthly(String sggCd, String umdNm, Integer fromYm, Integer toYm) {
        EstateMapper mapper = mapper();
        boolean hasSgg = sggCd != null && !sggCd.isBlank();
        if (hasSgg && umdNm != null && !umdNm.isBlank()) {
            return mapper.selectTradeMonthlyByUmd(sggCd, umdNm, fromYm, toYm);
        }
        if (hasSgg) {
            return mapper.selectTradeMonthlyBySgg(sggCd, fromYm, toYm);
        }
        return mapper.selectTradeMonthlyNationwide(fromYm, toYm);
    }

    /** 전월세 월별 추세. 범위에 따라 읽는 곳이 다르다(매매와 같은 규칙). */
    public List<Map<String, Object>> getRentMonthly(String sggCd, String umdNm, Integer fromYm, Integer toYm) {
        EstateMapper mapper = mapper();
        boolean hasSgg = sggCd != null && !sggCd.isBlank();
        if (hasSgg && umdNm != null && !umdNm.isBlank()) {
            return mapper.selectRentMonthlyByUmd(sggCd, umdNm, fromYm, toYm);
        }
        if (hasSgg) {
            return mapper.selectRentMonthlyBySgg(sggCd, fromYm, toYm);
        }
        return mapper.selectRentMonthlyNationwide(fromYm, toYm);
    }

    public List<Map<String, Object>> getTrades(
            String sggCd, String umdNm, Integer fromYm, Integer toYm, String aptNm, int limit) {
        return new ArrayList<>(mapper().selectTrades(sggCd, umdNm, fromYm, toYm, aptNm, limit));
    }

    /** 지역별 평균 매매가 순위. 시도를 주면 그 시도 안에서만 순위를 낸다. */
    public List<Map<String, Object>> getTradeRanking(String sidoCd, Integer fromYm, Integer toYm, int limit) {
        return new ArrayList<>(mapper().selectTradeRanking(sidoCd, fromYm, toYm, limit));
    }

    /** DB 접속 설정이 없을 때. 컨트롤러가 503 + NOT_CONFIGURED 로 바꿔 답한다. */
    public static class EstateNotConfiguredException extends RuntimeException {
        public EstateNotConfiguredException() {
            super("부동산 데이터 접속 설정이 없습니다(ESTATE_DB_URL). 관리자에게 문의하세요.");
        }
    }
}
