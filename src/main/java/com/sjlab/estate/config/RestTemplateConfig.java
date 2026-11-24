package com.sjlab.estate.config;

import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 로그인 서버를 부를 때 쓰는 {@link RestTemplate} 두 개.
 *
 * <ul>
 *   <li><b>loadBalancedRestTemplate</b> — 주소가 {@code http://SJ-LAB-AUTHSERVER} 처럼 서비스
 *       이름일 때. Eureka 에서 실제 주소를 찾아 간다(운영).</li>
 *   <li><b>directRestTemplate</b> — {@code http://localhost:8100} 처럼 주소를 직접 적었을 때.
 *       @LoadBalanced 가 붙은 것으로 실제 호스트를 부르면 "서비스를 찾을 수 없다" 로 실패한다.</li>
 * </ul>
 *
 * <p>시간 제한을 짧게 둔다 — 로그인 확인은 화면이 기다리는 길목이라, 로그인 서버가 느려졌을 때
 * 부동산 화면까지 함께 멈추면 안 된다.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    @LoadBalanced
    public RestTemplate loadBalancedRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(2))
                .setReadTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Bean
    public RestTemplate directRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(2))
                .setReadTimeout(Duration.ofSeconds(3))
                .build();
    }
}
