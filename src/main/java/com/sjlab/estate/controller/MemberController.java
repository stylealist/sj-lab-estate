package com.sjlab.estate.controller;

import com.sjlab.estate.auth.AuthClient;
import com.sjlab.estate.service.MemberService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인한 사람의 것 — <b>즐겨찾기 · 최근 본 단지 · 메모 · 내 정보</b>.
 *
 * <p>외부 경로는 {@code /estate/member/...} 다(context-path 가 {@code /estate}).
 *
 * <p><b>이 컨트롤러만 쓰기를 한다.</b> 실거래 조회는 종전대로 읽기 전용이고, 여기는
 * {@code member} 스키마에만 쓰는 별도 커넥션을 쓴다.
 *
 * <p><b>누구인지는 토큰으로만 정한다</b> — 요청 본문이나 쿼리의 계정 이름을 믿지 않는다.
 * 그랬다면 남의 즐겨찾기를 아무나 읽고 고칠 수 있다. 확인은 로그인 서버에 위임한다.
 *
 * <p>{@code /status} 만 로그인 없이 부를 수 있다 — 화면이 "이 기능을 보여 줄지" 를 먼저
 * 판단해야 하기 때문이다(표가 아직 없으면 단추 자체를 숨긴다).
 */
@RestController
@RequestMapping("/member")
public class MemberController {

    private final MemberService memberService;
    private final AuthClient authClient;

    public MemberController(MemberService memberService, AuthClient authClient) {
        this.memberService = memberService;
        this.authClient = authClient;
    }

    /** 회원 기능을 쓸 수 있는 상태인지. 로그인 없이 부를 수 있다. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return memberService.status();
    }

    /** 내 정보 — 계정과 담아 둔 것들의 개수, 새 거래가 있는 단지 수. */
    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return memberService.summary(authClient.requireUsername(auth));
    }

    // ── 즐겨찾기 ─────────────────────────────────────────────────────────────

    @GetMapping("/favorites")
    public Map<String, Object> favorites(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        List<Map<String, Object>> items = memberService.listFavorites(authClient.requireUsername(auth));
        // 목록 응답은 이 서비스의 다른 곳과 같게 {"items": [...]} 다.
        return Map.of("items", items);
    }

    /** 즐겨찾기 키만 — 화면이 별 모양을 정할 때 쓴다(목록 전체를 받을 필요가 없다). */
    @GetMapping("/favorites/keys")
    public Map<String, Object> favoriteKeys(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return Map.of("items", memberService.favoriteKeys(authClient.requireUsername(auth)));
    }

    @PostMapping("/favorites/{aptSeq}")
    public Map<String, Object> addFavorite(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @PathVariable String aptSeq,
            @RequestBody(required = false) Map<String, Object> body) {
        return memberService.addFavorite(
                authClient.requireUsername(auth),
                text(body, "property_type", "apt"),
                aptSeq,
                text(body, "umd_nm", null),
                text(body, "apt_nm", null));
    }

    @DeleteMapping("/favorites/{aptSeq}")
    public Map<String, Object> removeFavorite(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @PathVariable String aptSeq) {
        return memberService.removeFavorite(authClient.requireUsername(auth), aptSeq);
    }

    /**
     * 목표가를 정해 둔 관심 단지만 — 지도·팝업의 🔔 표시용(2026-11-25).
     *
     * <p>목록 전체(`/favorites`)는 단지마다 거래를 세는 무거운 질의라 화면을 열 때마다 부를 수
     * 없다. 이쪽은 <b>목표가를 정한 단지만</b> 보므로, 하나도 안 정했으면 행이 0개라 그 집계가
     * 아예 돌지 않는다.
     */
    @GetMapping("/favorites/targets")
    public Map<String, Object> favoriteTargets(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return Map.of("items", memberService.listTargets(authClient.requireUsername(auth)));
    }

    /**
     * 목표가를 정하거나 고친다. <b>금액을 비우면 지운다</b>(메모와 같은 규칙).
     *
     * <p>본문: {@code {amount, kind, dir, pyeong}} — 금액은 <b>만원</b> 단위(이 서비스 공통),
     * {@code kind} 는 {@code trade}·{@code jeonse}, {@code dir} 는 {@code below}·{@code above},
     * {@code pyeong} 은 분양 평형(비우면 모든 평형).
     */
    @PutMapping("/favorites/{aptSeq}/target")
    public Map<String, Object> setTarget(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @PathVariable String aptSeq,
            @RequestBody(required = false) Map<String, Object> body) {
        return memberService.setTarget(
                authClient.requireUsername(auth),
                aptSeq,
                number(body, "amount"),
                text(body, "kind", null),
                text(body, "dir", null),
                number(body, "pyeong"));
    }

    @DeleteMapping("/favorites/{aptSeq}/target")
    public Map<String, Object> clearTarget(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @PathVariable String aptSeq) {
        return memberService.setTarget(authClient.requireUsername(auth), aptSeq, null, null, null, null);
    }

    /** "새 거래" 표시를 지운다(그 단지를 열어 봤다는 뜻). */
    @PostMapping("/favorites/{aptSeq}/seen")
    public Map<String, Object> markSeen(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @PathVariable String aptSeq) {
        return memberService.markSeen(authClient.requireUsername(auth), aptSeq);
    }

    // ── 최근 본 단지 ─────────────────────────────────────────────────────────

    @GetMapping("/recent")
    public Map<String, Object> recent(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return Map.of("items", memberService.listRecent(authClient.requireUsername(auth)));
    }

    /**
     * 단지를 봤다고 알린다. <b>로그인하지 않았으면 조용히 넘어간다</b>(204) — 단지를 열 때마다
     * 부르는 것이라, 로그인 안 한 사람에게 401 을 던지면 화면에 오류가 깜빡인다.
     */
    @PostMapping("/recent/{aptSeq}")
    public Map<String, Object> touchRecent(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @PathVariable String aptSeq,
            @RequestBody(required = false) Map<String, Object> body) {
        if (auth == null || auth.isBlank()) {
            return Map.of("saved", false, "reason", "LOGIN_REQUIRED");
        }
        memberService.touchRecent(
                authClient.requireUsername(auth),
                text(body, "property_type", "apt"),
                aptSeq,
                text(body, "umd_nm", null),
                text(body, "apt_nm", null));
        return Map.of("saved", true);
    }

    @DeleteMapping("/recent")
    public Map<String, Object> clearRecent(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return memberService.clearRecent(authClient.requireUsername(auth));
    }

    // ── 메모 ─────────────────────────────────────────────────────────────────

    @GetMapping("/notes")
    public Map<String, Object> notes(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return Map.of("items", memberService.listNotes(authClient.requireUsername(auth)));
    }

    @GetMapping("/notes/{aptSeq}")
    public Map<String, Object> note(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @PathVariable String aptSeq) {
        return memberService.getNote(authClient.requireUsername(auth), aptSeq);
    }

    /** 빈 내용으로 저장하면 지운다 — 사람들은 "삭제" 를 따로 찾지 않는다. */
    @PutMapping("/notes/{aptSeq}")
    public Map<String, Object> saveNote(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @PathVariable String aptSeq,
            @RequestBody(required = false) Map<String, Object> body) {
        return memberService.saveNote(
                authClient.requireUsername(auth),
                text(body, "property_type", "apt"),
                aptSeq,
                text(body, "note", ""));
    }

    @DeleteMapping("/notes/{aptSeq}")
    public Map<String, Object> deleteNote(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @PathVariable String aptSeq) {
        return memberService.saveNote(authClient.requireUsername(auth), "apt", aptSeq, "");
    }

    /**
     * 본문에서 숫자를 꺼낸다. <b>화면이 문자열로 보내도 받는다</b> — 입력칸 값은 문자열이라
     * 숫자로 바꾸는 것을 깜빡하면 조용히 null 이 되어 "지우기" 로 동작한다(찾기 어려운 버그다).
     * 숫자로 읽을 수 없으면 400 으로 분명히 알린다.
     */
    private Integer number(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        String text = String.valueOf(value).trim().replace(",", "");
        if (text.isEmpty()) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(text));
        } catch (NumberFormatException e) {
            throw new EstateController.BadRequestException(
                    "INVALID_NUMBER", "숫자로 읽을 수 없는 값입니다: " + key);
        }
    }

    private String text(Map<String, Object> body, String key, String fallback) {
        if (body == null) {
            return fallback;
        }
        Object value = body.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
