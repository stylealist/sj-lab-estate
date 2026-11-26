package com.sjlab.estate.kakao;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

/**
 * 카카오를 부르는 유일한 곳 — <b>토큰 받기 · 토큰 갱신 · "나에게 보내기"</b> 셋뿐이다.
 *
 * <p><b>왜 "나에게 보내기" 인가.</b> 알림톡(흔히 받는 그 메시지)은 사업자등록증 · 비즈니스 채널 ·
 * 공식 딜러사 계약 · 템플릿 사전 심사가 필요하고 건당 비용도 든다. 반면 이 방식은 사용자가
 * 자기 카카오로 한 번 동의하면 그 사람의 <b>"나와의 채팅"</b> 으로 보낼 수 있고,
 * <b>등록·심사·비용이 없다.</b> 대신 <b>본인에게만</b> 간다 — 남에게 보내려면 카카오의 별도
 * 심사가 필요하다. 이 서비스는 "내가 담아 둔 단지 알림" 이라 본인에게만 가면 충분하다.
 *
 * <p><b>키가 없으면 기능만 꺼진다.</b> 저장소가 public 이라 키는 환경변수로만 받는다
 * ({@code KAKAO_REST_API_KEY}). 없으면 {@link #isConfigured()} 가 false 이고 화면은 연결
 * 단추를 숨긴다 — 기동을 막지 않는다(DB 접속 정보와 같은 규칙).
 *
 * <p><b>토큰을 로그에 찍지 말 것.</b> 실패를 남길 때도 상태 코드와 카카오가 준 오류 코드까지만
 * 적는다. 이 저장소는 public 이고, 로그는 어디로든 흘러갈 수 있다.
 */
@Component
public class KakaoClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoClient.class);

    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String SEND_URL = "https://kapi.kakao.com/v2/api/talk/memo/default/send";
    private static final String AUTHORIZE_URL = "https://kauth.kakao.com/oauth/authorize";

    /** 이 기능에 필요한 동의항목. 카카오 개발자 화면에서 켜 두어야 한다. */
    public static final String SCOPE = "talk_message";

    private final RestTemplate restTemplate;
    private final String restApiKey;
    private final String clientSecret;
    private final String prompt;

    public KakaoClient(
            @Qualifier("directRestTemplate") RestTemplate restTemplate,
            @Value("${estate.kakao.rest-api-key:}") String restApiKey,
            @Value("${estate.kakao.client-secret:}") String clientSecret,
            @Value("${estate.kakao.prompt:login}") String prompt) {
        this.restTemplate = restTemplate;
        this.restApiKey = restApiKey == null ? "" : restApiKey.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.prompt = prompt == null ? "" : prompt.trim();
    }

    public boolean isConfigured() {
        return StringUtils.hasText(restApiKey);
    }

    /**
     * 화면이 사용자를 보낼 카카오 동의 화면 주소.
     *
     * <p><b>주소를 화면이 직접 만들지 않게 서버가 준다</b> — 앱 키와 동의항목 이름이 화면 코드에
     * 박히면, 키를 바꿀 때 화면도 다시 배포해야 한다.
     *
     * @param redirectUri 카카오 개발자 화면에 <b>똑같이</b> 등록해 둔 주소여야 한다(한 글자라도
     *                    다르면 카카오가 거절한다). 토큰을 받을 때도 같은 값을 다시 보낸다.
     */
    public String authorizeUrl(String redirectUri, String state) {
        return AUTHORIZE_URL
                + "?response_type=code"
                + "&client_id=" + enc(restApiKey)
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc(SCOPE)
                /*
                  **로그인 화면을 건너뛰지 않게 한다**(2026-11-26 사용자 지적).

                  카카오는 브라우저에 카카오 세션이 남아 있고 이미 동의한 앱이면 **아무것도 묻지
                  않고 곧바로 되돌려 보낸다.** 연결을 끊었다가 다시 누른 사람 눈에는 "로그인창이
                  안 뜨고 저절로 연결됐다" 로 보이고, **다른 카카오 계정으로 바꿀 수도 없다.**

                  `prompt=login` 이면 세션과 무관하게 로그인 화면을 다시 거친다.
                  계정 고르기만 원하면 `select_account`, 종전처럼 두려면 설정을 비운다.
                */
                + (StringUtils.hasText(prompt) ? "&prompt=" + enc(prompt) : "")
                + (StringUtils.hasText(state) ? "&state=" + enc(state) : "");
    }

    /** 동의 화면이 돌려준 code 를 토큰으로 바꾼다. 응답에 refresh_token 이 들어 있다. */
    public Map<String, Object> exchangeCode(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", restApiKey);
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        if (StringUtils.hasText(clientSecret)) {
            form.add("client_secret", clientSecret);
        }
        return postForm(TOKEN_URL, form, null, "토큰 받기");
    }

    /**
     * 갱신 토큰으로 접근 토큰을 다시 받는다.
     *
     * <p>응답에 {@code refresh_token} 이 <b>함께 올 때만</b> 갱신 토큰도 바뀐 것이다(카카오는
     * 남은 기간이 넉넉하면 보내지 않는다). 없다고 지우지 말 것 — 지우면 연결이 끊긴다.
     */
    public Map<String, Object> refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", restApiKey);
        form.add("refresh_token", refreshToken);
        if (StringUtils.hasText(clientSecret)) {
            form.add("client_secret", clientSecret);
        }
        return postForm(TOKEN_URL, form, null, "토큰 갱신");
    }

    /**
     * "나와의 채팅" 으로 글 하나를 보낸다.
     *
     * @param text    본문(카카오 기본 템플릿의 텍스트 칸, 200자까지 들어간다)
     * @param linkUrl 눌렀을 때 열 주소. 카카오 개발자 화면에 등록한 도메인이라야 한다 —
     *                아니면 메시지는 가고 링크만 동작하지 않는다.
     */
    public void sendMemo(String accessToken, String text, String linkUrl, String buttonTitle) {
        String template = "{"
                + "\"object_type\":\"text\","
                + "\"text\":\"" + escapeJson(text) + "\","
                + "\"link\":{\"web_url\":\"" + escapeJson(linkUrl) + "\","
                + "\"mobile_web_url\":\"" + escapeJson(linkUrl) + "\"},"
                + "\"button_title\":\"" + escapeJson(buttonTitle) + "\""
                + "}";
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("template_object", template);
        postForm(SEND_URL, form, accessToken, "메시지 보내기");
    }

    private Map<String, Object> postForm(
            String url, MultiValueMap<String, String> form, String bearer, String what) {
        HttpHeaders headers = new HttpHeaders();
        // 카카오는 form 형식만 받는다. 한글이 깨지지 않게 charset 을 분명히 적는다.
        headers.setContentType(new MediaType(MediaType.APPLICATION_FORM_URLENCODED, StandardCharsets.UTF_8));
        if (StringUtils.hasText(bearer)) {
            headers.setBearerAuth(bearer);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restTemplate
                    .exchange(url, HttpMethod.POST, new HttpEntity<>(form, headers), Map.class)
                    .getBody();
            return body == null ? new LinkedHashMap<>() : body;
        } catch (HttpStatusCodeException e) {
            // **본문을 그대로 남기지 않는다** — 토큰이 섞여 들어올 수 있다. 카카오의 오류 코드만 읽는다.
            throw new KakaoException(what + " 실패", e.getStatusCode().value(), kakaoCodeOf(e), e);
        } catch (RestClientException e) {
            throw new KakaoException(what + " 실패(" + e.getClass().getSimpleName() + ")", 0, null, e);
        }
    }

    /**
     * 카카오 오류 본문에서 <b>오류 코드만</b> 꺼낸다. 본문 전체를 로그에 남기지 않기 위한 것이다.
     *
     * <p><b>두 가지 형식이 있다</b>(2026-11-26 에 걸렸다). 토큰 발급은 OAuth 규격이라
     * {@code {"error":"invalid_client","error_code":"KOE010"}} 이고, 그 밖의 API 는 카카오 자체
     * 형식이라 {@code {"code":-401}} 이다. 앞쪽을 읽지 못해 로그에 <b>"HTTP 401" 만 찍히고
     * 원인(Client Secret 누락)을 알 수 없었다</b> — 둘 다 읽을 것.
     */
    private String kakaoCodeOf(HttpStatusCodeException e) {
        try {
            String body = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            String oauth = quotedValueAfter(body, "\"error_code\"");
            if (oauth != null) {
                return oauth;
            }
            // `"error_code"` 안에는 `"code"`(앞뒤 따옴표 포함)가 들어 있지 않으므로 헷갈리지 않는다
            int at = body.indexOf("\"code\"");
            if (at < 0) {
                return null;
            }
            StringBuilder digits = new StringBuilder();
            for (int i = at + 6; i < body.length(); i++) {
                char c = body.charAt(i);
                if (Character.isDigit(c) || (c == '-' && digits.length() == 0)) {
                    digits.append(c);
                } else if (digits.length() > 0) {
                    break;
                }
            }
            return digits.length() == 0 ? null : digits.toString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** {@code "키":"값"} 에서 값만. 없으면 null. 코드 값은 영숫자뿐이라 이스케이프를 다루지 않는다. */
    private static String quotedValueAfter(String body, String key) {
        int at = body.indexOf(key);
        if (at < 0) {
            return null;
        }
        int open = body.indexOf('"', at + key.length());
        if (open < 0) {
            return null;
        }
        int close = body.indexOf('"', open + 1);
        if (close < 0 || close - open > 40) {
            return null;
        }
        String value = body.substring(open + 1, close);
        return value.isEmpty() ? null : value;
    }

    private static String enc(String value) {
        return UriUtils.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 템플릿을 문자열로 조립하므로 따옴표·줄바꿈을 반드시 막는다(단지 이름에 따옴표가 있을 수 있다). */
    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("");
                case '\t' -> out.append(" ");
                default -> {
                    if (c < 0x20) {
                        out.append(' ');
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** 카카오 호출이 실패했을 때. 상태 코드와 카카오 오류 코드만 들고 다닌다(토큰·본문은 없다). */
    public static class KakaoException extends RuntimeException {
        private final int status;
        private final String kakaoCode;

        public KakaoException(String message, int status, String kakaoCode, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.kakaoCode = kakaoCode;
        }

        public int getStatus() {
            return status;
        }

        public String getKakaoCode() {
            return kakaoCode;
        }

        /** 사람이 읽을 한 줄. DB 의 `last_error` 에 넣는 값이라 길이를 짧게 둔다. */
        public String summary() {
            return getMessage() + " (HTTP " + status
                    + (kakaoCode == null ? "" : ", " + kakaoCode) + ")";
        }

        /** 설정이 잘못돼 <b>다시 시도해도 소용없는</b> 경우 — 앱 키·Client Secret 문제다. */
        public boolean isClientConfigError() {
            return "KOE010".equals(kakaoCode);
        }

        /** 동의가 풀렸거나 토큰이 죽은 경우 — 다시 연결해야 하므로 재시도해도 소용없다. */
        public boolean needsRelink() {
            if (isClientConfigError()) {
                // 사용자가 다시 연결해도 똑같이 실패한다 — 고칠 곳은 설정이다
                return false;
            }
            return status == 401 || "-401".equals(kakaoCode) || "-3".equals(kakaoCode);
        }
    }
}
