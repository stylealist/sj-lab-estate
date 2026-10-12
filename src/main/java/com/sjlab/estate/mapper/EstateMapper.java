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

    /** 그 표의 가장 이른(또는 늦은) 거래 월. 식으로 비교하지 않아 기간 인덱스를 그대로 쓴다. */
    Map<String, Object> selectPeriodBound(@Param("table") String table, @Param("desc") boolean desc);

    /** 마지막 적재 시각. 가장 최근 거래 월로 범위를 좁혀야 전건 스캔을 피한다. */
    Map<String, Object> selectLastLoad(
            @Param("table") String table,
            @Param("dealYear") int dealYear,
            @Param("dealMonth") int dealMonth);

    /** 거래가 있는 시군구 목록(코드 · 이름 · 건수). */
    List<Map<String, Object>> selectRegions();

    /** 매매 월별 추세 — 지역을 고르면 집계 뷰, 전국이면 원본에서 다시 합친다. */
    List<Map<String, Object>> selectTradeMonthlyBySgg(
            @Param("sggCd") String sggCd, @Param("fromYm") Integer fromYm, @Param("toYm") Integer toYm);

    List<Map<String, Object>> selectTradeMonthlyNationwide(
            @Param("fromYm") Integer fromYm, @Param("toYm") Integer toYm);

    /** 전월세 월별 추세(전세 = 월세가 0). */
    List<Map<String, Object>> selectRentMonthlyBySgg(
            @Param("sggCd") String sggCd, @Param("fromYm") Integer fromYm, @Param("toYm") Integer toYm);

    List<Map<String, Object>> selectRentMonthlyNationwide(
            @Param("fromYm") Integer fromYm, @Param("toYm") Integer toYm);

    /** 매매 거래 목록(최근 계약일 순). */
    List<Map<String, Object>> selectTrades(
            @Param("sggCd") String sggCd,
            @Param("fromYm") Integer fromYm,
            @Param("toYm") Integer toYm,
            @Param("aptNm") String aptNm,
            @Param("limit") int limit);

    /** 지역별 평균 매매가 순위(거래 10건 이상인 지역만). */
    List<Map<String, Object>> selectTradeRanking(
            @Param("fromYm") Integer fromYm, @Param("toYm") Integer toYm, @Param("limit") int limit);
}
