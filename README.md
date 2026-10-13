# sj-lab-estate

sj-lab 플랫폼의 **부동산 실거래 조회 API** 입니다. 국토교통부가 공개하는 아파트 매매·전월세
실거래(약 2,100만 건, 2006-01 ~ 현재)를 읽어 월별 추세·지역 순위·거래 목록을 내려줍니다.

- 화면: [sj-lab-estate-web](https://sj-lab.co.kr) 의 "부동산 실거래" (허브 카드로 진입)
- 외부 경로: `api.sj-lab.co.kr/estate/**` (게이트웨이 → Eureka `SJ-LAB-ESTATE`)
- 적재: [sj-lab-scheduler](https://github.com/stylealist/sj-lab-scheduler) 가 매일 수집 → PostgreSQL `estate` 스키마
- 이 서비스는 **읽기만** 합니다(커넥션도 read-only 로 엽니다)

```
sj-lab-estate-web → sj-lab-apigateway(/estate/**) → sj-lab-estate → PostgreSQL(estate)
                                                                     ▲ sj-lab-scheduler 가 적재
```

## 엔드포인트

| 경로 | 돌려주는 것 |
|---|---|
| `GET /estate/status` | 사용 가능 여부와 매물 종류 목록(아파트 · 오피스텔 · 빌라) |
| `GET /estate/{종류}/meta` | 건수(추정)·수집 기간·마지막 적재 시각 |
| `GET /estate/{종류}/regions` | 거래가 있는 시군구(코드 · 이름 · 건수 + 시도 코드 · 이름) |
| `GET /estate/{종류}/regions/emd?sggCd=` | 그 시군구의 읍면동(이름 · 건수) |
| `GET /estate/{종류}/trade/monthly` | 매매 월별 건수·평균·중위·최저·최고·㎡당 |
| `GET /estate/{종류}/rent/monthly` | 전월세 월별 전세/월세 건수와 평균 보증금·월세 |
| `GET /estate/{종류}/trade` | 매매 거래 목록(최근 계약일 순, 단지명 부분 검색) |
| `GET /estate/{종류}/trade/ranking` | 기간 내 시군구 평균 매매가 순위 |

공통 질의 파라미터는 `sggCd`(숫자 5자리) · `umdNm`(읍면동 **이름**) · `fromYm` · `toYm`(yyyyMM) · `limit` 입니다.
순위는 `sidoCd`(숫자 2자리)로 그 시도 안에서만 낼 수 있습니다. `umdNm` 은 `sggCd` 와 함께 주세요
(같은 동 이름이 여러 지역에 있어 단독으로는 400입니다).
금액 단위는 원천 그대로 **만원**이고, 억/만원 표기는 화면이 합니다.

```bash
curl "http://localhost:8100/estate/apt/trade/monthly?sggCd=11680&fromYm=202601&toYm=202609"
```

**매물 종류는 경로 조각**입니다(`apt`·`offi`·`villa`). 국토교통부가 종류별로 다른 API 를 주고
항목도 달라 DB 표가 나뉘기 때문입니다. 지금은 아파트만 서비스하고 나머지는 `503` 과 함께
준비 중임을 알려 줍니다(`404` 로 두면 주소가 틀린 것으로 읽혀 원인을 찾기 어렵습니다).

## 실행

```
mvnw.cmd clean package
java -jar target\sj-lab-estate.jar --spring.profiles.active=local --server.port=8120
```

`local` 프로파일이 Eureka(`localhost:8761`)를 가리킵니다. 총괄 저장소(`sj-lab`)의
`scripts\local-stack.ps1 start` 를 쓰면 Eureka·게이트웨이와 함께 한 번에 뜹니다.

### 설정(환경변수)

| 이름 | 설명 |
|---|---|
| `ESTATE_DB_URL` | `jdbc:postgresql://호스트:5432/DB명` |
| `ESTATE_DB_USERNAME` / `ESTATE_DB_PASSWORD` | 조회 전용 계정 권장 |
| `ESTATE_DB_POOL_SIZE` | 커넥션 수(기본 5) |
| `SERVER_PORT` | 기본 0(임의 포트). 직접 확인할 때만 지정 |

**값을 저장소에 적지 않습니다**(이 저장소는 public). 로컬은 `sj-lab/.claude/settings.local.json`,
운영은 k8s Secret 으로 넣습니다.

**접속 정보가 없어도 서비스는 뜹니다.** 그 상태에서 조회 API 는 `503 NOT_CONFIGURED` 이고
`GET /estate/status` 는 `ready: false` 로 답해, 화면이 "준비되지 않았다"를 사용자에게 보여 줄 수
있습니다. 기동 자체를 막으면 그조차 알릴 수 없기 때문에 이렇게 두었습니다.

## 성능에 관해 (실측)

데이터가 2,100만 행이라 "맞게 쓴 SQL"과 "인덱스를 타는 SQL"의 차이가 큽니다.

| 조회 | 전 | 후 | 원인 |
|---|---|---|---|
| 요약(meta) | 32초 | 0.1초 | `max(created_at)` 전건 스캔 → 최근 거래 월로 범위를 좁힘 |
| 지역 순위 | 23.5초 | 1.5초 | 기간을 `연*100+월` **식**으로 비교해 인덱스 미사용 → 연도 범위로 바꿈 |
| 지역 1곳 추세 | — | 0.04초 | 집계 뷰 사용 |
| 지역 목록 | 6.4초 | 0.04초 | 전체를 세는 조회라 느림 → 6시간 캐시 + 기동 시 예열(첫 방문자가 기다리지 않게) |
| 전국 추세 | 8.6초(매매) · 28.7초(전월세) | — | 평균·중위 때문에 금액·면적을 실제로 읽어야 함(미리 집계하면 해결) |

건수는 `count(*)` 가 아니라 통계 추정치를 쓰고 응답에 `rows_estimated: true` 로 밝힙니다.

## 현재 한계

- **운영 배포 전**입니다(Helm 차트 · Jenkins 잡 · 운영 DB Secret 이 남았습니다).
- **전국 단위 추세는 느립니다.** 미리 집계해 두는 스크립트가 총괄 저장소에 있고(DB 담당자 실행),
  실행 전에도 화면은 동작합니다(지역을 고르면 즉시 나옵니다).
- **오피스텔 · 빌라는 수집부터 필요**합니다(종류마다 원천 API 와 항목이 다릅니다).
- **좌표가 없어 지도 표시는 없습니다.** 원천 데이터에 지번·도로명만 있어, 단지 주소를 좌표로
  바꾸는 작업이 선행되어야 합니다.
- 인증을 요구하지 않습니다(플랫폼의 다른 조회 API 와 같습니다).
