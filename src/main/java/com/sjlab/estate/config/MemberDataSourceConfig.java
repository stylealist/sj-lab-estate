package com.sjlab.estate.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 회원 데이터(즐겨찾기 · 최근 본 단지 · 메모)용 <b>두 번째</b> 커넥션 풀.
 *
 * <p><b>왜 따로 여는가.</b> 이 서비스의 실거래 커넥션({@code estate-pool})은 <b>읽기 전용</b>이다 —
 * 실수로 수집 데이터에 쓰는 SQL 이 들어가면 DB 가 거부하게 해 둔 안전장치이고, 그대로 둔다.
 * 회원 데이터는 사람이 화면에서 만드는 것이라 써야 하므로, 쓰기가 되는 풀을 <b>작게</b> 따로 연다.
 * 하나를 쓰기 가능으로 바꿔 버리면 그 안전장치가 서비스 전체에서 사라진다.
 *
 * <p><b>접속 정보는 실거래와 같은 것을 쓴다</b>({@code estate.datasource.*}). 같은 DB 의 다른
 * 스키마({@code member})일 뿐이라 새 환경변수도, 새 Secret 도, 차트 수정도 필요 없다.
 * 쓰기 권한은 DB 쪽에서 {@code member} 스키마에만 준다(스크립트
 * {@code sj-lab/db/estate/estate_member.sql}) — 그래서 이 풀로도 실거래 표는 건드릴 수 없다.
 *
 * <p><b>MyBatis 가 아니라 {@link JdbcTemplate} 을 쓴다.</b> MyBatis 자동설정은 DataSource 가
 * 하나일 때를 전제로 돌아가, 두 번째 매퍼 묶음을 붙이려면 SqlSessionFactory 를 손으로 짜고
 * 자동 스캔과 충돌을 풀어야 한다. 회원 CRUD 는 동적 SQL 이 없는 단순한 질의라 그만한 값어치가
 * 없다 — 실거래 매퍼(XML)는 종전 그대로 두고 여기만 JdbcTemplate 으로 간다.
 */
@Configuration
@ConfigurationProperties(prefix = "estate.member-datasource")
public class MemberDataSourceConfig {

    /** 회원 쓰기는 몰리지 않는다(사람이 단추를 누를 때만). 실거래 풀 16 과 달리 작게 잡는다. */
    private int poolSize = 4;

    @Bean
    // 실거래 접속 정보가 있을 때만 만든다 — 없으면 회원 기능도 "준비 안 됨" 으로 답한다.
    @ConditionalOnExpression("'${estate.datasource.url:}' != ''")
    public DataSource estateMemberDataSource(EstateDataSourceConfig estateConfig) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(estateConfig.getUrl());
        dataSource.setUsername(estateConfig.getUsername());
        dataSource.setPassword(estateConfig.getPassword());
        dataSource.setMaximumPoolSize(poolSize);
        dataSource.setPoolName("estate-member-pool");
        // 여기만 쓰기 가능하다. 실거래 풀은 종전대로 읽기 전용이다.
        dataSource.setReadOnly(false);
        // **이 한 줄이 없으면 INSERT 가 25006 으로 거부된다**(2026-11-24 실제로 겪음).
        // 조회용으로 만든 DB 계정에는 보통 `default_transaction_read_only = on` 이 걸려 있고,
        // 그러면 **계정 기본값이 이겨서** 위의 setReadOnly(false) 로는 뒤집히지 않는다
        // (PostgreSQL JDBC 드라이버는 "읽기 전용으로 바꿀 때만" SET 을 보내고, 되돌릴 때는
        // 아무것도 보내지 않는다). 그래서 이 풀의 커넥션마다 쓰기 가능하다고 명시한다.
        // **실거래 풀에는 절대 넣지 말 것** — 그 풀의 읽기 전용 보호가 사라진다.
        dataSource.setConnectionInitSql("SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE");
        dataSource.setConnectionTimeout(10_000);
        // 실거래 풀과 같은 이유 — DB 가 NodePort 로 멀리 있어 쉬는 연결이 중간에서 끊긴다.
        dataSource.setKeepaliveTime(120_000);
        dataSource.setMaxLifetime(600_000);
        dataSource.setValidationTimeout(3_000);
        return dataSource;
    }

    @Bean
    @ConditionalOnExpression("'${estate.datasource.url:}' != ''")
    // **@Qualifier 를 빼지 말 것.** 실거래 DataSource 가 @Primary 라, 이름이 같아도
    // 타입으로 찾을 때 그쪽이 먼저 잡혀 회원 쓰기가 읽기 전용 풀로 가 버린다.
    public JdbcTemplate memberJdbcTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("estateMemberDataSource")
                    DataSource memberDataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(memberDataSource);
        // 회원 질의는 모두 작다. 오래 걸리면 무언가 잘못된 것이므로 기다리지 않고 끊는다.
        jdbcTemplate.setQueryTimeout(5);
        return jdbcTemplate;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }
}
