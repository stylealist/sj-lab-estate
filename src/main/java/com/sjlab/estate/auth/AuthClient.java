package com.sjlab.estate.auth;

import com.sjlab.estate.controller.EstateController;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * "이 요청은 누구 것인가" 를 <b>sj-lab-authserver 에 물어본다</b>({@code GET /auth/me}).
 *
 * <p>JWT 서명 키를 서비스마다 복사해 두지 않기 위해서다 — 키가 여러 곳에 퍼지면 바꿀 때
 * 한 곳이 빠져 조용히 로그인이 깨진다. 공개 API(`sj-lab-openapi`)도 같은 방식이다.
 *
 * <p><b>토큰을 짧게 기억한다</b>(60초). 즐겨찾기 화면 하나가 목록·메모·최근 본 단지를 잇따라
 * 부르는데, 그때마다 로그인 서버를 왕복하면 화면이 눈에 띄게 굼떠진다. 기억하는 것은
 * <b>토큰 → 계정</b>뿐이고 토큰 자체는 로그에 남기지 않는다.
 */
@Component
public class AuthClient {

    private static final Logger log = LoggerFactory.getLogger(AuthClient.class);
    private static final long CACHE_TTL_MS = 60_000L;
    /** 한 서버가 들고 있을 토큰 수의 상한. 넘으면 통째로 비운다(다시 물어보면 그만이다). */
    private static final int CACHE_MAX = 500;

    private final RestTemplate restTemplate;
    private final String authBaseUrl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public AuthClient(
            @Qualifier("loadBalancedRestTemplate") RestTemplate loadBalanced,
            @Qualifier("directRestTemplate") RestTemplate direct,
            @Value("${estate.auth.base-url:http://SJ-LAB-AUTHSERVER}") String authBaseUrl) {
        this.authBaseUrl = authBaseUrl;
        // 주소가 서비스 이름(점·콜론이 없고 localhost 도 아님)이면 Eureka 로 찾아간다.
        String host = authBaseUrl.replaceFirst("^https?://", "").split("/")[0];
        boolean serviceName = !host.matches(".*[.:].*") && !host.contains("localhost");
        this.restTemplate = serviceName ? loadBalanced : direct;
    }

    /** Authorization 헤더의 토큰이 누구 것인지 확인한다. 없거나 틀리면 401. */
    public String requireUsername(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)) {
            throw new EstateController.UnauthorizedException(
                    "LOGIN_REQUIRED", "로그인이 필요합니다.");
        }

        Cached cached = cache.get(authorizationHeader);
        if (cached != null && System.currentTimeMillis() - cached.at < CACHE_TTL_MS) {
            return cached.username;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
        try {
            @SuppressWarnings("rawtypes")
            Map body = restTemplate
                    .exchange(authBaseUrl + "/auth/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class)
                    .getBody();
            Object username = body == null ? null : body.get("username");
            if (username == null || !StringUtils.hasText(username.toString())) {
                throw new EstateController.UnauthorizedException(
                        "LOGIN_REQUIRED", "로그인 정보를 확인하지 못했습니다.");
            }
            if (cache.size() >= CACHE_MAX) {
                cache.clear();
            }
            cache.put(authorizationHeader, new Cached(username.toString()));
            return username.toString();
        } catch (HttpStatusCodeException e) {
            // 틀린 토큰은 기억하지 않는다 — 다시 로그인하면 바로 통해야 한다.
            cache.remove(authorizationHeader);
            throw new EstateController.UnauthorizedException(
                    "LOGIN_REQUIRED", "로그인이 만료됐거나 올바르지 않습니다. 다시 로그인해 주세요.");
        } catch (RestClientException e) {
            // 로그인 서버가 잠깐 죽은 것과 "로그인 안 함" 은 다른 이야기다.
            log.warn("로그인 서버 호출 실패: {}", e.getClass().getSimpleName());
            throw new EstateController.NotReadyException(
                    "AUTH_UNAVAILABLE", "로그인 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private static final class Cached {
        private final String username;
        private final long at = System.currentTimeMillis();

        private Cached(String username) {
            this.username = username;
        }
    }
}
