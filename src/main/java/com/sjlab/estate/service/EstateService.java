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

                // 지도 경계도 미리 깎아 둔다 — 첫 방문자가 8초를 기다리지 않도록.
                // 화면 기본값이 서울이라 서울부터 받아 둔다.
                long mapStarted = System.currentTimeMillis();
                getMapRegionsSgg("11", null, null);
                log.info("지도 경계 예열 완료: 서울 시군구 ({}ms)", System.currentTimeMillis() - mapStarted);

                warmUpRemainingSido();
                warmUpSidoStats();
            } catch (Exception e) {
                log.info("지역 목록 예열 건너뜀: {}", e.getClass().getSimpleName());
            }
        }, "estate-regions-warmup");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 서울 말고 나머지 시도의 경계도 뒤에서 미리 깎아 둔다.
     *
     * <p>경계 단순화는 한 번만 하면 캐시에 남지만 <b>처음 한 번이 느리다</b>(경기도 12.7초 —
     * 2026-10-14 실측. 시군구가 많고 섬·해안선이 복잡한 시도가 특히 오래 걸린다). 다른 시도를
     * 처음 누른 사람이 그 시간을 혼자 부담하지 않도록 기동 후에 한 번씩 돌려 둔다.
     *
     * <p>한 번에 하나씩, 사이를 조금 띄워 돈다 — 기동 직후에 DB를 몰아치면 정작 화면 조회가
     * 밀린다. 실패한 시도는 건너뛰고 다음으로 간다(그 시도는 누를 때 받으면 된다).
     */
    private void warmUpRemainingSido() {
        List<String> sidoCodes = new ArrayList<>();
        for (Map<String, Object> region : getRegions()) {
            Object code = region.get("sido_cd");
            String sidoCd = code == null ? "" : String.valueOf(code);
            if (!sidoCd.isBlank() && !"11".equals(sidoCd) && !sidoCodes.contains(sidoCd)) {
                sidoCodes.add(sidoCd);
            }
        }

        long started = System.currentTimeMillis();
        int done = 0;
        for (String sidoCd : sidoCodes) {
            try {
                getMapRegionsSgg(sidoCd, null, null);
                done++;
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.info("지도 경계 예열 건너뜀(시도 {}): {}", sidoCd, e.getClass().getSimpleName());
            }
        }
        log.info("지도 경계 예열 완료: 시도 {}/{}개 ({}ms)", done, sidoCodes.size(),
                System.currentTimeMillis() - started);
    }

    /**
     * 전국 시도 집계를 화면 기본 기간으로 미리 계산해 둔다.
     *
     * <p>화면 기본값은 "가장 최근 달부터 12개월"이다(프론트 `buildMonthOptions`). 그 조합을 미리
     * 계산해 두면 사용자가 지도를 끝까지 줌아웃해도 색이 바로 보인다. 다른 기간을 고르면
     * 그때는 경계만 먼저 나가고 집계가 뒤따라온다.
     */
    private void warmUpSidoStats() {
        try {
            Integer latest = periodBound(mapper(), TRADE_TABLE, true);
            if (latest == null) {
                return;
            }
            int toYm = latest;
            int year = toYm / 100;
            int month = toYm % 100;
            // 11개월 뒤로 — 최근 달을 포함해 12개월이 된다
            month -= 11;
            while (month <= 0) {
                month += 12;
                year -= 1;
            }
            int fromYm = year * 100 + month;
            long started = System.currentTimeMillis();
            sidoStatsCache.put("sido:" + fromYm + "-" + toYm,
                    mapper().selectRegionStatsSido(fromYm, toYm));
            log.info("전국 시도 집계 예열 완료: {}~{} ({}ms)", fromYm, toYm,
                    System.currentTimeMillis() - started);
        } catch (RuntimeException e) {
            log.info("전국 시도 집계 예열 건너뜀: {}", e.getClass().getSimpleName());
        }
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

    // ── 지도 ────────────────────────────────────────────────────────────
    /**
     * 경계를 단순화하는 정도. 화면에서 보이는 차이는 없고 응답 크기만 줄어든다.
     *
     * <p>전국 시군구 원본이 37MB 라 그대로 내보낼 수 없다(단순화 0.005 → 791KB, 실측).
     * 좁은 범위일수록 섬세하게 둔다.
     */
    private static final double TOLERANCE_SIDO = 0.02;       // 전국을 한 화면에
    private static final double TOLERANCE_SGG_ALL = 0.005;   // 전국
    private static final double TOLERANCE_SGG_SIDO = 0.002;  // 한 시도 안
    private static final double TOLERANCE_EMD = 0.0005;      // 한 시군구 안의 읍면동

    private static final String EMPTY_FEATURES = "{\"type\":\"FeatureCollection\",\"features\":[]}";

    /**
     * 경계 캐시.
     *
     * <p>경계는 <b>기간과 무관하고 바뀌지도 않는다</b>. 반면 깎는(단순화) 비용이 커서 매번 읽으면
     * 서울 8.7초·전국 8.4초였다(실측). 한 번 읽어 두고 다시 쓴다 — 기간을 바꿔도 다시 읽지 않는다.
     * 키는 "sgg:11" / "emd:11680" 처럼 단계+코드다.
     */
    private final java.util.Map<String, List<Map<String, Object>>> boundaryCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 시도별 집계 캐시(기간이 키).
     *
     * <p>시도 평균가는 <b>전국 전건을 읽어야</b> 나온다(실측 4~5초). 지도를 끝까지 줌아웃할 때마다
     * 그 시간을 사람이 기다리면 쓸 수 없는 화면이 된다. 기간 조합은 화면 기본값(최근 12개월)에
     * 거의 고정되므로 기간을 키로 담아 두면 대부분 바로 맞는다. 수집이 돌 때만 값이 바뀌므로
     * 오래 들고 있어도 된다.
     */
    private final java.util.Map<String, List<Map<String, Object>>> sidoStatsCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 시도 집계를 뒤에서 계산하는 중인 기간(같은 기간을 여러 번 계산하지 않게). */
    private final java.util.Set<String> sidoStatsLoading =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 전국 시도 경계 + 평균가(GeoJSON). 지도를 끝까지 줌아웃했을 때 쓴다.
     *
     * <p><b>집계가 없으면 기다리지 않는다.</b> 전국 평균은 990만 건을 모두 읽어야 나와서 28초가
     * 걸렸다(2026-10-15 실측). 그 시간을 사람이 지도 앞에서 기다리면 멈춘 화면으로 보이므로,
     * <b>경계를 먼저 돌려주고(회색) 집계는 뒤에서 계산</b>한다. 응답의 {@code stats_pending} 이
     * true 면 화면이 잠시 뒤 다시 물어 색을 채운다.
     *
     * @param withStats false 면 집계를 아예 건드리지 않는다 — 지도가 "지금 어느 시도인가"를
     *                  판단하려고 경계만 필요할 때 쓴다(그 호출이 전국 집계를 깨우면 안 된다).
     */
    public String getMapRegionsSido(Integer fromYm, Integer toYm, boolean withStats) {
        List<Map<String, Object>> boundaries = boundaryCache.computeIfAbsent(
                "sido:ALL", key -> mapper().selectBoundarySido(TOLERANCE_SIDO));
        if (!withStats) {
            return toFeatureCollection(boundaries, null, "sido", false);
        }
        String key = "sido:" + fromYm + "-" + toYm;
        List<Map<String, Object>> stats = sidoStatsCache.get(key);
        if (stats != null) {
            return toFeatureCollection(boundaries, stats, "sido", false);
        }
        loadSidoStatsInBackground(key, fromYm, toYm);
        return toFeatureCollection(boundaries, null, "sido", true);
    }

    private void loadSidoStatsInBackground(String key, Integer fromYm, Integer toYm) {
        if (!sidoStatsLoading.add(key)) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                long started = System.currentTimeMillis();
                List<Map<String, Object>> stats = mapper().selectRegionStatsSido(fromYm, toYm);
                sidoStatsCache.put(key, stats);
                log.info("전국 시도 집계 완료: {} ({}ms)", key, System.currentTimeMillis() - started);
            } catch (RuntimeException e) {
                log.info("전국 시도 집계 실패: {}", e.getClass().getSimpleName());
            } finally {
                sidoStatsLoading.remove(key);
            }
        }, "estate-sido-stats");
        worker.setDaemon(true);
        worker.start();
    }

    /** 시군구 경계 + 평균가(GeoJSON). 시도를 주면 그 안만 — 전국은 경계가 크다. */
    public String getMapRegionsSgg(String sidoCd, Integer fromYm, Integer toYm) {
        boolean hasSido = sidoCd != null && !sidoCd.isBlank();
        double tolerance = hasSido ? TOLERANCE_SGG_SIDO : TOLERANCE_SGG_ALL;
        List<Map<String, Object>> boundaries = boundaryCache.computeIfAbsent(
                "sgg:" + (hasSido ? sidoCd : "ALL"),
                key -> mapper().selectBoundarySgg(hasSido ? sidoCd : null, tolerance));
        List<Map<String, Object>> stats = mapper().selectRegionStatsSgg(hasSido ? sidoCd : null, fromYm, toYm);
        return toFeatureCollection(boundaries, stats, "sgg");
    }

    /** 한 시군구 안의 읍면동 경계 + 평균가(GeoJSON). */
    public String getMapRegionsEmd(String sggCd, Integer fromYm, Integer toYm) {
        List<Map<String, Object>> boundaries = boundaryCache.computeIfAbsent(
                "emd:" + sggCd, key -> mapper().selectBoundaryEmd(sggCd, TOLERANCE_EMD));
        List<Map<String, Object>> stats = mapper().selectRegionStatsEmd(sggCd, fromYm, toYm);
        return toFeatureCollection(boundaries, stats, "emd");
    }

    // ── 지도 단지 핀 ─────────────────────────────────────────────────────

    /** 단지 핀 1회 조회 상한. 호갱노노처럼 라벨을 달기 때문에 너무 많으면 글자가 겹친다. */
    private static final int PIN_LIMIT_MAX = 500;

    /**
     * 단지 마스터 표가 있는지. <b>없을 수 있다</b> — DDL 은 DB 담당자가 실행하는 규칙이라
     * 코드가 먼저 배포되는 상황이 정상이다. 없는데 조회하면 500 이 나므로 먼저 확인하고,
     * 결과를 들고 있다가 <b>60초마다 다시 본다</b>(표가 생기면 재기동 없이 반영되도록).
     */
    private volatile boolean complexTableExists = false;
    private volatile long complexTableCheckedAt = 0L;

    private boolean complexTableReady() {
        long now = System.currentTimeMillis();
        if (complexTableExists || now - complexTableCheckedAt > 60_000L) {
            try {
                complexTableExists = mapper().existsComplexTable() != null;
            } catch (RuntimeException e) {
                complexTableExists = false;
            }
            complexTableCheckedAt = now;
        }
        return complexTableExists;
    }

    /**
     * 화면 영역 안의 단지 핀(GeoJSON).
     *
     * <p>좌표가 아직 없으면 <b>빈 목록과 함께 그 사실을 응답에 담는다</b> — 화면이 "핀이 없는
     * 지도"와 "아직 좌표를 못 받은 지도"를 구분해 알려 줄 수 있어야 한다. 수집 진행률(전체/받은 수)도
     * 같이 내려 화면에 "44,581개 중 0개" 처럼 보여 준다.
     */
    public String getMapComplexPins(
            double minX, double minY, double maxX, double maxY, Integer fromYm, Integer toYm, int limit) {
        int capped = Math.max(1, Math.min(limit, PIN_LIMIT_MAX));
        if (!complexTableReady()) {
            return "{\"type\":\"FeatureCollection\",\"features\":[],\"coordinates_ready\":false,"
                    + "\"reason\":\"NO_TABLE\",\"message\":\"단지 좌표 표가 아직 만들어지지 않았습니다.\"}";
        }

        long total = 0;
        long geocoded = 0;
        try {
            Map<String, Object> progress = mapper().selectComplexGeocodeStats();
            if (progress != null) {
                total = asLong(progress.get("total"));
                geocoded = asLong(progress.get("geocoded"));
            }
        } catch (RuntimeException e) {
            log.info("좌표 진행률을 읽지 못했다: {}", e.getClass().getSimpleName());
        }

        List<Map<String, Object>> pins = geocoded == 0
                ? List.of()
                : mapper().selectComplexPins(minX, minY, maxX, maxY, fromYm, toYm, capped);

        StringBuilder sb = new StringBuilder(pins.size() * 256 + 256);
        sb.append("{\"type\":\"FeatureCollection\",\"coordinates_ready\":").append(geocoded > 0)
                .append(",\"complex_total\":").append(total)
                .append(",\"complex_geocoded\":").append(geocoded)
                .append(",\"features\":[");
        boolean first = true;
        for (Map<String, Object> pin : pins) {
            Object lon = pin.get("lon");
            Object lat = pin.get("lat");
            if (lon == null || lat == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                    .append(lon).append(',').append(lat).append("]},\"properties\":{")
                    .append("\"apt_seq\":\"").append(escape(String.valueOf(pin.get("apt_seq"))))
                    .append("\",\"apt_nm\":\"").append(escape(String.valueOf(pin.get("apt_nm"))))
                    .append("\",\"umd_nm\":\"").append(escape(String.valueOf(pin.get("umd_nm"))))
                    .append("\",\"sgg_cd\":\"").append(escape(String.valueOf(pin.get("sgg_cd"))))
                    .append("\",\"build_year\":").append(numberOrNull(pin, "build_year"))
                    .append(",\"deal_count\":").append(numberOrNull(pin, "deal_count"))
                    .append(",\"avg_amount\":").append(numberOrNull(pin, "avg_amount"))
                    .append(",\"avg_amount_per_m2\":").append(numberOrNull(pin, "avg_amount_per_m2"))
                    .append(",\"area_min\":").append(numberOrNull(pin, "area_min"))
                    .append(",\"area_max\":").append(numberOrNull(pin, "area_max"))
                    .append("}}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /**
     * 경계 + 집계를 GeoJSON 문자열로 합친다.
     *
     * <p>JSON 라이브러리를 쓰지 않고 이어 붙인다 — geometry 는 DB 가 만든 JSON 문자열이라 그대로
     * 넣으면 되고, 나머지는 숫자와 짧은 이름뿐이다(이름은 따옴표만 escape 한다).
     */
    private String toFeatureCollection(
            List<Map<String, Object>> boundaries, List<Map<String, Object>> stats, String level) {
        return toFeatureCollection(boundaries, stats, level, false);
    }

    private String toFeatureCollection(
            List<Map<String, Object>> boundaries, List<Map<String, Object>> stats, String level,
            boolean statsPending) {
        if (boundaries == null || boundaries.isEmpty()) {
            return EMPTY_FEATURES;
        }
        Map<String, Map<String, Object>> statByCode = new LinkedHashMap<>();
        if (stats != null) {
            for (Map<String, Object> row : stats) {
                statByCode.put(String.valueOf(row.get("code")), row);
            }
        }

        StringBuilder sb = new StringBuilder(boundaries.size() * 512);
        sb.append("{\"type\":\"FeatureCollection\"");
        if (statsPending) {
            // 화면이 "색이 아직 없는 이유"를 알고 잠시 뒤 다시 묻게 한다
            sb.append(",\"stats_pending\":true");
        }
        sb.append(",\"features\":[");
        boolean first = true;
        for (Map<String, Object> boundary : boundaries) {
            Object geometry = boundary.get("geometry");
            if (geometry == null) {
                continue;
            }
            String code = String.valueOf(boundary.get("code"));
            Map<String, Object> stat = statByCode.get(code);

            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"type\":\"Feature\",\"geometry\":").append(geometry)
                    .append(",\"properties\":{\"level\":\"").append(level)
                    .append("\",\"code\":\"").append(code)
                    .append("\",\"name\":\"").append(escape(String.valueOf(boundary.get("name"))))
                    .append("\",\"deal_count\":").append(stat == null ? "0" : String.valueOf(stat.get("deal_count")))
                    .append(",\"avg_amount\":").append(numberOrNull(stat, "avg_amount"))
                    .append(",\"avg_amount_per_m2\":").append(numberOrNull(stat, "avg_amount_per_m2"))
                    .append("}}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String numberOrNull(Map<String, Object> stat, String key) {
        if (stat == null || stat.get(key) == null) {
            return "null";
        }
        return String.valueOf(stat.get(key));
    }

    private String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── 단지(아파트) 단위 ────────────────────────────────────────────────
    // 단지 키 apt_seq 의 앞 5자리가 시군구 코드다. 인덱스가 없는 컬럼이라 **시군구 조건을 함께**
    // 걸어야 그 시군구만 읽는다(실측 134ms). 그 조립을 여기서 한 곳으로 모은다.

    /** apt_seq(예: 11680-218) 앞 5자리 = 시군구 코드. */
    private String sggCdOf(String aptSeq) {
        if (aptSeq == null || aptSeq.length() < 5) {
            throw new IllegalArgumentException("aptSeq");
        }
        return aptSeq.substring(0, 5);
    }

    public List<Map<String, Object>> getComplexes(String sggCd, String umdNm, String keyword, int limit) {
        return new ArrayList<>(mapper().selectComplexes(sggCd, umdNm, keyword, limit));
    }

    /** 단지 기본 정보 + 평형별 요약을 한 번에 돌려준다(화면이 둘을 함께 쓰기 때문). */
    public Map<String, Object> getComplexDetail(String aptSeq, String umdNm) {
        EstateMapper mapper = mapper();
        String sggCd = sggCdOf(aptSeq);
        Map<String, Object> summary = mapper.selectComplexSummary(sggCd, aptSeq, umdNm);
        if (summary == null || summary.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>(summary);
        result.put("areas", mapper.selectComplexAreas(sggCd, aptSeq, umdNm));
        return result;
    }

    public List<Map<String, Object>> getComplexMonthly(
            String aptSeq, String umdNm, Integer pyeong, Integer fromYm, Integer toYm) {
        return new ArrayList<>(
                mapper().selectComplexMonthly(sggCdOf(aptSeq), aptSeq, umdNm, pyeong, fromYm, toYm));
    }

    public List<Map<String, Object>> getComplexRentMonthly(
            String aptSeq, String umdNm, Integer pyeong, Integer fromYm, Integer toYm) {
        return new ArrayList<>(
                mapper().selectComplexRentMonthly(sggCdOf(aptSeq), aptSeq, umdNm, pyeong, fromYm, toYm));
    }

    public List<Map<String, Object>> getComplexTrades(
            String aptSeq, String umdNm, Integer pyeong, int limit) {
        return new ArrayList<>(mapper().selectComplexTrades(sggCdOf(aptSeq), aptSeq, umdNm, pyeong, limit));
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
