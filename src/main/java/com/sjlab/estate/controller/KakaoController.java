package com.sjlab.estate.controller;

import com.sjlab.estate.auth.AuthClient;
import com.sjlab.estate.kakao.KakaoNotifyService;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 카카오톡 알림 연결 — <b>목표가에 닿으면 "나와의 채팅" 으로 알려 주기</b>(2026-11-26).
 *
 * <p>외부 경로는 {@code /estate/member/kakao/...} 다.
 *
 * <p><b>{@code /status} 만 로그인 없이 부를 수 있다</b> — 화면이 "이 기능을 보여 줄지" 를 먼저
 * 판단해야 하기 때문이다(회원 기능과 같은 규칙). 그때는 "연결함/안 함" 은 알려 주지 않는다.
 *
 * <p><b>토큰은 어떤 응답에도 담기지 않는다.</b> 화면은 "연결됨 / 안 됨" 과 마지막 발송 시각만
 * 본다 — 토큰이 브라우저로 한 번이라도 내려가면 그 순간부터 비밀이 아니다.
 */
@RestController
@RequestMapping("/member/kakao")
public class KakaoController {

    private final KakaoNotifyService kakaoService;
    private final AuthClient authClient;

    public KakaoController(KakaoNotifyService kakaoService, AuthClient authClient) {
        this.kakaoService = kakaoService;
        this.authClient = authClient;
    }

    /** 쓸 수 있는 상태인지 + (로그인했으면) 연결했는지. */
    @GetMapping("/status")
    public Map<String, Object> status(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        String username = null;
        if (auth != null && !auth.isBlank()) {
            username = authClient.requireUsername(auth);
        }
        return kakaoService.status(username);
    }

    /**
     * 카카오 동의 화면 주소를 받아 간다.
     *
     * <p>{@code redirectUri} 는 <b>화면이 돌아올 주소</b>이고, 카카오 개발자 화면에 똑같이
     * 등록돼 있어야 한다. 토큰을 받을 때 같은 값을 다시 보내므로 화면이 기억했다가 넘긴다.
     */
    @GetMapping("/authorize-url")
    public Map<String, Object> authorizeUrl(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestParam("redirectUri") String redirectUri) {
        authClient.requireUsername(auth);
        return kakaoService.authorizeUrl(redirectUri);
    }

    /** 동의 화면이 돌려준 {@code code} 로 연결을 마친다. */
    @PutMapping("/link")
    public Map<String, Object> link(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {
        return kakaoService.link(
                authClient.requireUsername(auth),
                text(body, "code"),
                text(body, "redirect_uri"));
    }

    /** 연결 끊기 — 저장해 둔 토큰을 지운다. */
    @DeleteMapping("/link")
    public Map<String, Object> unlink(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return kakaoService.unlink(authClient.requireUsername(auth));
    }

    /** 연결은 둔 채 보내기만 켜고 끈다. */
    @PutMapping("/enabled")
    public Map<String, Object> setEnabled(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {
        Object value = body == null ? null : body.get("enabled");
        boolean enabled = value == null || Boolean.parseBoolean(String.valueOf(value));
        return kakaoService.setEnabled(authClient.requireUsername(auth), enabled);
    }

    /** 테스트 메시지 한 통 — 연결 직후 사용자가 스스로 확인하는 길이다. */
    @PostMapping("/test")
    public Map<String, Object> test(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return kakaoService.sendTest(authClient.requireUsername(auth));
    }

    private String text(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
