# 1-9 기본 관측 구성

상태: 정책 확정·구현·독립 검증·위키 갱신 완료. 사용자가 "1-9 부터 1-11까지도 마찬가지로 정책 협의들 너가 생각하는 추천안으로 확정하고 개발 및 테스트"하라고 지시해 주 에이전트 추천안으로 확정하고 구현을 시작했다.

[1단계 정책](01-mysql-order.md), [전체 기획의 관측 항목](../project-plan.md)을 따른다. 이 문서는 1-9의 새 합의를 우선한다.

## 범위

- 판매 앱의 Micrometer Prometheus 지표 노출, 업무 지표, 요청 ID 로그를 만든다.
- 로컬 Prometheus·Grafana를 Compose의 별도 프로필로 띄우고 대시보드를 코드로 준비한다.
- MySQL 잠금·쿼리를 관찰하는 스냅샷 스크립트를 만든다. 1-11 부하 실험 전후에 쓴다.
- Mock PG 지표, Redis exporter, 분산 추적, 로그 수집 서버, 알림 규칙은 범위 밖이다.

## 확정한 정책과 이유

| 항목 | 확정 내용 | 이유 |
|---|---|---|
| 지표 노출 위치 | Actuator 관리 포트를 공개 API와 분리. 기본 `127.0.0.1:8090`, 환경변수로 주소·포트 변경 | 공개 API 보안 체인과 섞지 않는다. 지표는 로컬 수집기만 읽는다. 1-10에서 인스턴스마다 다른 포트를 준다 |
| 관리 포트 노출 엔드포인트 | `health`, `prometheus`만 | 필요한 것만 연다 |
| 공개 포트 | `/actuator/health`는 유지, `/actuator/prometheus`는 열지 않음 | 기존 health 사용처를 깨지 않는다 |
| HTTP 지표 | Spring 기본 `http.server.requests`. 백분위 히스토그램 켬 | p50·p95·p99를 Prometheus에서 계산한다 |
| PG 호출 지표 | PG HTTP 클라이언트가 Spring 기본 `http.client.requests`를 남기게 함 | PG 지연과 오류를 판매 앱 쪽에서 본다 |
| JVM·연결 풀 | Spring 기본 JVM·Hikari 지표 | 추가 코드 없이 얻는다 |
| 주문 업무 지표 | 주문 생성 결과 카운터. 라벨은 결과 하나(생성, 재생, 품절, 한도 초과, 기간 외, 중단, 없는 이벤트, 키 충돌) | 업무 거절을 시스템 오류와 따로 센다 |
| 결제 업무 지표 | 결제 요청 결과 카운터. 라벨은 결과 하나(성공, 확인 중, 거절, 만료, 없는 주문) | 사용자 결제 경로의 결과 분포 |
| 복구 지표 | 미완료 주문 수, 운영 확인 대상 주문 수 게이지. DB에서 주기적으로 셈 | PG 장애 중 점유가 쌓이는지 본다 |
| 라벨 제한 | 회원 ID·주문 ID·요청 키·요청 ID를 라벨로 쓰지 않음 | 시계열 수 폭발을 막는다. 전체 기획의 원칙 |
| 업무 거절과 시스템 오류 구분 | 업무 거절은 업무 카운터와 4xx, 시스템 오류는 5xx로 구분해 대시보드에 따로 그림 | 완료 기준 |
| 요청 ID | `X-Request-Id`가 영문·숫자·`-`·`_` 1~64자면 그대로 쓰고, 아니면 새 UUID. 응답 헤더로 돌려주고 로그 MDC `requestId`에 넣음 | 클라이언트와 서버 로그를 이어 본다. 잘못된 값으로 로그를 오염시키지 않는다 |
| 로그 형식 | 기본 콘솔 패턴에 `requestId` 포함. JSON 로그는 Spring Boot 기본 구조화 로그 설정으로 켤 수 있음 | 로컬은 읽기 쉽게, 수집이 필요하면 설정만 바꾼다 |
| Prometheus·Grafana 실행 | Compose `monitoring` 프로필. `127.0.0.1:9090`, `127.0.0.1:3000` | 기본 `docker compose up`은 MySQL·Redis만 띄운다 |
| Grafana 접근 | 로컬 전용. 익명 Viewer 허용, 데이터 소스·대시보드는 파일로 프로비저닝 | 비밀번호를 커밋하지 않는다 |
| MySQL 관측 | `performance_schema`와 InnoDB 지표를 읽는 스냅샷 스크립트. 실행 전후 차이를 파일로 남김 | exporter용 계정을 따로 만들지 않는다. 1-11에서 실험 전후 차이를 기록한다 |

## 검증 기준

- 관리 포트 `/actuator/prometheus`가 HTTP·JVM·Hikari·업무 지표를 내고, 공개 포트에서는 보이지 않는다.
- 주문 품절과 성공, 결제 성공과 거절을 만든 뒤 업무 카운터가 결과별로 오른다.
- 지표 라벨에 회원·주문·요청 ID가 없다.
- 유효한 `X-Request-Id`는 그대로, 잘못되거나 없는 값은 새 UUID로 응답 헤더와 로그에 남는다.
- 격리된 MySQL·Redis로 앱을 실제로 띄우고 Prometheus가 판매 앱을 수집한다. Grafana API로 대시보드가 프로비저닝되고, 실제 요청이 Prometheus 질의 결과에 반영된다.
- MySQL 스냅샷 스크립트가 격리된 MySQL에서 잠금·쿼리 요약을 출력한다.

## 구현 중 정한 세부 사항

- PG 클라이언트는 `RestClient`가 아니라 `java.net.http.HttpClient`다. 클라이언트를 바꾸지 않고 `HttpPaymentGateway`가 `http.client.requests` 타이머를 직접 기록한다. 시간 초과와 연결 실패는 `status=IO_ERROR`다.
- 관리 포트를 분리하면 공개 포트에서 Actuator가 사라진다. 기존 사용처를 위해 `PublicHealthController`가 공개 `/actuator/health`에 상태만 돌려준다.
- 관리 포트도 앱의 보안 필터 체인을 쓰므로 `EndpointRequest.to("health", "prometheus")`를 허용한다. 이 규칙은 관리 포트에만 맞는다.
- 지표 이름은 `order.creation.results`, `order.payment.results`, `order.unfinished`, `order.requires.review`다. 결과 라벨은 `OrderResult.Kind` 소문자다. 모든 결과를 0으로 미리 등록한다.
- Grafana 기본 관리자 계정은 그대로 두고 README에 첫 로그인 때 바꾸라고 적었다.
- 1-10 이후 두 번째 인스턴스의 관리 포트는 8092이고 Prometheus가 8090·8092를 모두 수집한다.

## 검증 결과

Sonnet high 워커가 구현했고, 주 에이전트가 diff를 리뷰하고 빌드를 다시 실행했다.

- 처음에는 작업 환경의 Mac 라우팅 표에서 Cloudflare 대역(104.18/16, 104.19/16, 172.64/13)이 닿지 않는 게이트웨이로 향해 Maven Central에 접속하지 못했다. 사용자가 경로를 지운 뒤 관리 버전 의존성(Micrometer 1.15.12, prometheus-metrics 1.3.10)을 받았다.
- 관리 버전으로 `./gradlew clean build` 성공. 판매 앱 단위 94개, 통합 132개(관측 통합 9개 포함), Mock PG 11개·15개 모두 실패·오류·건너뜀 0개.
- 관측 통합 테스트는 실제 HTTP로 관리 포트 지표, 공개 포트 비노출, 결과별 업무 카운터 증가, 게이지와 DB 값 일치, ID 라벨 부재, 401·403을 포함한 요청 ID를 검사한다.
- 워커가 임시 대체 버전 JAR로 격리 환경에서 실제 실행을 확인했다. Prometheus 대상이 up이었고, Grafana 대시보드가 프로비저닝됐으며, 대시보드 질의 29개가 모두 성공했다. 업무 카운터는 보낸 요청 수와 일치했다.
- `python3 scripts/check-wiki-diagrams.py`와 `git diff --check` 통과.

## 체크리스트

- [x] 미정 사항 확정과 범위·완료 기준 기록
- [x] 사용자로부터 구현 시작 지시 받음
- [x] 워커 구현과 검증 결과 수령
- [x] 주 에이전트 독립 검증과 코드리뷰
- [x] 위키 갱신과 검증
