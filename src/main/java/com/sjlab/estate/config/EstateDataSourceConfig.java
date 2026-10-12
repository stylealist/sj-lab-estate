package com.sjlab.estate.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 부동산 DB 접속.
 *
 * <p><b>접속 정보가 없으면 DataSource 를 만들지 않는다.</b> 그러면 MyBatis 자동설정도 건너뛰어
 * 매퍼 빈이 없는 상태로 서비스가 뜨고, 조회 API 만 503(NOT_CONFIGURED) 으로 답한다
 * ({@code EstateService} 가 매퍼를 {@code ObjectProvider} 로 받아 비었는지 본다).
 * 기동 자체를 막으면 화면이 "준비되지 않았다"조차 알려 줄 수 없어 이렇게 했다.
 *
 * <p>조회 전용 서비스이므로 커넥션을 <b>읽기 전용</b>으로 연다 — 실수로 쓰기 SQL 이 들어가면
 * 조용히 성공하는 대신 DB 가 거부하게 하는 쪽이 안전하다.
 */
@Configuration
@ConfigurationProperties(prefix = "estate.datasource")
public class EstateDataSourceConfig {

    private String url = "";
    private String username = "";
    private String password = "";
    private int poolSize = 5;

    @Bean
    // 빈 문자열이면 만들지 않는다. @ConditionalOnProperty 는 "값이 빈 문자열"도 있는 것으로 보기 때문에
    // 식으로 비교한다(환경변수를 안 주면 application.yml 기본값이 빈 문자열이다).
    @ConditionalOnExpression("'${estate.datasource.url:}' != ''")
    public DataSource estateDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setMaximumPoolSize(poolSize);
        dataSource.setPoolName("estate-pool");
        dataSource.setReadOnly(true);
        dataSource.setConnectionTimeout(5_000);
        return dataSource;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }
}
