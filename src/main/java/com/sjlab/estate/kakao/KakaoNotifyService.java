package com.sjlab.estate.kakao;

import com.sjlab.estate.controller.EstateController;
import com.sjlab.estate.service.MemberService;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 목표가에 닿으면 <b>카카오톡 "나와의 채팅" 으로</b> 알린다(2026-11-26).
 *
 * <p>하는 일은 둘이다. ① 사용자의 카카오 연결을 맡아 두고(토큰 저장·갱신·해제)
 * ② 일정 간격으로 <b>아직 알리지 않은 도달</b>을 찾아 보낸다.
 *
 * <p><b>화면 배지를 대신하는 것이 아니라 더하는 것이다.</b> 배지는 들어와야 보이고, 이것은
 * 들어오지 않아도 알려 준다. 연결하지 않은 사람에게는 종전과 똑같이 배지만 보인다.
 *
 * <p><b>세 겹으로 꺼진다.</b> ① 카카오 키가 없으면(환경변수 미설정) ② 표가 없으면
 * (스크립트 미실행) ③ 사용자가 연결하지 않았으면 — 어느 경우에도 <b>오류가 아니라 조용히
 * 꺼진 상태</b>이고 목표가 배지·관심 담기는 그대로 돈다.
 *
 * <p><b>같은 알림을 두 번 보내지 않는다.</b> 보내기 전에 "이건 내가 맡는다" 를 DB 에 먼저 적고
 * (`target_notified_ymd`), 실패하면 되돌린다. 카카오톡은 한 번 가면 취소할 수 없으므로
 * 순서를 바꾸지 말 것.
 */
@Service
public class KakaoNotifyService {

    private static final Logger log = LoggerFactory.getLogger(KakaoNotifyService.class);

    /** 한 번 돌 때 보낼 상한. 밀려 있어도 한꺼번에 쏟아붓지 않는다. */
    private static final int BATCH_MAX = 50;
    /** 연속 실패가 이만큼 쌓이면 스스로 멈춘다(사용자가 카카오 쪽에서 끊은 경우). */
    private static final int FAIL_LIMIT = 5;
    private static final long PROBE_TTL_MS = 60_000L;

    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final KakaoClient kakao;
    private final MemberService memberService;
    private final String siteUrl;

    private volatile boolean tableReady = false;
    private volatile long probedAt = 0L;

    public KakaoNotifyService(
            @Qualifier("memberJdbcTemplate") ObjectProvider<JdbcTemplate> jdbcProvider,
            KakaoClient kakao,
            MemberService memberService,
            @Value("${estate.kakao.site-url:https://sj-lab.co.kr/estate/}") String siteUrl) {
        this.jdbcProvider = jdbcProvider;
        this.kakao = kakao;
        this.memberService = memberService;
        this.siteUrl = siteUrl;
    }

    // ── 상태 ─────────────────────────────────────────────────────────────────

    /** 화면이 "카카오 연결 단추를 보여 줄지" 를 정하는 곳. */
    public Map<String, Object> status(String username) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean configured = kakao.isConfigured();
        boolean ready = configured && tableReady() && memberService.noticeColumnReady();
        result.put("ready", ready);
        result.put("configured", configured);
        if (!configured) {
            result.put("reason", "NOT_CONFIGURED");
            result.put("message", "카카오 앱 키가 설정되지 않아 카카오톡 알림을 쓸 수 없습니다.");
        } else if (!ready) {
            result.put("reason", "NO_TABLE");
            result.put("message", "카카오 알림 표가 아직 만들어지지 않았습니다.");
        } else {
            result.put("reason", "READY");
            result.put("message", "카카오톡으로 알림을 받을 수 있습니다.");
        }

        // 로그인한 사람이면 "연결했는지" 까지 함께 알려 준다. **토큰은 내보내지 않는다.**
        if (ready && StringUtils.hasText(username)) {
            List<Map<String, Object>> rows = jdbc().queryForList(
                    "select enabled, kakao_user_id, fail_count, last_error,"
                            + "       to_char(linked_at, 'YYYY-MM-DD HH24:MI:SS') as linked_at,"
                            + "       to_char(last_sent_at, 'YYYY-MM-DD HH24:MI:SS') as last_sent_at"
                            + "  from member.estate_kakao where username = ?",
                    username);
            if (rows.isEmpty()) {
                result.put("linked", false);
            } else {
                Map<String, Object> row = rows.get(0);
                result.put("linked", true);
                result.put("enabled", row.get("enabled"));
                result.put("linked_at", row.get("linked_at"));
                result.put("last_sent_at", row.get("last_sent_at"));
                result.put("fail_count", row.get("fail_count"));
                result.put("last_error", row.get("last_error"));
            }
        } else {
            result.put("linked", false);
        }
        return result;
    }

    /** 동의 화면 주소. 화면은 이 주소로 사용자를 보내기만 한다. */
    public Map<String, Object> authorizeUrl(String redirectUri) {
        requireReady();
        if (!StringUtils.hasText(redirectUri)) {
            throw new EstateController.BadRequestException(
                    "MISSING_REDIRECT_URI", "돌아올 주소가 없습니다.");
        }
        return Map.of("authorize_url", kakao.authorizeUrl(redirectUri, "estate"), "scope", KakaoClient.SCOPE);
    }

    // ── 연결 ─────────────────────────────────────────────────────────────────

    /**
     * 동의 화면이 돌려준 {@code code} 를 토큰으로 바꿔 저장한다.
     *
     * <p>{@code redirect_uri} 는 동의 화면에 보냈던 것과 <b>똑같아야</b> 한다 — 카카오가 그
     * 값으로 대조한다. 그래서 화면이 자기가 쓴 주소를 그대로 함께 보낸다.
     */
    public Map<String, Object> link(String username, String code, String redirectUri) {
        requireReady();
        if (!StringUtils.hasText(code)) {
            throw new EstateController.BadRequestException("MISSING_CODE", "카카오 인증 값이 없습니다.");
        }
        Map<String, Object> token;
        try {
            token = kakao.exchangeCode(code, redirectUri);
        } catch (KakaoClient.KakaoException e) {
            log.info("카카오 연결 실패: {}", e.summary());
            if (e.isClientConfigError()) {
                /*
                  **다시 시도해도 똑같이 실패한다.** 앱 키나 Client Secret 설정 문제이므로
                  "다시 눌러 보세요" 라고 안내하면 사용자가 같은 자리를 계속 맴돈다
                  (2026-11-26 실제로 그랬다 — 동의 화면은 통과하고 토큰에서만 막혔다).
                */
                throw new EstateController.NotReadyException(
                        "KAKAO_CLIENT_CONFIG",
                        "카카오 앱 설정이 맞지 않습니다(KOE010). 관리자에게 문의해 주세요.");
            }
            throw new EstateController.BadRequestException(
                    "KAKAO_LINK_FAILED",
                    "카카오 연결에 실패했습니다. 동의 화면을 다시 열어 주세요.");
        }
        String refresh = text(token.get("refresh_token"));
        String access = text(token.get("access_token"));
        if (!StringUtils.hasText(refresh)) {
            // 갱신 토큰이 없으면 사용자가 없을 때 보낼 수 없다 — 연결로 치지 않는다.
            throw new EstateController.BadRequestException(
                    "KAKAO_NO_REFRESH_TOKEN",
                    "카카오가 갱신 토큰을 주지 않았습니다. 동의 항목을 확인해 주세요.");
        }
        long expiresIn = asLong(token.get("expires_in"));

        jdbc().update(
                "insert into member.estate_kakao"
                        + " (username, refresh_token, access_token, access_expires_at, enabled,"
                        + "  linked_at, fail_count, last_error)"
                        + " values (?, ?, ?, ?, true, now(), 0, null)"
                        + " on conflict (username) do update set"
                        + "   refresh_token = excluded.refresh_token,"
                        + "   access_token = excluded.access_token,"
                        + "   access_expires_at = excluded.access_expires_at,"
                        + "   enabled = true, linked_at = now(), fail_count = 0, last_error = null",
                username, refresh, access, expiresAt(expiresIn));

        log.info("카카오 연결: {}", username);
        return Map.of("linked", true, "enabled", true);
    }

    /** 연결을 끊는다 — <b>행을 지운다.</b> 토큰을 남겨 두면 끊었는데도 보낼 수 있다. */
    public Map<String, Object> unlink(String username) {
        requireReady();
        int removed = jdbc().update("delete from member.estate_kakao where username = ?", username);
        return Map.of("linked", false, "removed", removed > 0);
    }

    /** 연결은 둔 채 보내기만 켜고 끈다. */
    public Map<String, Object> setEnabled(String username, boolean enabled) {
        requireReady();
        int changed = jdbc().update(
                "update member.estate_kakao set enabled = ?, fail_count = 0, last_error = null"
                        + " where username = ?",
                enabled, username);
        if (changed == 0) {
            throw new EstateController.BadRequestException(
                    "NOT_LINKED", "카카오톡을 먼저 연결해 주세요.");
        }
        return Map.of("linked", true, "enabled", enabled);
    }

    /** 잘 연결됐는지 한 통 보내 본다. 연결 직후 사용자가 스스로 확인하는 길이다. */
    public Map<String, Object> sendTest(String username) {
        requireReady();
        String accessToken = accessTokenFor(username);
        try {
            kakao.sendMemo(
                    accessToken,
                    "[SJ 부동산] 알림 연결이 끝났습니다.\n"
                            + "담아 둔 단지가 목표가에 닿으면 여기로 알려 드립니다.",
                    siteUrl,
                    "내 단지 보기");
            markSent(username);
            return Map.of("sent", true);
        } catch (KakaoClient.KakaoException e) {
            markFailed(username, e);
            throw new EstateController.BadRequestException(
                    "KAKAO_SEND_FAILED",
                    e.needsRelink()
                            ? "카카오 연결이 풀렸습니다. 다시 연결해 주세요."
                            : "카카오톡을 보내지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
        }
    }

    // ── 감시 ─────────────────────────────────────────────────────────────────

    /**
     * 아직 알리지 않은 목표가 도달을 찾아 보낸다.
     *
     * <p><b>수집 직후에 맞춰 돌리지 않는다</b> — 수집은 다른 서비스(`sj-lab-scheduler`)가 하고
     * 끝나는 시각이 일정하지 않다. 대신 일정 간격으로 훑는데, 조건이 "연결한 사람 × 목표가를
     * 정한 단지 × 아직 안 알린 도달" 이라 평소에는 행이 0개여서 비용이 사실상 없다.
     *
     * <p>한 건이 실패해도 <b>나머지는 계속 보낸다</b> — 한 사람의 연결이 풀렸다고 다른 사람의
     * 알림까지 멈추면 안 된다.
     */
    @Scheduled(
            initialDelayString = "${estate.kakao.watch-initial-delay-ms:60000}",
            fixedDelayString = "${estate.kakao.watch-interval-ms:1800000}")
    public void watchTargets() {
        if (!kakao.isConfigured() || !tableReady()) {
            return;
        }
        List<Map<String, Object>> pending;
        try {
            pending = memberService.listPendingTargetAlerts(BATCH_MAX);
        } catch (RuntimeException e) {
            log.info("목표가 감시 조회 실패: {}", e.getClass().getSimpleName());
            return;
        }
        if (pending.isEmpty()) {
            return;
        }
        int sent = 0;
        for (Map<String, Object> row : pending) {
            if (sendAlert(row)) {
                sent++;
            }
        }
        log.info("목표가 카카오 알림: 대상 {}건 · 보냄 {}건", pending.size(), sent);
    }

    private boolean sendAlert(Map<String, Object> row) {
        String username = String.valueOf(row.get("username"));
        String aptSeq = String.valueOf(row.get("apt_seq"));
        long hitYmd = asLong(row.get("hit_ymd"));
        long previous = asLong(row.get("notified_ymd"));

        // **먼저 적고 보낸다** — 순서를 바꾸면 서버가 여러 대일 때 같은 알림이 두 번 간다.
        if (!memberService.claimTargetNotice(username, aptSeq, hitYmd)) {
            return false;
        }
        try {
            kakao.sendMemo(accessTokenFor(username), alertText(row), siteUrl, "단지 보기");
            markSent(username);
            return true;
        } catch (RuntimeException e) {
            // 실패하면 표식을 되돌려 다음 번에 다시 시도한다.
            memberService.releaseTargetNotice(username, aptSeq, previous);
            if (e instanceof KakaoClient.KakaoException kakaoError) {
                markFailed(username, kakaoError);
                log.info("목표가 알림 실패 {} / {}: {}", username, aptSeq, kakaoError.summary());
            } else {
                log.info("목표가 알림 실패 {} / {}: {}", username, aptSeq, e.getClass().getSimpleName());
            }
            return false;
        }
    }

    /**
     * 메시지 본문. <b>무엇이 · 얼마에 · 언제</b> 세 가지만 적는다 — 알림은 짧아야 읽힌다.
     * 자세한 것은 단추를 눌러 사이트에서 본다.
     */
    private String alertText(Map<String, Object> row) {
        String name = text(row.get("apt_nm"));
        if (!StringUtils.hasText(name)) {
            name = String.valueOf(row.get("apt_seq"));
        }
        boolean jeonse = "jeonse".equals(text(row.get("target_kind")));
        boolean above = "above".equals(text(row.get("target_dir")));
        long pyeong = asLong(row.get("target_pyeong"));

        StringBuilder sb = new StringBuilder();
        sb.append("🔔 목표가 도달\n");
        sb.append(name);
        if (pyeong > 0) {
            sb.append(' ').append(pyeong).append("평");
        }
        String region = text(row.get("umd_nm"));
        if (StringUtils.hasText(region)) {
            sb.append(" (").append(region).append(')');
        }
        sb.append('\n');
        sb.append(jeonse ? "전세 " : "매매 ");
        sb.append(money(asLong(row.get("hit_amount"))));
        sb.append(" 거래 · ").append(dateText(asLong(row.get("hit_ymd")))).append('\n');
        sb.append("내 목표 ").append(money(asLong(row.get("target_amount"))));
        sb.append(above ? " 이상" : " 이하");
        return sb.toString();
    }

    /** 만원 단위를 "8억 5,000만" 으로. 화면의 표기와 같은 규칙이다. */
    static String money(long manwon) {
        if (manwon <= 0) {
            return "-";
        }
        long eok = manwon / 10000;
        long rest = manwon % 10000;
        if (eok > 0) {
            return rest > 0 ? eok + "억 " + String.format("%,d", rest) + "만" : eok + "억";
        }
        return String.format("%,d", rest) + "만";
    }

    /** 20261120 → "2026-11-20". */
    static String dateText(long ymd) {
        String text = String.valueOf(ymd);
        if (text.length() != 8) {
            return "";
        }
        return text.substring(0, 4) + "-" + text.substring(4, 6) + "-" + text.substring(6, 8);
    }

    // ── 토큰 ─────────────────────────────────────────────────────────────────

    /**
     * 보낼 때 쓸 접근 토큰. <b>남은 시간이 넉넉하면 그대로 쓰고</b>, 아니면 갱신 토큰으로 새로 받는다.
     *
     * <p>카카오는 갱신할 때 {@code refresh_token} 을 <b>함께 줄 때만</b> 갱신 토큰이 바뀐 것이다.
     * 안 왔다고 지우면 연결이 끊긴다 — 왔을 때만 덮어쓴다.
     */
    private String accessTokenFor(String username) {
        List<Map<String, Object>> rows = jdbc().queryForList(
                "select refresh_token, access_token, access_expires_at, enabled"
                        + "  from member.estate_kakao where username = ?",
                username);
        if (rows.isEmpty()) {
            throw new EstateController.BadRequestException("NOT_LINKED", "카카오톡을 먼저 연결해 주세요.");
        }
        Map<String, Object> row = rows.get(0);
        Object expiresAt = row.get("access_expires_at");
        String access = text(row.get("access_token"));
        if (StringUtils.hasText(access) && expiresAt instanceof Timestamp stamp
                // 1분 남았으면 보내는 사이에 만료될 수 있다 — 미리 갱신한다.
                && stamp.getTime() - System.currentTimeMillis() > 60_000L) {
            return access;
        }

        Map<String, Object> token = kakao.refresh(text(row.get("refresh_token")));
        String newAccess = text(token.get("access_token"));
        String newRefresh = text(token.get("refresh_token"));
        long expiresIn = asLong(token.get("expires_in"));
        if (StringUtils.hasText(newRefresh)) {
            jdbc().update(
                    "update member.estate_kakao"
                            + "    set access_token = ?, access_expires_at = ?, refresh_token = ?"
                            + "  where username = ?",
                    newAccess, expiresAt(expiresIn), newRefresh, username);
        } else {
            jdbc().update(
                    "update member.estate_kakao set access_token = ?, access_expires_at = ?"
                            + " where username = ?",
                    newAccess, expiresAt(expiresIn), username);
        }
        return newAccess;
    }

    private void markSent(String username) {
        jdbc().update(
                "update member.estate_kakao"
                        + "    set last_sent_at = now(), fail_count = 0, last_error = null"
                        + "  where username = ?",
                username);
    }

    /**
     * 실패를 적는다. <b>연결이 풀린 종류의 실패가 쌓이면 스스로 멈춘다</b> — 사용자가 카카오
     * 쪽에서 연결을 해제했는데 서버가 계속 두드리면 로그만 쌓이고 아무도 보지 않는다.
     */
    private void markFailed(String username, KakaoClient.KakaoException e) {
        String reason = e.summary();
        if (reason.length() > 290) {
            reason = reason.substring(0, 290);
        }
        jdbc().update(
                "update member.estate_kakao"
                        + "    set fail_count = fail_count + 1, last_error = ?,"
                        + "        enabled = case when fail_count + 1 >= ? then false else enabled end"
                        + "  where username = ?",
                reason, FAIL_LIMIT, username);
        if (e.needsRelink()) {
            jdbc().update(
                    "update member.estate_kakao set enabled = false where username = ?", username);
            log.info("카카오 연결이 풀려 알림을 멈춥니다: {}", username);
        }
    }

    // ── 공통 ─────────────────────────────────────────────────────────────────

    private JdbcTemplate jdbc() {
        JdbcTemplate jdbcTemplate = jdbcProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new EstateController.NotReadyException(
                    "NOT_CONFIGURED", "DB 접속 정보가 없어 카카오 알림을 쓸 수 없습니다.");
        }
        return jdbcTemplate;
    }

    private void requireReady() {
        if (!kakao.isConfigured()) {
            throw new EstateController.NotReadyException(
                    "NOT_CONFIGURED", "카카오 앱 키가 설정되지 않았습니다.");
        }
        if (!tableReady() || !memberService.noticeColumnReady()) {
            throw new EstateController.NotReadyException(
                    "NO_TABLE", "카카오 알림 표가 아직 만들어지지 않았습니다. 관리자에게 문의해 주세요.");
        }
    }

    /** 표가 있는지. 회원 표와 같은 규칙 — 한 번 확인하면 뒤집지 않고, 없으면 60초마다 다시 묻는다. */
    private boolean tableReady() {
        if (tableReady) {
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
                            + " where table_schema = 'member' and table_name = 'estate_kakao'",
                    Integer.class);
            tableReady = found != null && found == 1;
        } catch (RuntimeException e) {
            log.info("카카오 표 확인 실패: {}", e.getClass().getSimpleName());
            tableReady = false;
        }
        return tableReady;
    }

    private Timestamp expiresAt(long expiresInSeconds) {
        long seconds = expiresInSeconds > 0 ? expiresInSeconds : 3600L;
        return new Timestamp(System.currentTimeMillis() + seconds * 1000L);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
