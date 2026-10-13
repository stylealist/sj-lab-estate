# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

sj-lab 저장소를 넘나드는 작업의 총괄 기준 저장소는 `C:\developer\workspace\sj-lab` 입니다.
전체 구조·API 계약은 그 저장소의 `docs/system-architecture.md`, 로컬 포트·기동 순서는
`docs/dev-environment.md` 를 따릅니다.

## 이 서비스의 역할

부동산 실거래(아파트 매매·전월세) **조회 전용** API 입니다. Spring Boot 3.3.2 / Java 17,
Eureka 에 `SJ-LAB-ESTATE` 로 등록되고 게이트웨이가 `/estate/**` 를 넘겨 줍니다.
화면은 `sj-lab-estate-web`(React) 입니다.

**2026-10-12 신설** — 그 전에는 `fast-api-ai` 의 `/fast-api-ai/estate/**` 가 같은 일을 했습니다.
`fast-api-ai` 는 AI 기능만 담기로 해서 분리했고, 응답 형태는 **그대로 유지**했습니다
(화면을 고치지 않아도 되게). 옛 파이썬 코드는 `sj-lab/trash/2026-10-12/fast-api-ai/` 에 있습니다.

```
sj-lab-estate-web → 게이트웨이 /estate/** → 이 서비스 → PostgreSQL estate 스키마(읽기 전용)
                                                        ▲ 적재는 sj-lab-scheduler
```

## 빌드 및 실행

```
mvnw.cmd clean package                                        # target/sj-lab-estate.jar
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local     # local 프로파일 필수(Eureka localhost:8761)
java -jar target\sj-lab-estate.jar --spring.profiles.active=local --server.port=8120
mvnw.cmd test                                                 # 컨텍스트 로딩 스모크
```

총괄 저장소의 `scripts\local-stack.ps1` 이 이 서비스까지 **8120 포트**로 함께 띄웁니다
(접속 정보는 `sj-lab/.claude/settings.local.json` 의 `ESTATE_DB_DSN` 을 JDBC 형식으로 바꿔 넣습니다).

## 반드시 지킬 것

- **쓰기를 넣지 말 것.** 적재는 `sj-lab-scheduler` 담당이고 이 서비스는 조회만 합니다.
  커넥션도 `readOnly` 로 엽니다(실수로 쓰기 SQL 이 들어가면 DB 가 거부하게).
- **접속 정보는 환경변수로만 받습니다**(`ESTATE_DB_URL`/`ESTATE_DB_USERNAME`/`ESTATE_DB_PASSWORD`).
  이 저장소는 public 입니다 — `application.yml` 이나 코드에 값을 적지 말 것.
- **접속 정보가 없어도 서비스는 떠야 합니다.** `EstateDataSourceConfig` 가 DataSource 를 만들지 않고
  (그래서 MyBatis 자동설정도 건너뜀) 조회 API 만 503(`NOT_CONFIGURED`), `GET /estate/status` 는 정상
  응답합니다. 기동을 막으면 화면이 "준비되지 않았다"조차 알려 줄 수 없습니다.
  `DataSourceAutoConfiguration` 을 다시 켜지 말 것(켜면 접속 정보가 없을 때 기동이 실패합니다).
- **응답 키는 DB 컬럼명 그대로**(`deal_count`, `avg_amount`, `sgg_nm` …)이고 목록은 `{"items": [...]}`,
  오류는 `{"detail": {"code", "message"}}` 입니다. 옛 구현과 같은 계약이라 **바꾸면 화면이 깨집니다**
  (화면은 `src/api.js` 에서 `detail.message` 를 꺼내 보여 줍니다). 그래서 결과를 DTO 가 아니라
  `Map` 으로 받습니다 — DTO 면 Jackson 이 camelCase 로 바꿔 내보냅니다.
- **기간 조건을 `deal_year * 100 + deal_month` 같은 식으로 비교하지 말 것.** 식에는 인덱스가 걸리지
  않아 전건을 훑습니다(실측 지역 순위 23.5초 → 고친 뒤 1.5초). 연도를 범위로 좁힌 뒤 경계 연도에서만
  월을 봅니다(`EstateMapper.xml` 의 `ymFilter`).
- **지역은 시도 → 시군구 → 읍면동 3단계다**(2026-10-13).
  - 시도는 **시군구 코드 앞 2자리**다. 시군구 목록 응답에 `sido_cd`·`sido_nm` 을 함께 담아
    화면이 시도 목록을 그 응답만으로 만든다(호출을 늘리지 않는다).
  - **이름 조인을 무거운 집계 쿼리에 더 넣지 말 것.** 시도 이름까지 `group by` 에 넣었더니
    5.1초 → **19.7초**가 됐다(실측). 이름은 `selectSidoNames`(17행)로 따로 읽어 서비스가 붙인다.
  - **읍면동은 코드가 아니라 이름(`umd_nm`)으로 가린다.** 매매 표에는 `umd_cd` 가 있지만
    전월세 표에는 없어 두 표에 공통인 키가 이름뿐이고, 자연키 인덱스도 이름 기준이다.
    `umdNm` 만 주고 `sggCd` 를 빼면 400 이다(같은 동 이름이 여러 지역에 있다).
  - 읍면동까지 좁힌 추세는 **집계 뷰를 쓸 수 없다**(뷰가 시군구 단위로 묶여 있다). 원본에서
    계산하며 `(sgg_cd, umd_nm)` 으로 자연키 인덱스를 타 수십 ms 다(실측 추세 57ms·목록 64ms).
  - 순위(`/trade/ranking`)는 `sidoCd` 로 범위를 좁힐 수 있다 — **전국 4.7초, 시도 0.6초**(실측).
    앞 2자리를 `substring` 으로 자르면 인덱스를 못 타므로 **코드 범위(`between`)로** 좁힌다.
- **지역 목록(`/regions`)은 캐시한다.** `group by sgg_cd` + `count(*)` 는 인덱스를 타도 990만 건을
  끝까지 읽어 **5.1초**다(실측). 결과는 수집이 돌 때만 바뀌므로 6시간 캐시하고, **기동 직후
  별도 스레드로 예열**한다 — 기다리는 쪽이 사람이 아니라 서버가 되게 하는 것이 목적이다.
  새로 받지 못하면 기한이 지난 값이라도 돌려준다(목록이 비면 화면이 먹통이 된다).
  첫 화면 렉의 주원인이 이것이었다(2026-10-13, 6.4초 → 40ms).
- **건수는 `count(*)` 로 세지 말 것.** 두 표가 2,100만 행이라 32초가 걸립니다. 통계(`reltuples`)
  추정치를 쓰고 응답에 `rows_estimated: true` 로 밝힙니다. 지역 수도 세지 않습니다(화면이 목록 길이로 셈).
- **전세·월세 구분은 `monthly_rent = 0`** 하나로 합니다(화면도 같은 기준).
- 금액 단위는 **만원**입니다(원천 공개데이터 그대로). 억/만원 표기는 화면이 합니다.
- 매물 종류(`apt`·`offi`·`villa`)는 **경로 조각**입니다. 준비 중인 종류는 404 가 아니라 **503 + 이유**로
  답합니다(404 면 "주소가 틀렸나"로 읽혀 원인을 찾기 어렵습니다). 모르는 종류는 400.
  종류를 열 때는 `EstateService.PROPERTY_TYPES` 의 `available` 을 바꾸면 화면이 따라옵니다
  (`GET /estate/status` 가 그 목록을 내려줍니다).

## 엔드포인트

| 경로 | 설명 |
|---|---|
| `GET /estate/status` | 사용 가능 여부 + 매물 종류 목록(설정 없어도 응답) |
| `GET /estate/{propertyType}/meta` | 건수(추정)·기간·마지막 적재 |
| `GET /estate/{propertyType}/regions` | 거래가 있는 시군구 목록 |
| `GET /estate/{propertyType}/trade/monthly?sggCd=&fromYm=&toYm=` | 매매 월별 추세 |
| `GET /estate/{propertyType}/rent/monthly?sggCd=&fromYm=&toYm=` | 전월세 월별 추세 |
| `GET /estate/{propertyType}/trade?sggCd=&fromYm=&toYm=&aptNm=&limit=` | 매매 거래 목록(최근 순, 최대 1000) |
| `GET /estate/{propertyType}/trade/ranking?fromYm=&toYm=&limit=` | 지역별 평균 매매가 순위(10건 이상) |

- 지역을 고르면 집계 뷰(`estate.v_apt_trade_monthly`·`v_apt_rent_monthly`)를 읽고, 전국이면 원본에서
  다시 합칩니다(뷰가 지역별이라 그대로 평균하면 틀립니다). **전국 조회는 느립니다**(매매 8.6초·전월세 28.7초) —
  미리 집계하는 스크립트가 `sj-lab/db/estate/estate_apt_monthly_matview.sql` 이고 실행은 DB 담당자가 합니다.

## 현재 범위와 남은 작업

- 운영 배포 전입니다 — Helm 차트(`sj-lab-k8s-manifests`)와 Jenkins 잡, 운영 DB 접속 Secret 이 필요합니다.
- 지도용 좌표가 없습니다(원천 데이터에 좌표가 없음). 단지 좌표 표를 만들면 이 서비스에 지도용
  조회를 추가하는 것이 자연스럽습니다.
- 오피스텔·빌라는 수집·표부터 필요합니다.
