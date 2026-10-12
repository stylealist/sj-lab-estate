package com.sjlab.estate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * sj-lab 부동산 실거래 조회 서비스.
 *
 * <p>estate 스키마를 <b>읽기만</b> 한다 — 적재는 sj-lab-scheduler 담당이다.
 * 게이트웨이가 {@code /estate/**} 를 이 서비스로 보내고, context-path 도 {@code /estate} 라
 * 컨트롤러 매핑은 그 뒤 부분이다.
 */
// DataSource 는 EstateDataSourceConfig 가 접속 정보가 있을 때만 만든다.
// 스프링 기본 자동설정을 켜 두면 접속 정보가 없을 때 "드라이버를 찾을 수 없다"며
// 기동 자체가 실패해, 화면이 '준비되지 않았다'조차 받을 수 없다.
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class})
@EnableDiscoveryClient
public class EstateApplication {

    public static void main(String[] args) {
        SpringApplication.run(EstateApplication.class, args);
    }
}
