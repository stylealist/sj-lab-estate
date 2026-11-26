package com.sjlab.estate.service;

import com.sjlab.estate.controller.EstateController;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 회원 데이터 — <b>즐겨찾기 · 최근 본 단지 · 단지 메모</b>.
 *
 * <p>이 서비스에서 <b>유일하게 쓰기를 하는 곳</b>이다. 실거래 표는 종전대로 읽기 전용 커넥션으로만
 * 보고, 여기서는 {@code member} 스키마만 쓰기 가능한 작은 풀을 쓴다({@code MemberDataSourceConfig}).
 *
 * <p><b>표가 없으면 오류가 아니라 "준비 안 됨" 이다.</b> DDL 은 DB 담당자가 실행하므로, 아직
 * 만들지 않은 상태가 정상적인 상태다. 그때는 {@code ready:false} 로 답하고 화면이 즐겨찾기
 * 단추를 숨긴다 — 지도·단지 조회는 전혀 영향이 없다. 한 번 "있다"고 확인한 뒤에는 뒤집지
 * 않는다(실거래 단지 표에서 겪은 것과 같은 이유 — DB 가 잠깐 끊겼다고 기능이 사라지면 안 된다).
 */
@Service
public class MemberService {

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    /** 표가 아직 없을 때 다시 확인하는 간격. 스크립트를 실행하면 재기동 없이 켜진다. */
    private static final long PROBE_TTL_MS = 60_000L;
    /** 한 사람의 즐겨찾기·최근 본 단지 상한. 화면이 한 번에 보여 줄 수 있는 양을 넘기지 않는다. */
    private static final int FAVORITE_MAX = 200;
    private static final int RECENT_KEEP = 30;
    private static final int NOTE_MAX_LENGTH = 1000;

    /** 목표 금액의 상한(만원) — 1,000억. 오타로 0 을 더 찍은 값이 저장되지 않게 한다. */
    private static final int TARGET_MAX_AMOUNT = 10_000_000;

    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    private volatile boolean tablesReady = false;
    private volatile long probedAt = 0L;

    /**
     * 목표가 컬럼이 있는지. 표 확인과 <b>따로</b> 둔다 — 표는 있는데 컬럼만 아직 없는 상태가
     * 정상적인 상태이고(스크립트를 나중에 실행), 그때 관심 담기·메모까지 함께 꺼지면 안 된다.
     */
    private volatile boolean targetReady = false;
    private volatile long targetProbedAt = 0L;

    /** 카카오 알림의 "보낸 기록" 컬럼. 이것도 목표가 컬럼과 따로 확인한다(스크립트가 다르다). */
    private volatile boolean noticeReady = false;
    private volatile long noticeProbedAt = 0L;

    /**
     * <b>@Qualifier 를 빼지 말 것.</b> Spring Boot 는 DataSource 가 하나로 보이면
     * (실거래 쪽이 @Primary 다) 그 위에 {@code jdbcTemplate} 빈을 자동으로 하나 더 만든다.
     * 이름을 집어 주지 않으면 후보가 둘이라 {@code getIfAvailable()} 이 그냥 null 을 돌려주고,
     * 회원 기능이 "설정 없음" 으로 조용히 꺼진다. 집어 줘도 엉뚱한 쪽(읽기 전용)이 잡히면
     * 즐겨찾기 저장이 DB 에서 거부된다.
     */
    public MemberService(
            @Qualifier("memberJdbcTemplate") ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    // ── 준비 상태 ────────────────────────────────────────────────────────────

    /** 화면이 "즐겨찾기를 쓸 수 있는 상태인지" 묻는 곳. 로그인 없이도 부를 수 있다. */
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean configured = jdbcProvider.getIfAvailable() != null;
        boolean ready = configured && tablesReady();
        result.put("ready", ready);
        result.put("configured", configured);
        if (!configured) {
            result.put("reason", "NOT_CONFIGURED");
            result.put("message", "DB 접속 정보가 없어 회원 기능을 쓸 수 없습니다.");
        } else if (!ready) {
            result.put("reason", "NO_TABLE");
            result.put("message", "회원 데이터 표가 아직 만들어지지 않았습니다.");
        } else {
            result.put("reason", "READY");
            result.put("message", "즐겨찾기·메모를 쓸 수 있습니다.");
        }
        /*
          목표가 알림은 **같은 표에 컬럼만 더한 기능**이라 따로 알린다
          (`db/estate/estate_member_target.sql`, 2026-11-25). 컬럼이 없으면 화면이 목표가
          단추를 숨기고 나머지는 그대로 쓴다 — 눌러도 저장되지 않는 단추를 두지 않는다.
        */
        result.put("target_ready", ready && targetColumnsReady());
        return result;
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate jdbcTemplate = jdbcProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new EstateController.NotReadyException(
                    "NOT_CONFIGURED", "DB 접속 정보가 없어 회원 기능을 쓸 수 없습니다.");
        }
        if (!tablesReady()) {
            throw new EstateController.NotReadyException(
                    "NO_TABLE", "회원 데이터 표가 아직 만들어지지 않았습니다. 관리자에게 문의해 주세요.");
        }
        return jdbcTemplate;
    }

    private boolean tablesReady() {
        // 한 번 있다고 확인했으면 다시 묻지 않는다 — 표는 사라지지 않는다.
        if (tablesReady) {
            return true;
        }
        if (System.currentTimeMillis() - probedAt < PROBE_TTL_MS) {
            return false;
        }
        probedAt = System.currentTimeMillis();
        JdbcTemplate jdbcTemplate = jdbcProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return false;
        }
        try {
            Integer found = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.tables"
                            + " where table_schema = 'member'"
                            + "   and table_name in ('estate_favorite', 'estate_recent', 'estate_note')",
                    Integer.class);
            tablesReady = found != null && found == 3;
        } catch (RuntimeException e) {
            log.info("회원 표 확인 실패: {}", e.getClass().getSimpleName());
            tablesReady = false;
        }
        return tablesReady;
    }

    /**
     * 목표가 컬럼 6개가 다 있는지. 표 확인과 <b>같은 규칙</b>이다 — 한 번 있다고 확인하면
     * 뒤집지 않고, 없으면 60초마다 다시 묻는다(스크립트를 실행하면 재기동 없이 켜진다).
     *
     * <p>6개를 모두 세는 이유: 일부만 있는 상태로 질의를 보내면 <b>목록 전체가 오류</b>가 되어
     * 관심 단지가 통째로 안 보인다. 컬럼을 더할 때 이 숫자도 함께 고칠 것.
     */
    private boolean targetColumnsReady() {
        if (targetReady) {
            return true;
        }
        if (!tablesReady()) {
            return false;
        }
        if (System.currentTimeMillis() - targetProbedAt < PROBE_TTL_MS) {
            return false;
        }
        targetProbedAt = System.currentTimeMillis();
        JdbcTemplate jdbcTemplate = jdbcProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return false;
        }
        try {
            Integer found = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.columns"
                            + " where table_schema = 'member' and table_name = 'estate_favorite'"
                            + "   and column_name in ('target_amount', 'target_kind', 'target_dir',"
                            + "                       'target_pyeong', 'target_base_ymd', 'target_at')",
                    Integer.class);
            targetReady = found != null && found == 6;
        } catch (RuntimeException e) {
            log.info("목표가 컬럼 확인 실패: {}", e.getClass().getSimpleName());
            targetReady = false;
        }
        return targetReady;
    }

    private void requireTarget() {
        if (!targetColumnsReady()) {
            throw new EstateController.NotReadyException(
                    "NO_TARGET_COLUMN",
                    "목표가 알림이 아직 준비되지 않았습니다(관심 단지 표에 컬럼을 더해야 합니다).");
        }
    }

    // ── 즐겨찾기 ─────────────────────────────────────────────────────────────

    /**
     * 내 즐겨찾기 목록. <b>단지마다 "마지막으로 본 뒤 올라온 새 거래"를 함께 센다.</b>
     *
     * <p>한 단지씩 따로 묻지 않고 {@code left join lateral} 로 <b>한 번에</b> 가져온다 —
     * 즐겨찾기 40개면 왕복이 80번이 된다. 안쪽 질의는 {@code (sgg_cd, apt_seq)} 인덱스
     * (2026-11-17 추가)를 타므로 단지 하나당 1ms 아래다. <b>그 인덱스가 없으면 이 기능은
     * 쓸 수 없을 만큼 느려진다</b>(그때는 단지 하나에 2초였다).
     *
     * <p>계약일을 {@code 연*10000 + 월*100 + 일} 로 만드는 것은 <b>조건이 아니라 집계</b>다 —
     * 이미 단지 하나로 좁힌 뒤라 인덱스와 무관하다(기간 조건을 식으로 쓰지 말라는 규칙은
     * 표 전체를 훑게 되는 경우에 대한 것이다).
     *
     * <p><b>좌표(`lon`·`lat`)를 함께 보낸다</b>(2026-11-25). 없으면 화면이 "지도에서 보기" 를
     * 눌러도 <b>패널만 열리고 지도는 그대로</b>다 — 어느 단지인지 보이지 않는다.
     * {@code estate.apt_complex.apt_seq} 가 기본키라 단지당 색인 한 번이고, 목록은 많아야
     * {@value #FAVORITE_MAX} 건이라 비용이 사실상 없다. 좌표를 아직 못 받은 단지는 null 이고,
     * 화면은 그때 지도를 옮기지 않는다.
     */
    public List<Map<String, Object>> listFavorites(String username) {
        return favoriteRows(username, false);
    }

    /**
     * 목표가를 정해 둔 관심 단지만 — 지도·팝업의 🔔 표시용.
     *
     * <p><b>목록 전체를 세지 않는 것이 핵심이다.</b> 이 질의는 화면을 열 때마다 불리는데,
     * 조건이 {@code target_amount is not null} 이라 목표가를 하나도 안 정했으면 <b>행이 0개</b>라
     * 무거운 {@code lateral} 이 아예 돌지 않는다. 비용은 "관심 단지 수" 가 아니라
     * "목표가를 정한 단지 수" 에 비례한다.
     */
    public List<Map<String, Object>> listTargets(String username) {
        if (!targetColumnsReady()) {
            return List.of();
        }
        return favoriteRows(username, true);
    }

    /**
     * 관심 단지 목록의 본체. {@code onlyTargets} 면 목표가를 정한 것만 추린다.
     *
     * <p><b>목표가 컬럼이 없으면 그 부분을 질의에서 통째로 뺀다</b> — 없는 컬럼을 적으면
     * 목록 전체가 오류가 되어 관심 단지가 하나도 안 보인다. 스크립트를 실행하기 전에도
     * 종전 기능은 그대로 돌아야 한다.
     */
    private List<Map<String, Object>> favoriteRows(String username, boolean onlyTargets) {
        boolean withTarget = targetColumnsReady();
        String sql =
                "select f.apt_seq, f.property_type, f.sgg_cd,"
                        // **이름은 단지 표로 보완한다**(2026-11-26). 담을 때 이름을 안 보낸 경로가
                        // 있으면 목록에 `11650-3500` 같은 키가 그대로 찍힌다(사용자 지적).
                        + "       coalesce(f.umd_nm, c.umd_nm) as umd_nm,"
                        + "       coalesce(f.apt_nm, c.apt_nm) as apt_nm, f.seen_deal_ymd,"
                        + "       to_char(f.reg_date, 'YYYY-MM-DD HH24:MI:SS') as reg_date,"
                        + "       n.note,"
                        + "       c.lon, c.lat, c.build_year,"
                        + "       coalesce(t.last_ymd, 0)  as trade_last_ymd,"
                        + "       coalesce(t.new_count, 0) as trade_new_count,"
                        + "       t.last_amount            as trade_last_amount,"
                        + "       coalesce(r.last_ymd, 0)  as rent_last_ymd,"
                        + "       coalesce(r.new_count, 0) as rent_new_count,"
                        + "       r.last_amount            as rent_last_amount"
                        + (withTarget ? targetSelect() : "")
                        + "  from member.estate_favorite f"
                        + "  left join member.estate_note n"
                        + "    on n.username = f.username and n.apt_seq = f.apt_seq"
                        + "  left join estate.apt_complex c on c.apt_seq = f.apt_seq"
                        + "  left join lateral ("
                        + "       select max(x.deal_year * 10000 + x.deal_month * 100 + x.deal_day) as last_ymd,"
                        + "              count(*) filter ("
                        + "                 where x.deal_year * 10000 + x.deal_month * 100 + x.deal_day"
                        + "                       > f.seen_deal_ymd) as new_count,"
                        // 최근 거래가 — 목표가와 나란히 보여 줘야 "얼마나 남았나" 가 읽힌다.
                        // 날짜를 앞에 둔 배열의 max 라 **가장 최근 거래의 금액**이고, 정렬이 없어
                        // 건수가 많은 단지(1.4만 건)에서도 한 번 훑는 비용 그대로다.
                        + "              (max(array[(x.deal_year * 10000 + x.deal_month * 100"
                        + "                          + x.deal_day)::bigint, x.deal_amount]))[2] as last_amount"
                        + (withTarget ? targetAggregate("x.deal_amount",
                                "coalesce(f.target_kind, 'trade') = 'trade'", "") : "")
                        + "         from estate.apt_trade x"
                        + "        where x.sgg_cd = coalesce(f.sgg_cd, left(f.apt_seq, 5))"
                        + "          and x.apt_seq = f.apt_seq) t on true"
                        + "  left join lateral ("
                        + "       select max(x.deal_year * 10000 + x.deal_month * 100 + x.deal_day) as last_ymd,"
                        + "              count(*) filter ("
                        + "                 where x.deal_year * 10000 + x.deal_month * 100 + x.deal_day"
                        + "                       > f.seen_deal_ymd) as new_count,"
                        // 전월세의 "최근 금액" 은 **전세 보증금**이다(월세 보증금과 섞으면 읽히지 않는다)
                        + "              (max(array[(x.deal_year * 10000 + x.deal_month * 100"
                        + "                          + x.deal_day)::bigint, x.deposit])"
                        + "                 filter (where x.monthly_rent = 0))[2] as last_amount"
                        + (withTarget ? targetAggregate("x.deposit",
                                "f.target_kind = 'jeonse'", " and x.monthly_rent = 0") : "")
                        + "         from estate.apt_rent x"
                        + "        where x.sgg_cd = coalesce(f.sgg_cd, left(f.apt_seq, 5))"
                        + "          and x.apt_seq = f.apt_seq) r on true"
                        + " where f.username = ?"
                        + (onlyTargets ? "   and f.target_amount is not null" : "")
                        + " order by f.reg_date desc"
                        + " limit " + FAVORITE_MAX;
        return jdbc().queryForList(sql, username);
    }

    /**
     * 목표가 관련 바깥 컬럼. 어느 쪽 {@code lateral} 에서 가져올지는 <b>종류가 정한다</b> —
     * 전세 목표면 전월세 쪽, 그 밖에는 매매 쪽이다(종류를 안 적었으면 매매로 본다).
     */
    private String targetSelect() {
        return ",      f.target_amount, f.target_kind, f.target_dir, f.target_pyeong,"
                + "       to_char(f.target_at, 'YYYY-MM-DD HH24:MI:SS') as target_at,"
                + "       case when f.target_kind = 'jeonse' then coalesce(r.hit_count, 0)"
                + "            else coalesce(t.hit_count, 0) end as target_hit_count,"
                + "       case when f.target_kind = 'jeonse' then r.hit_ymd"
                + "            else t.hit_ymd end as target_hit_ymd,"
                + "       case when f.target_kind = 'jeonse' then r.hit_amount"
                + "            else t.hit_amount end as target_hit_amount";
    }

    /**
     * 목표가에 닿은 거래를 세는 집계 — 매매·전월세 {@code lateral} 에 같은 모양으로 들어간다.
     *
     * <p>세 가지를 <b>같은 배열 하나</b>에서 꺼낸다(날짜를 앞에 둔 {@code max(array[...])}).
     * 금액과 날짜가 따로 놀면 "8.5억(11-20)" 처럼 <b>서로 다른 거래의 숫자가 한 줄에</b>
     * 적힐 수 있다 — 가장 최근에 닿은 거래 하나를 가리키게 한다.
     *
     * <p>조건에 {@code target_base_ymd}(목표가를 정한 시점의 최근 계약일)가 들어가는 것이
     * 중요하다. 없으면 <b>정하자마자 과거 거래가 전부 "도달"</b> 로 떠서 알림이 무의미해진다.
     *
     * @param amountExpr 비교할 금액 컬럼 — 매매는 {@code deal_amount}, 전세는 {@code deposit}
     * @param kindCond   이 {@code lateral} 이 맡을 종류인지
     * @param extraCond  종류별 추가 조건(전세는 월세 0 인 것만)
     */
    private String targetAggregate(String amountExpr, String kindCond, String extraCond) {
        String ymd = "(x.deal_year * 10000 + x.deal_month * 100 + x.deal_day)";
        String cond = "(" + kindCond
                + "   and f.target_amount is not null"
                + "   and " + ymd + " > f.target_base_ymd" + extraCond
                + "   and (f.target_pyeong is null"
                + "        or round(x.exclu_use_ar / 3.3058 / 0.75) = f.target_pyeong)"
                + "   and ((f.target_dir = 'above'  and " + amountExpr + " >= f.target_amount)"
                + "     or (f.target_dir is distinct from 'above'"
                + "         and " + amountExpr + " <= f.target_amount)))";
        String best = "max(array[" + ymd + "::bigint, " + amountExpr + "]) filter (where " + cond + ")";
        return ",              count(*) filter (where " + cond + ") as hit_count,"
                + "              (" + best + ")[1] as hit_ymd,"
                + "              (" + best + ")[2] as hit_amount";
    }

    /**
     * 즐겨찾기에 담는다. 이미 있으면 아무 일도 하지 않는다(단추를 두 번 눌러도 안전하다).
     *
     * <p><b>담는 순간의 "가장 최근 계약일" 을 기준점으로 함께 적는다.</b> 0 으로 두면 담자마자
     * 과거 거래 전부가 "새 거래 N건" 으로 뜬다.
     */
    public Map<String, Object> addFavorite(
            String username, String propertyType, String aptSeq, String umdNm, String aptNm) {
        String sggCd = sggCdOf(aptSeq);
        int count = countFavorites(username);
        if (count >= FAVORITE_MAX) {
            throw new EstateController.BadRequestException(
                    "FAVORITE_LIMIT", "즐겨찾기는 " + FAVORITE_MAX + "개까지 담을 수 있습니다.");
        }
        String sql =
                "insert into member.estate_favorite"
                        + " (username, apt_seq, property_type, sgg_cd, umd_nm, apt_nm, seen_deal_ymd, seen_at)"
                        + " values (?, ?, ?, ?, ?, ?, greatest("
                        + "   coalesce((select max(deal_year * 10000 + deal_month * 100 + deal_day)"
                        + "               from estate.apt_trade where sgg_cd = ? and apt_seq = ?), 0),"
                        + "   coalesce((select max(deal_year * 10000 + deal_month * 100 + deal_day)"
                        + "               from estate.apt_rent  where sgg_cd = ? and apt_seq = ?), 0)), now())"
                        + " on conflict (username, apt_seq) do nothing";
        jdbc().update(sql, username, aptSeq, propertyType, sggCd, umdNm, aptNm,
                sggCd, aptSeq, sggCd, aptSeq);
        return Map.of("apt_seq", aptSeq, "favorite", true);
    }

    /**
     * 관심에서 뺀다. <b>메모와 목표가도 함께 지운다</b>(2026-11-26 사용자 결정).
     *
     * <p>목표가는 관심 단지 행에 붙어 있어 전부터 함께 사라졌는데, 메모는 다른 표라 남아
     * "메모만 남긴 단지" 로 옮겨 갔다. <b>한 동작에 어떤 것은 지워지고 어떤 것은 남는 것이
     * 더 헷갈린다</b>는 판단이다 — 뺄 때 그 단지에 달아 둔 것을 다 거둔다.
     *
     * <p><b>화면이 미리 알려야 한다.</b> 지워진 것을 돌려줄 수 없으므로, 메모나 목표가가 있을 때는
     * 별을 끄기 전에 "함께 지워집니다" 를 묻는다(프론트 `FavoriteBar` · `MyPage` · `MyEstate`).
     * 응답의 {@code note_removed}·{@code target_removed} 는 실제로 무엇이 지워졌는지다.
     */
    public Map<String, Object> removeFavorite(String username, String aptSeq) {
        boolean hadTarget = false;
        if (targetColumnsReady()) {
            Integer found = jdbc().queryForObject(
                    "select count(*) from member.estate_favorite"
                            + " where username = ? and apt_seq = ? and target_amount is not null",
                    Integer.class, username, aptSeq);
            hadTarget = found != null && found > 0;
        }
        jdbc().update(
                "delete from member.estate_favorite where username = ? and apt_seq = ?",
                username, aptSeq);
        int notes = jdbc().update(
                "delete from member.estate_note where username = ? and apt_seq = ?",
                username, aptSeq);
        return Map.of(
                "apt_seq", aptSeq,
                "favorite", false,
                "note_removed", notes > 0,
                "target_removed", hadTarget);
    }

    /**
     * "새 거래" 표시를 지운다 — 기준점을 지금의 최근 계약일로 올린다.
     * 화면이 그 단지 상세를 열 때 부른다.
     */
    public Map<String, Object> markSeen(String username, String aptSeq) {
        String sggCd = sggCdOf(aptSeq);
        String sql =
                "update member.estate_favorite"
                        + "    set seen_deal_ymd = greatest("
                        + "          coalesce((select max(deal_year * 10000 + deal_month * 100 + deal_day)"
                        + "                      from estate.apt_trade where sgg_cd = ? and apt_seq = ?), 0),"
                        + "          coalesce((select max(deal_year * 10000 + deal_month * 100 + deal_day)"
                        + "                      from estate.apt_rent  where sgg_cd = ? and apt_seq = ?), 0)),"
                        + "        seen_at = now()"
                        + "  where username = ? and apt_seq = ?";
        int changed = jdbc().update(sql, sggCd, aptSeq, sggCd, aptSeq, username, aptSeq);
        return Map.of("apt_seq", aptSeq, "updated", changed > 0);
    }

    private int countFavorites(String username) {
        Integer count = jdbc().queryForObject(
                "select count(*) from member.estate_favorite where username = ?",
                Integer.class, username);
        return count == null ? 0 : count;
    }

    // ── 목표가 알림 ──────────────────────────────────────────────────────────

    /**
     * 관심 단지에 <b>"얼마가 되면 알려 줘"</b> 를 적는다(2026-11-25).
     *
     * <p>금액을 비우면 <b>지운다</b> — 메모와 같은 규칙이다(사람들은 그만 보려고 값을 비우지,
     * "삭제" 를 따로 찾지 않는다).
     *
     * <p><b>정한 시점의 최근 계약일을 함께 적는다</b>({@code target_base_ymd}). 0 으로 두면
     * 과거에 그 값에 닿은 적이 있는 단지는 정하자마자 "도달" 로 떠서 알림이 뜻을 잃는다.
     * 담을 때 {@code seen_deal_ymd} 를 적는 것과 같은 이유다.
     *
     * <p>관심 단지가 아니면 거절한다 — 목표가는 관심 단지의 속성이라 담지 않은 단지에는
     * 붙일 곳이 없다(화면도 담은 뒤에만 단추를 보여 준다).
     *
     * @param amount 목표 금액(만원). null·0 이하면 지운다
     * @param kind   {@code trade}(매매가) | {@code jeonse}(전세 보증금). 비우면 매매
     * @param dir    {@code below}(이하로 내려오면) | {@code above}(이상이면). 비우면 below
     * @param pyeong 분양 평형. null 이면 단지의 모든 평형을 본다
     */
    public Map<String, Object> setTarget(
            String username, String aptSeq, Integer amount, String kind, String dir, Integer pyeong) {
        requireTarget();
        String sggCd = sggCdOf(aptSeq);

        if (amount == null || amount <= 0) {
            int cleared = jdbc().update(
                    "update member.estate_favorite"
                            + "    set target_amount = null, target_kind = null, target_dir = null,"
                            + "        target_pyeong = null, target_base_ymd = 0, target_at = null"
                            + "  where username = ? and apt_seq = ?",
                    username, aptSeq);
            requireFavoriteChanged(cleared);
            // 지운 뒤에는 **null** 로 답한다(빈 문자열로 주면 화면이 "0원" 으로 읽는다).
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("apt_seq", aptSeq);
            empty.put("target_amount", null);
            empty.put("cleared", true);
            return empty;
        }

        if (amount > TARGET_MAX_AMOUNT) {
            throw new EstateController.BadRequestException(
                    "TARGET_TOO_LARGE",
                    "목표 금액이 너무 큽니다(만원 단위로 적어 주세요).");
        }
        String kindValue = "jeonse".equals(kind) ? "jeonse" : "trade";
        String dirValue = "above".equals(dir) ? "above" : "below";
        if (pyeong != null && pyeong <= 0) {
            throw new EstateController.BadRequestException("INVALID_PYEONG", "평형이 올바르지 않습니다.");
        }

        /*
          기준점은 **매매·전월세 중 더 최근인 계약일**이다. 전세 목표라고 전월세만 보면,
          그 단지에 전세 거래가 뜸한 동안 올라온 과거 기록이 "방금 도달" 로 뜰 수 있다.
          담을 때 쓰는 식(addFavorite)과 같은 것을 쓴다.
        */
        int changed = jdbc().update(
                "update member.estate_favorite"
                        + "    set target_amount = ?, target_kind = ?, target_dir = ?, target_pyeong = ?,"
                        + "        target_at = now(),"
                        + "        target_base_ymd = greatest("
                        + "          coalesce((select max(deal_year * 10000 + deal_month * 100 + deal_day)"
                        + "                      from estate.apt_trade where sgg_cd = ? and apt_seq = ?), 0),"
                        + "          coalesce((select max(deal_year * 10000 + deal_month * 100 + deal_day)"
                        + "                      from estate.apt_rent  where sgg_cd = ? and apt_seq = ?), 0))"
                        + "  where username = ? and apt_seq = ?",
                amount, kindValue, dirValue, pyeong, sggCd, aptSeq, sggCd, aptSeq, username, aptSeq);
        requireFavoriteChanged(changed);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apt_seq", aptSeq);
        result.put("target_amount", amount);
        result.put("target_kind", kindValue);
        result.put("target_dir", dirValue);
        result.put("target_pyeong", pyeong);
        return result;
    }

    /**
     * <b>아직 알리지 않은 목표가 도달</b>을 모아 온다 — 카카오톡 발송 감시가 쓴다.
     *
     * <p>세 가지로 좁혀 두어 평소에는 거의 공짜다. ① 카카오를 <b>연결한 사람</b>만
     * ② 목표가를 <b>정해 둔 단지</b>만 ③ 마지막으로 알린 거래보다 <b>뒤의 도달</b>만.
     * 아무도 연결하지 않았으면 첫 조인에서 행이 0개라 무거운 집계가 돌지 않는다.
     *
     * <p>화면 배지와 달리 <b>카카오톡은 한 번 가면 되돌릴 수 없다</b> — 그래서 "알린 기록"
     * (`target_notified_ymd`)을 조건에 넣는다. 이것이 없으면 감시가 돌 때마다 같은 알림이
     * 또 간다.
     */
    public List<Map<String, Object>> listPendingTargetAlerts(int limit) {
        if (!targetColumnsReady() || !noticeColumnReady()) {
            return List.of();
        }
        String sql =
                "select * from ("
                        + "select f.username, f.apt_seq, f.apt_nm, f.umd_nm,"
                        + "       f.target_amount, f.target_kind, f.target_dir, f.target_pyeong,"
                        + "       coalesce(f.target_notified_ymd, 0) as notified_ymd,"
                        + "       case when f.target_kind = 'jeonse' then coalesce(r.hit_count, 0)"
                        + "            else coalesce(t.hit_count, 0) end as hit_count,"
                        + "       case when f.target_kind = 'jeonse' then r.hit_ymd"
                        + "            else t.hit_ymd end as hit_ymd,"
                        + "       case when f.target_kind = 'jeonse' then r.hit_amount"
                        + "            else t.hit_amount end as hit_amount"
                        + "  from member.estate_favorite f"
                        + "  join member.estate_kakao k"
                        + "    on k.username = f.username and k.enabled"
                        + "  left join lateral ("
                        + "       select 1 as one"
                        + targetAggregate("x.deal_amount", "coalesce(f.target_kind, 'trade') = 'trade'", "")
                        + "         from estate.apt_trade x"
                        + "        where x.sgg_cd = coalesce(f.sgg_cd, left(f.apt_seq, 5))"
                        + "          and x.apt_seq = f.apt_seq) t on true"
                        + "  left join lateral ("
                        + "       select 1 as one"
                        + targetAggregate("x.deposit", "f.target_kind = 'jeonse'", " and x.monthly_rent = 0")
                        + "         from estate.apt_rent x"
                        + "        where x.sgg_cd = coalesce(f.sgg_cd, left(f.apt_seq, 5))"
                        + "          and x.apt_seq = f.apt_seq) r on true"
                        + " where f.target_amount is not null) q"
                        + " where q.hit_count > 0 and q.hit_ymd > q.notified_ymd"
                        + " limit " + Math.max(1, limit);
        return jdbc().queryForList(sql);
    }

    /**
     * "이 도달은 내가 알린다" 고 <b>먼저 적어 두고</b> 보낸다.
     *
     * <p>적히지 않으면(이미 다른 쪽이 가져갔으면) 0 을 돌려주고 보내지 않는다. 서버가 여러 대로
     * 늘어나도 <b>같은 알림이 두 번 가지 않게</b> 하는 장치다 — 보낸 뒤에 적으면 그사이에 다른
     * 서버가 같은 건을 집어 간다.
     *
     * <p>보내기가 실패하면 {@link #releaseTargetNotice} 로 되돌린다.
     */
    public boolean claimTargetNotice(String username, String aptSeq, long hitYmd) {
        int changed = jdbc().update(
                "update member.estate_favorite"
                        + "    set target_notified_ymd = ?"
                        + "  where username = ? and apt_seq = ?"
                        + "    and coalesce(target_notified_ymd, 0) < ?",
                hitYmd, username, aptSeq, hitYmd);
        return changed > 0;
    }

    /** 보내기가 실패했을 때 표식을 되돌린다 — 다음 감시 때 다시 시도한다. */
    public void releaseTargetNotice(String username, String aptSeq, long previousYmd) {
        jdbc().update(
                "update member.estate_favorite set target_notified_ymd = ?"
                        + " where username = ? and apt_seq = ?",
                previousYmd, username, aptSeq);
    }

    /** `target_notified_ymd` 컬럼이 있는지(카카오 스크립트를 실행했는지). */
    public boolean noticeColumnReady() {
        if (noticeReady) {
            return true;
        }
        if (!tablesReady()) {
            return false;
        }
        if (System.currentTimeMillis() - noticeProbedAt < PROBE_TTL_MS) {
            return false;
        }
        noticeProbedAt = System.currentTimeMillis();
        JdbcTemplate jdbcTemplate = jdbcProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return false;
        }
        try {
            Integer found = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.columns"
                            + " where table_schema = 'member' and table_name = 'estate_favorite'"
                            + "   and column_name = 'target_notified_ymd'",
                    Integer.class);
            noticeReady = found != null && found == 1;
        } catch (RuntimeException e) {
            log.info("알림 기록 컬럼 확인 실패: {}", e.getClass().getSimpleName());
            noticeReady = false;
        }
        return noticeReady;
    }

    private void requireFavoriteChanged(int changed) {
        if (changed == 0) {
            throw new EstateController.BadRequestException(
                    "NOT_FAVORITE", "먼저 관심 단지로 담은 뒤에 목표가를 정할 수 있습니다.");
        }
    }

    // ── 최근 본 단지 ─────────────────────────────────────────────────────────

    /**
     * 최근 본 단지로 올린다. 같은 단지를 다시 보면 <b>행을 늘리지 않고 시각만 갱신</b>한다.
     *
     * <p>오래된 것은 그때그때 정리한다 — 목록 질의에 {@code limit} 만 걸면 표가 끝없이 자란다.
     */
    public void touchRecent(String username, String propertyType, String aptSeq, String umdNm, String aptNm) {
        jdbc().update(
                "insert into member.estate_recent"
                        + " (username, apt_seq, property_type, sgg_cd, umd_nm, apt_nm, viewed_at)"
                        + " values (?, ?, ?, ?, ?, ?, now())"
                        + " on conflict (username, apt_seq)"
                        + " do update set viewed_at = now(),"
                        + "               umd_nm = excluded.umd_nm,"
                        + "               apt_nm = excluded.apt_nm",
                username, aptSeq, propertyType, sggCdOf(aptSeq), umdNm, aptNm);

        jdbc().update(
                "delete from member.estate_recent"
                        + " where username = ?"
                        + "   and apt_seq not in ("
                        + "       select apt_seq from member.estate_recent"
                        + "        where username = ? order by viewed_at desc limit " + RECENT_KEEP + ")",
                username, username);
    }

    public List<Map<String, Object>> listRecent(String username) {
        return jdbc().queryForList(
                // 좌표는 "지도에서 보기" 용이다(2026-11-25) — listFavorites 의 설명 참고
                "select v.apt_seq, v.property_type, v.sgg_cd,"
                        // 이름이 비어 있으면 단지 표로 채운다 — listFavorites 와 같은 이유
                        + "       coalesce(v.umd_nm, c.umd_nm) as umd_nm,"
                        + "       coalesce(v.apt_nm, c.apt_nm) as apt_nm,"
                        + "       c.lon, c.lat,"
                        + "       to_char(v.viewed_at, 'YYYY-MM-DD HH24:MI:SS') as viewed_at"
                        + "  from member.estate_recent v"
                        + "  left join estate.apt_complex c on c.apt_seq = v.apt_seq"
                        + " where v.username = ?"
                        + " order by v.viewed_at desc"
                        + " limit " + RECENT_KEEP,
                username);
    }

    public Map<String, Object> clearRecent(String username) {
        int removed = jdbc().update("delete from member.estate_recent where username = ?", username);
        return Map.of("removed", removed);
    }

    // ── 메모 ─────────────────────────────────────────────────────────────────

    /**
     * 메모를 쓰거나 고친다. <b>빈 내용은 저장하지 않고 지운다</b> — 사람들은 지우려고 내용을
     * 비우고 저장하지, "삭제" 를 따로 찾지 않는다.
     */
    public Map<String, Object> saveNote(String username, String propertyType, String aptSeq, String note) {
        String trimmed = note == null ? "" : note.trim();
        if (!StringUtils.hasText(trimmed)) {
            jdbc().update("delete from member.estate_note where username = ? and apt_seq = ?",
                    username, aptSeq);
            return Map.of("apt_seq", aptSeq, "note", "");
        }
        if (trimmed.length() > NOTE_MAX_LENGTH) {
            throw new EstateController.BadRequestException(
                    "NOTE_TOO_LONG", "메모는 " + NOTE_MAX_LENGTH + "자까지 쓸 수 있습니다.");
        }
        jdbc().update(
                "insert into member.estate_note (username, apt_seq, property_type, note)"
                        + " values (?, ?, ?, ?)"
                        + " on conflict (username, apt_seq)"
                        + " do update set note = excluded.note, update_at = now()",
                username, aptSeq, propertyType, trimmed);
        return Map.of("apt_seq", aptSeq, "note", trimmed);
    }

    public Map<String, Object> getNote(String username, String aptSeq) {
        List<Map<String, Object>> rows = jdbc().queryForList(
                "select apt_seq, note, to_char(update_at, 'YYYY-MM-DD HH24:MI:SS') as update_at"
                        + "  from member.estate_note where username = ? and apt_seq = ?",
                username, aptSeq);
        return rows.isEmpty() ? Map.of("apt_seq", aptSeq, "note", "") : rows.get(0);
    }

    public List<Map<String, Object>> listNotes(String username) {
        return jdbc().queryForList(
                // 단지 이름은 관심·최근에서 빌려 온다(메모 표에는 이름을 두지 않는다).
                // 그 둘에도 없으면 단지 이름 대신 `apt_complex` 의 이름을 쓴다 — 담아 둔 적
                // 없이 메모만 남긴 단지도 목록에서 "11680-218" 이 아니라 이름으로 보여야 한다.
                // 좌표도 같은 조인에서 가져온다(2026-11-25, "지도에서 보기").
                "select n.apt_seq, n.property_type, n.note,"
                        + "       to_char(n.update_at, 'YYYY-MM-DD HH24:MI:SS') as update_at,"
                        + "       coalesce(f.apt_nm, r.apt_nm, c.apt_nm) as apt_nm,"
                        + "       coalesce(f.umd_nm, r.umd_nm, c.umd_nm) as umd_nm,"
                        + "       coalesce(f.sgg_cd, r.sgg_cd, c.sgg_cd) as sgg_cd,"
                        + "       (f.apt_seq is not null) as favorite,"
                        + "       c.lon, c.lat"
                        + "  from member.estate_note n"
                        + "  left join member.estate_favorite f"
                        + "    on f.username = n.username and f.apt_seq = n.apt_seq"
                        + "  left join member.estate_recent r"
                        + "    on r.username = n.username and r.apt_seq = n.apt_seq"
                        + "  left join estate.apt_complex c on c.apt_seq = n.apt_seq"
                        + " where n.username = ?"
                        + " order by n.update_at desc"
                        + " limit 200",
                username);
    }

    // ── 내 정보 화면 ─────────────────────────────────────────────────────────

    /** 즐겨찾기·메모·최근 본 단지 개수와 "새 거래가 있는 단지" 수를 한 번에 센다. */
    public Map<String, Object> summary(String username) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", username);
        result.put("favorite_count", countFavorites(username));
        Integer notes = jdbc().queryForObject(
                "select count(*) from member.estate_note where username = ?", Integer.class, username);
        Integer recent = jdbc().queryForObject(
                "select count(*) from member.estate_recent where username = ?", Integer.class, username);
        result.put("note_count", notes == null ? 0 : notes);
        result.put("recent_count", recent == null ? 0 : recent);

        int alerts = 0;
        int targets = 0;
        int targetHits = 0;
        for (Map<String, Object> row : listFavorites(username)) {
            long fresh = asLong(row.get("trade_new_count")) + asLong(row.get("rent_new_count"));
            if (fresh > 0) {
                alerts++;
            }
            if (row.get("target_amount") != null) {
                targets++;
                if (asLong(row.get("target_hit_count")) > 0) {
                    targetHits++;
                }
            }
        }
        result.put("alert_count", alerts);
        // 목표가를 정한 단지 수와 그중 닿은 단지 수(컬럼이 없으면 둘 다 0 이다)
        result.put("target_count", targets);
        result.put("target_hit_count", targetHits);
        return result;
    }

    private long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /**
     * 단지 키 앞 5자리가 시군구 코드다 — 실거래 표에서 단지를 찾을 때 <b>반드시</b> 함께 건다
     * ({@code apt_seq} 단독 조회는 990만 행을 훑는다). {@code EstateService.sggCdOf} 와 같은 규칙이다.
     */
    private String sggCdOf(String aptSeq) {
        if (aptSeq == null || aptSeq.length() < 5) {
            throw new EstateController.BadRequestException("INVALID_APT_SEQ", "단지 키가 올바르지 않습니다.");
        }
        return aptSeq.substring(0, 5);
    }

    /** 즐겨찾기 여부만 빠르게 묻는다(단지 상세가 별 모양을 정할 때). */
    public List<String> favoriteKeys(String username) {
        List<Map<String, Object>> rows = jdbc().queryForList(
                "select apt_seq from member.estate_favorite where username = ?", username);
        List<String> keys = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            keys.add(String.valueOf(row.get("apt_seq")));
        }
        return keys;
    }
}
