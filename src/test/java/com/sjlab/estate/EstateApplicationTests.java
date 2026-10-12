package com.sjlab.estate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 컨텍스트 로딩 스모크 테스트.
 *
 * DB 접속 정보 없이도 떠야 한다 — 그 상태에서 조회만 503 이고 /status 는 응답하는 것이
 * 이 서비스의 설계다. Eureka 등록은 테스트에서 끈다.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "estate.datasource.url="
})
@ActiveProfiles("test")
class EstateApplicationTests {

    @Test
    void contextLoads() {
    }
}
