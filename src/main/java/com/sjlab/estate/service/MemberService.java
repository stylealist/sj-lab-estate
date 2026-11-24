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

    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    private volatile boolean tablesReady = false;
    private volatile long probedAt = 0L;

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
     */
    public List<Map<String, Object>> listFavorites(String username) {
        String sql =
                "select f.apt_seq, f.property_type, f.sgg_cd, f.umd_nm, f.apt_nm, f.seen_deal_ymd,"
                        + "       to_char(f.reg_date, 'YYYY-MM-DD HH24:MI:SS') as reg_date,"
                        + "       n.note,"
                        + "       coalesce(t.last_ymd, 0)  as trade_last_ymd,"
                        + "       coalesce(t.new_count, 0) as trade_new_count,"
                        + "       coalesce(r.last_ymd, 0)  as rent_last_ymd,"
                        + "       coalesce(r.new_count, 0) as rent_new_count"
                        + "  from member.estate_favorite f"
                        + "  left join member.estate_note n"
                        + "    on n.username = f.username and n.apt_seq = f.apt_seq"
                        + "  left join lateral ("
                        + "       select max(x.deal_year * 10000 + x.deal_month * 100 + x.deal_day) as last_ymd,"
                        + "              count(*) filter ("
                        + "                 where x.deal_year * 10000 + x.deal_month * 100 + x.deal_day"
                        + "                       > f.seen_deal_ymd) as new_count"
                        + "         from estate.apt_trade x"
                        + "        where x.sgg_cd = coalesce(f.sgg_cd, left(f.apt_seq, 5))"
                        + "          and x.apt_seq = f.apt_seq) t on true"
                        + "  left join lateral ("
                        + "       select max(x.deal_year * 10000 + x.deal_month * 100 + x.deal_day) as last_ymd,"
                        + "              count(*) filter ("
                        + "                 where x.deal_year * 10000 + x.deal_month * 100 + x.deal_day"
                        + "                       > f.seen_deal_ymd) as new_count"
                        + "         from estate.apt_rent x"
                        + "        where x.sgg_cd = coalesce(f.sgg_cd, left(f.apt_seq, 5))"
                        + "          and x.apt_seq = f.apt_seq) r on true"
                        + " where f.username = ?"
                        + " order by f.reg_date desc"
                        + " limit " + FAVORITE_MAX;
        return jdbc().queryForList(sql, username);
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

    public Map<String, Object> removeFavorite(String username, String aptSeq) {
        jdbc().update(
                "delete from member.estate_favorite where username = ? and apt_seq = ?",
                username, aptSeq);
        return Map.of("apt_seq", aptSeq, "favorite", false);
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
                "select apt_seq, property_type, sgg_cd, umd_nm, apt_nm,"
                        + "       to_char(viewed_at, 'YYYY-MM-DD HH24:MI:SS') as viewed_at"
                        + "  from member.estate_recent"
                        + " where username = ?"
                        + " order by viewed_at desc"
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
                "select n.apt_seq, n.property_type, n.note,"
                        + "       to_char(n.update_at, 'YYYY-MM-DD HH24:MI:SS') as update_at,"
                        + "       coalesce(f.apt_nm, r.apt_nm) as apt_nm,"
                        + "       coalesce(f.umd_nm, r.umd_nm) as umd_nm"
                        + "  from member.estate_note n"
                        + "  left join member.estate_favorite f"
                        + "    on f.username = n.username and f.apt_seq = n.apt_seq"
                        + "  left join member.estate_recent r"
                        + "    on r.username = n.username and r.apt_seq = n.apt_seq"
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
        for (Map<String, Object> row : listFavorites(username)) {
            long fresh = asLong(row.get("trade_new_count")) + asLong(row.get("rent_new_count"));
            if (fresh > 0) {
                alerts++;
            }
        }
        result.put("alert_count", alerts);
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
