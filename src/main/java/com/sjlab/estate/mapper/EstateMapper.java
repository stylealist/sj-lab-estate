package com.sjlab.estate.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 부동산 실거래 조회 매퍼. SQL 은 {@code resources/mapper/EstateMapper.xml} 에 모은다.
 *
 * <p>결과를 DTO 가 아니라 {@code Map} 으로 받는 이유: 응답 키가 <b>DB 컬럼명 그대로</b>여야
 * 한다(화면 코드가 {@code deal_count}, {@code avg_amount} 같은 이름을 그대로 읽는다).
 * DTO 로 받으면 Jackson 이 camelCase 로 바꿔 내보내 화면을 전부 고쳐야 한다.
 */
@Mapper
public interface EstateMapper {

    /** 표의 대략적인 건수(통계 기반 추정치). 정확히 세면 2,100만 행에서 30초가 걸린다. */
    Map<String, Object> selectRowEstimates();

    /**
     * 조회가 실제로 되는지 확인하는 probe.
     *
     * <p><b>통계(pg_class)만 읽어서는 안 된다</b> — 그 카탈로그는 권한이 없는 계정도 읽을 수 있어
     * "정상"으로 보고된 뒤 실제 조회만 권한 오류로 실패한다(2026-10-13, estate 읽기 권한이 없는
     * 계정으로 바꾸며 확인). 그래서 실제 표에서 한 행을 읽어 본다.
     */
    Integer selectReadinessProbe();

    /** 그 표의 가장 이른(또는 늦은) 거래 월. 식으로 비교하지 않아 기간 인덱스를 그대로 쓴다. */
    Map<String, Object> selectPeriodBound(@Param("table") String table, @Param("desc") boolean desc);

    /** 마지막 적재 시각. 가장 최근 거래 월로 범위를 좁혀야 전건 스캔을 피한다. */
    Map<String, Object> selectLastLoad(
            @Param("table") String table,
            @Param("dealYear") int dealYear,
            @Param("dealMonth") int dealMonth);

    /** 거래가 있는 시군구 목록(코드 · 이름 · 건수). 시도 이름은 아래 조회로 따로 붙인다. */
    List<Map<String, Object>> selectRegions();

    /** 시도 코드·이름(17행). 시군구 코드 앞 2자리로 이어 붙인다. */
    List<Map<String, Object>> selectSidoNames();

    /** 그 시군구 안에서 거래가 있는 읍면동 목록(이름 · 건수). */
    List<Map<String, Object>> selectEmdList(@Param("sggCd") String sggCd);

    /** 매매 월별 추세 — 지역을 고르면 집계 뷰, 전국이면 원본에서 다시 합친다. */
    List<Map<String, Object>> selectTradeMonthlyBySgg(
            @Param("sggCd") String sggCd, @Param("fromYm") Integer fromYm, @Param("toYm") Integer toYm);

    List<Map<String, Object>> selectTradeMonthlyNationwide(
            @Param("fromYm") Integer fromYm, @Param("toYm") Integer toYm);

    /** 읍면동까지 좁힌 매매 추세(집계 뷰는 시군구 단위라 쓸 수 없다). */
    List<Map<String, Object>> selectTradeMonthlyByUmd(
            @Param("sggCd") String sggCd,
            @Param("umdNm") String umdNm,
            @Param("fromYm") Integer fromYm,
            @Param("toYm") Integer toYm);

    /** 전월세 월별 추세(전세 = 월세가 0). */
    List<Map<String, Object>> selectRentMonthlyBySgg(
            @Param("sggCd") String sggCd, @Param("fromYm") Integer fromYm, @Param("toYm") Integer toYm);

    List<Map<String, Object>> selectRentMonthlyNationwide(
            @Param("fromYm") Integer fromYm, @Param("toYm") Integer toYm);

    /** 읍면동까지 좁힌 전월세 추세. */
    List<Map<String, Object>> selectRentMonthlyByUmd(
            @Param("sggCd") String sggCd,
            @Param("umdNm") String umdNm,
            @Param("fromYm") Integer fromYm,
            @Param("toYm") Integer toYm);

    /** 매매 거래 목록(최근 계약일 순). */
    List<Map<String, Object>> selectTrades(
            @Param("sggCd") String sggCd,
            @Param("umdNm") String umdNm,
            @Param("fromYm") Integer fromYm,
            @Param("toYm") Integer toYm,
            @Param("aptNm") String aptNm,
            @Param("limit") int limit);

    // ── 지도용 지역 집계(GeoJSON 텍스트) ────────────────────────────────
    // 좌표가 없어도 되는 지도다 — 행정구역 경계는 이미 DB 에 있다.
    // 경계를 그대로 내보내면 전국 시군구가 37MB 라 반드시 단순화(tolerance)해서 쓴다.

    /** 시군구 경계(코드·이름·geometry). **기간과 무관하다** — 서비스가 캐시한다. */
    /** 시도 경계(17개). */
    List<Map<String, Object>> selectBoundarySido(@Param("tolerance") double tolerance);

    List<Map<String, Object>> selectBoundarySgg(
            @Param("sidoCd") String sidoCd, @Param("tolerance") double tolerance);

    /** 한 시군구 안의 읍면동 경계. */
    List<Map<String, Object>> selectBoundaryEmd(
            @Param("sggCd") String sggCd, @Param("tolerance") double tolerance);

    /** 시군구별 평균가(기간 조건). */
    List<Map<String, Object>> selectRegionStatsSgg(
            @Param("sidoCd") String sidoCd, @Param("fromYm") Integer fromYm, @Param("toYm") Integer toYm);

    /** 시도별 평균가(기간 조건). 전국 전건을 읽어 느리다 — 서비스가 캐시한다. */
    List<Map<String, Object>> selectRegionStatsSido(
            @Param("fromYm") Integer fromYm, @Param("toYm") Integer toYm);

    /** 읍면동별 평균가(기간 조건). */
    List<Map<String, Object>> selectRegionStatsEmd(
            @Param("sggCd") String sggCd, @Param("fromYm") Integer fromYm, @Param("toYm") Integer toYm);

    // ── 지도 단지 핀 ─────────────────────────────────────────────────────

    /** 단지 마스터 표가 있는지(없으면 null). DDL 은 담당자가 실행하므로 없을 수 있다. */
    String existsComplexTable();

    /** 좌표 수집 진행률(전체·받은 수·실패). */
    Map<String, Object> selectComplexGeocodeStats();

    /** 테두리 컬럼이 있는지(없으면 null). 좌표 표보다 나중에 더해진 컬럼이다. */
    String existsComplexBoundaryColumn();

    /** 고른 단지 하나의 테두리(연속지적도 필지, EPSG:4326 GeoJSON). */
    Map<String, Object> selectComplexBoundary(
            @Param("sggCd") String sggCd, @Param("aptSeq") String aptSeq);

    /**
     * 화면 영역(EPSG:3857) 안의 단지 핀 + 기간 평균가. <b>Feature 배열 JSON 문자열</b>을 돌려준다 —
     * 행 목록을 Map 으로 옮기는 것만으로 단지 381개에 400ms 가 들었다(2026-10-28).
     */
    String selectComplexPins(
            @Param("minX") double minX,
            @Param("minY") double minY,
            @Param("maxX") double maxX,
            @Param("maxY") double maxY,
            @Param("fromYm") Integer fromYm,
            @Param("toYm") Integer toYm,
            @Param("limit") int limit);

    // ── 단지(아파트) 단위 ────────────────────────────────────────────────
    // 단지 키는 apt_seq 이고 앞 5자리가 시군구 코드다. **인덱스가 없어 sgg_cd 를 반드시 함께**
    // 넘긴다(없으면 990만 건 전수 조회). 읍면동(umdNm)을 주면 더 좁아진다.

    /** 지역 안의 단지 목록(거래 많은 순). 단지명 일부로 찾을 수 있다. */
    List<Map<String, Object>> selectComplexes(
            @Param("sggCd") String sggCd,
            @Param("umdNm") String umdNm,
            @Param("keyword") String keyword,
            @Param("limit") int limit);

    /** 단지 기본 정보(이름·주소·준공연도·거래 기간). */
    Map<String, Object> selectComplexSummary(
            @Param("sggCd") String sggCd, @Param("aptSeq") String aptSeq, @Param("umdNm") String umdNm);

    /** 평형별 요약(건수·평균·면적 범위 + 그 평형의 가장 최근 거래). 평형은 **전용면적** 기준. */
    List<Map<String, Object>> selectComplexAreas(
            @Param("sggCd") String sggCd, @Param("aptSeq") String aptSeq, @Param("umdNm") String umdNm);

    /** 단지(평형) 월별 매매 추세. */
    List<Map<String, Object>> selectComplexMonthly(
            @Param("sggCd") String sggCd,
            @Param("aptSeq") String aptSeq,
            @Param("umdNm") String umdNm,
            @Param("pyeong") Integer pyeong,
            @Param("fromYm") Integer fromYm,
            @Param("toYm") Integer toYm);

    /** 단지(평형) 월별 전월세. */
    List<Map<String, Object>> selectComplexRentMonthly(
            @Param("sggCd") String sggCd,
            @Param("aptSeq") String aptSeq,
            @Param("umdNm") String umdNm,
            @Param("pyeong") Integer pyeong,
            @Param("fromYm") Integer fromYm,
            @Param("toYm") Integer toYm);

    /** 단지(평형) 최근 거래 목록. */
    List<Map<String, Object>> selectComplexTrades(
            @Param("sggCd") String sggCd,
            @Param("aptSeq") String aptSeq,
            @Param("umdNm") String umdNm,
            @Param("pyeong") Integer pyeong,
            @Param("limit") int limit);

    /** 지역별 평균 매매가 순위(거래 10건 이상인 지역만). */
    List<Map<String, Object>> selectTradeRanking(
            @Param("sidoCd") String sidoCd,
            @Param("fromYm") Integer fromYm,
            @Param("toYm") Integer toYm,
            @Param("limit") int limit);
}
