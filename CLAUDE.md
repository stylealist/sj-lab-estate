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
- **단지(아파트) 단위 조회는 `sgg_cd` 조건을 반드시 함께 건다**(2026-10-14).
  단지 키 `apt_seq`(예: `11680-218`)에는 **인덱스가 없다** — 단독으로 조회하면 990만 건을 전부
  훑는다. 앞 5자리가 시군구 코드이므로 서비스가 잘라 넣어 `(sgg_cd, …)` 인덱스를 타게 한다
  (실측 134ms, 읍면동까지 주면 더 좁아진다). `EstateService.sggCdOf()` 가 그 조립을 맡는다.
- **평형은 분양 평형(공급면적 기준)이다**(2026-10-28 바뀜). 원천에는 전용면적만 있어 종전에는
  전용 평을 그대로 썼는데, **전용 84㎡ 를 "25평"으로 적어 시장에서 부르는 이름(34평)과 달랐다**
  (사용자 지적). 통상 전용률 **0.75** 로 공급면적을 되짚어 환산한다
  (`round(exclu_use_ar / 3.3058 / 0.75)`) — 전용 84 → 34평 · 74 → 30평 · 59 → 24평.
  - **추정이므로 `area_min`·`area_max`(전용 ㎡)를 응답에 함께 담고 화면이 "전용 OO㎡"를 같이
    보여 준다.** 전용률은 평형이 클수록 높아져 아주 큰 평형은 1~2평 차이가 날 수 있다.
  - `pyeong` 은 **조회 키로도 쓰인다**(`and round(...) = #{pyeong}`). 식을 고칠 때는
    `EstateMapper.xml` 의 **6곳을 모두** 같이 고칠 것 — 한 곳만 다르면 그 평형이 조회되지 않는다.
    프론트의 `toPyeong()`(`src/api.js`)도 같은 식이어야 한다.
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
| `GET /estate/{propertyType}/map/regions?level=sido\|sgg\|emd&...&stats=` | 지도 경계 + 그 기간 평균가(GeoJSON) |
| `GET /estate/{propertyType}/map/complexes?bbox=&fromYm=&toYm=&limit=` | 지도 아파트 핀(GeoJSON Point) |

- 지역을 고르면 집계 뷰(`estate.v_apt_trade_monthly`·`v_apt_rent_monthly`)를 읽고, 전국이면 원본에서
  다시 합칩니다(뷰가 지역별이라 그대로 평균하면 틀립니다). **전국 조회는 느립니다**(매매 8.6초·전월세 28.7초) —
  미리 집계하는 스크립트가 `sj-lab/db/estate/estate_apt_monthly_matview.sql` 이고 실행은 DB 담당자가 합니다.

## 지도 조회 (2026-10-15 추가)

화면이 호갱노노 형태(본문 전체가 지도)로 바뀌면서 두 가지를 추가했습니다.

- **경계와 집계를 따로 읽습니다.** 경계는 기간과 무관하고 깎는(단순화) 비용이 커서 한 번 읽어
  `boundaryCache` 에 둡니다. 기간이 걸린 집계만 그때그때 읽어 Java 가 합칩니다(FeatureCollection
  문자열을 직접 이어 붙입니다 — geometry 는 DB 가 만든 JSON 이라 그대로 넣으면 됩니다).
- **전국(시도) 평균은 990만 건을 모두 읽어 28초입니다**(2026-10-15 실측). 그래서 세 가지를 둡니다.
  **세 가지를 지우면 첫 화면이 수십 초 멈춥니다.**
  1. 기동 시 **화면 기본 기간(최근 12개월)으로 미리 계산**합니다(`warmUpSidoStats`).
  2. 캐시에 없으면 **경계만 먼저 보내고(`stats_pending:true`) 뒤에서 계산**합니다. 화면이 6초 뒤
     한 번 더 물어 색을 채웁니다.
  3. 지도가 "지금 어느 시도를 보고 있나"만 알려고 부를 때는 **`stats=0`**(경계만)으로 받습니다 —
     보지도 않는 집계를 깨우지 않기 위한 것입니다.
- **시도·시군구 경계는 기동 직후 백그라운드로 모두 예열합니다**(`warmUpRemainingSido`, 14개 60초).
  경기도처럼 시군구가 많은 곳은 첫 호출이 12.7초였습니다.
- **시군구·읍면동은 조립까지 끝낸 응답을 캐시합니다**(2026-10-28, `mapJsonCache`, 6시간).
  경계만 캐시해서는 부족했습니다 — 집계는 호출마다 DB 를 읽어 **지도를 옮길 때마다 경기도가
  1.1초**였고(`stats=0` 은 시도에만 있어 "경계만" 받는 호출도 같은 값이었습니다), 지도 이동이
  느리다는 지적의 원인이었습니다. 집계는 수집이 돌 때만 바뀌므로 응답 문자열을 그대로 들고
  있다가 내줍니다 — **실측 경기 1.1초 → 205ms · 서울 0.5초 → 25ms · 읍면동 → 44ms**.
  - **예열은 화면 기본 기간으로 합니다**(`defaultPeriod()`, 최근 12개월 — 프론트
    `buildMonthOptions` 와 같은 규칙). 종전에는 기간 없이(`null`) 예열해 **경계만 캐시에 남고**
    화면이 부르는 키는 비어 있었습니다. 기간을 키에서 빼지 말 것(다른 기간은 값이 달라집니다).
- **아파트 핀(`/map/complexes`)은 `estate.apt_complex` 가 없으면 오류가 아니라** 빈 목록 +
  `coordinates_ready:false` + 사유로 답합니다(표가 없는 것은 정상 상태입니다 — DDL 은 담당자가
  실행합니다). 표 유무는 60초마다 다시 확인해 재기동 없이 반영됩니다.
- **표·컬럼이 "있다"고 확인한 뒤에는 뒤집지 않습니다**(2026-10-22). 종전에는 호출마다 다시 묻고
  오류가 나면 "없음"으로 바꿔, DB 연결이 잠깐 끊기면 화면에 *"단지 좌표 표를 만들면 수집이
  시작됩니다"* 가 뜨고 **핀이 사라졌습니다**(10회 중 9회 실패). 표는 사라지지 않으므로 한 번
  확인하면 유지하고, 확인 실패는 로그만 남깁니다. DataSource 에는 keepalive(2분)·maxLifetime(10분)을
  둬서 **끊긴 연결을 미리 걸러냅니다** — 이 설정들을 지우지 말 것.
- **철거된 옛 단지는 핀에서 뺍니다 — 최근 5년 거래가 있는 단지만 올립니다**(2026-10-22, `alive` CTE).
  재건축이 끝난 자리에는 옛 단지와 새 단지가 **따로** 들어 있습니다(진흥(97-3) 2006~2021 ·
  안양역푸르지오더샵 2026~). 핀은 거래 많은 순으로 뽑히니 **거래가 쌓인 옛 단지가 이기고**,
  좌표·영역은 "지금 서 있는 건물"로 받으므로 **새 아파트 자리에 옛 이름**이 붙어 보였습니다.
  - 기준은 화면에서 고른 기간과 **무관**합니다(기간을 2015년으로 돌려 봐도 철거 단지는 안 뜹니다).
  - 옛 단지의 거래 기록 자체는 지웁니다/숨기지 않습니다 — 목록·추세에서는 그대로 보입니다.
- **`apt_seq` 로 거래를 찾지 말고 자연키로 조인할 것**(2026-10-22, 핀이 안 뜨던 진짜 원인).
  `apt_seq` 에는 인덱스가 없어 `apt_seq in (...)` 로 묻는 순간 그 시군구의 거래를 전부 훑습니다 —
  화면 한 번에 **21.5초**(단지 300개·5개 시군구). 자연키 유니크 인덱스
  `ux_apt_trade_natural(sgg_cd, umd_nm, jibun, apt_nm, ...)` 를 타게
  `t.sgg_cd=p.sgg_cd and t.umd_nm=p.umd_nm and t.jibun=p.jibun and t.apt_nm=p.apt_nm` 로 조인하면
  **0.1초**이고 결과는 완전히 같습니다(222단지·1,997건 동일 확인). 화면 응답은 2초.
  - `limit` 을 20 이하로 줄이면 빨라 보이지만 그건 실행계획이 우연히 바뀌는 것이라 믿을 수 없습니다
    (20 → 111ms, 60 → 21초).
  - 단지 상세 계열(`/complexes/{aptSeq}/...`)은 사용자가 고른 **한 단지**라 sgg_cd + apt_seq 로도
    충분히 빠릅니다 — 거기까지 바꿀 필요는 없습니다.
- **DB 연결 풀은 16입니다**(2026-10-22, 종전 5). 무거운 집계(전국 평균·경계)가 풀을 다 쓰면 핀 조회가
  5초 만에 타임아웃해 화면에 *"부동산 데이터를 조회하지 못했습니다: MyBatisSystemException"* 이 떴습니다
  (`estate-pool total=5 active=5` 로그로 확인). 5 로 되돌리지 말 것.
- 핀 쿼리는 **bbox 로 단지를 먼저 고르고, 그 단지들의 시군구에서만 거래를 묶습니다.**
  단지별로 하나씩 조회하면 300개 × 100ms 가 됩니다 — `apt_seq` 에는 인덱스가 없습니다.
- **핀 GeoJSON 은 DB 가 조립합니다**(2026-10-28, `json_agg` + `json_build_object`, `resultType="string"`).
  행 목록을 `Map` 으로 옮기는 것만으로 **단지 381개에 400ms** 가 들었습니다 — DB 쿼리 자체는 62ms
  였고, 지도 이동이 느리다는 지적의 가장 큰 원인이었습니다. Java 는 받은 배열에 머리말
  (`coordinates_ready`·`complex_total`·`complex_geocoded`)만 붙입니다. **키 이름과 구조는 종전
  Java 조립과 똑같아야** 합니다(화면이 그대로 씁니다) — 키를 더하거나 뺄 때 양쪽을 함께 볼 것.
- **핀 응답도 캐시합니다**(2026-10-28, `pinJsonCache`, 10분·300개). 화면이 bbox 를 1km 격자로 끊어
  묻기 때문에 같은 영역을 되돌아오면 같은 요청이 됩니다 — 실측 **광교 385ms → 113ms**.
  **좌표가 준비 중인 응답은 담지 않습니다**(진행률이 굳어 "준비 중"이 멈춰 보입니다).
- **좌표 진행률도 캐시합니다**(1분, `complexProgressCached`). 화면 안내 문구에만 쓰는 숫자인데
  **핀을 부를 때마다 단지 4.4만 행을 다시 셌습니다**(실측 55ms).
- **핀 응답에 대표 평형이 함께 옵니다**(2026-10-21, `main_pyeong`·`main_avg_amount`·`main_deal_count`).
  그 기간에 **거래가 가장 많은 평형**과 그 평형의 평균가로, 지도 마커에 "26평 25.4억"처럼 적습니다 —
  단지 전체 평균만 주면 평형이 섞인 숫자라 "이 단지 이 평수가 얼마"로 읽히지 않습니다.
  거래를 **단지 × 평형으로 한 번만 묶고**(`byPyeong`) 거기서 단지 합계(건수 가중평균)와 대표 평형을
  함께 만듭니다 — 표를 두 번 읽지 않기 위한 것이므로 따로 떼지 말 것(실측 333ms, 종전과 같음).
  평형은 이 서비스의 다른 곳과 같이 **전용면적 기준**(`round(전용/3.3058)`)입니다.

## 현재 범위와 남은 작업

- 운영 배포 전입니다 — Helm 차트(`sj-lab-k8s-manifests`)와 Jenkins 잡, 운영 DB 접속 Secret 이 필요합니다.
- **지도 조회는 들어갔지만 아파트 핀은 아직 비어 있습니다** — `estate.apt_complex` 표가 만들어지고
  좌표가 채워지면(scheduler 의 지오코딩 배치 + 행안부 키) 코드 변경 없이 핀이 떠오릅니다.
  절차는 `sj-lab/docs/estate-geocode-workflow.md`.
- 오피스텔·빌라는 수집·표부터 필요합니다.
