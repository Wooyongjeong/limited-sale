# 주문·결제·복구 정합성 실험

이 도구는 전용 MySQL 8.4.7 두 개, Redis 7.4(`compose.yaml`과 같은 `maxmemory 256mb`·`noeviction`), Mock PG JVM과 판매 JVM 두 개를 만들고 소유한 자원만 정리한다. 기존 Compose 볼륨이나 서버를 사용하지 않는다. Python 3.9 이상과 Java 25, Docker가 필요하며 Python 외부 패키지는 사용하지 않는다. 먼저 현재 소스의 실행 JAR을 만든다.

```sh
./gradlew bootJar :mock-pg:bootJar
```

전체 실험은 오래 걸리므로 명시적으로 실행한다. 출력 디렉터리는 존재하지 않아야 한다. 1,000명 요청, 9회 실제 SIGKILL과 실제 11분 PG 중단이 포함된다. macOS에서는 `caffeinate`로 잠자기를 막는다. 잠자기 중에는 단조시계가 멈춰 측정이 틀어지며, 각 실행기는 벽시계와의 차이가 5초를 넘으면 실패한다.

```sh
caffeinate -ims python3 -B tools/order-regression/run_all.py --output /tmp/order-regression-RUN
```

개별 실행도 가능하다. `JAVA_HOME`은 Java 25를 가리켜야 한다.

```sh
python3 -B tools/order-regression/run_oversubscription.py --users 100 --output /tmp/oversub-RUN
python3 -B tools/order-regression/run_scenarios.py --users 1000 --output /tmp/scenarios-RUN
python3 -B tools/order-regression/run_forcekill.py --output /tmp/forcekill-RUN
python3 -B tools/order-regression/run_outage.py --output /tmp/outage-RUN
```

로드 밸런서를 거치는 1-10 실험은 Caddy 컨테이너(`caddy:2.11.2-alpine`, 저장소의 `infra/caddy/Caddyfile` 사용)를 소유 자원으로 추가하고 판매 JVM 1개 또는 2개를 그 뒤에 둔다. 클라이언트는 인스턴스를 모르며 사용자마다 로드 밸런서로 한 번 로그인해 세션을 유지한다. 서로 다른 사용자 1,000명이 재고 300·한도 1에 동시 주문하고, 10명마다 같은 사용자의 동시 중복 묶음(같은 키 재생 2건, 다른 키 2건)을 더해 총 1,400건을 한 장벽에서 보낸다. 발사 순서는 시드로 섞어 기록한다. 생성된 주문은 모두 로드 밸런서로 결제한다. 통과 기준은 확정 300·잔여 0·검증기·HTTP 증거·`tools/reconciliation/reconcile.sql` 위반 0, 로드 밸런서 접근 로그의 주문 생성 건수가 시도 수와 같고 모든 인스턴스가 처리했으며 401/403이 없는 것이다. 같은 키 재생과 어느 키가 이기는지는 경합에 달려 있어 매니페스트의 결과 건수는 `[최소, 최대]` 범위, 거절 사유는 허용 목록이다(`verify.py`가 둘 다 지원한다). 접근 로그는 `lb-access.log`, 인스턴스별 분배는 `summary.json`의 `order_create_split`에 기록한다.

```sh
python3 -B tools/order-regression/run_balanced.py --instances 1 --output /tmp/balanced-1-RUN
python3 -B tools/order-regression/run_balanced.py --instances 2 --output /tmp/balanced-2-RUN
```

일반 시나리오는 사전 인증된 서로 다른 사용자와 시작 장벽을 사용한다. 재고 미만과 강제 종료는 지정된 더 작은 요청 집합을 사용한다. 거절 후 재사용은 먼저 재고를 점유하고 일부를 거절한 뒤, 기존 요청의 재생과 다른 사용자의 새 키를 함께 장벽에서 보낸다. 혼합 장애는 두 사용자 주문을 먼저 확보하고 나머지 사용자 요청과 그 두 재생을 장벽에서 보낸다. 강제 종료는 100명/재고 30 프로필에서 단일 대상 주문의 정확한 경계를 관찰한다.

출력에는 실행별 고정 manifest, 실제 전송 결과와 예외, DB 스냅샷, 수렴 관측 시각, 소유 자원 목록, JVM 로그와 요약을 보관한다. 쿠키·CSRF·비밀번호·API 키는 저장하지 않는다. 대량 원본은 Git에 추가하지 않는다. 클라이언트와 서버는 로컬 CPU·메모리를 공유하며 설정된 동시 출발 수는 서버 처리량이나 성능 보장이 아니다. 실패·누락·기한 초과는 0이 아닌 종료 코드로 보고한다.

빠른 검사와 개별 스냅샷 검사:

```sh
python3 -B -m unittest discover -s tools/order-regression -v
python3 -B tools/order-regression/verify.py /tmp/oversub-RUN/snapshot.json
python3 -B tools/order-regression/audit_outage.py /tmp/outage-RUN
```

검증기는 주어진 manifest와 수집 결과의 일관성을 검사한다. 실제 프로세스 종료, 사전 고정 명세, 단조시계 측정, 수집 시점과 환경의 신뢰성은 별도로 원본 증거와 대조해야 한다. 단일 스냅샷 검증기의 통과만으로 전체 실험을 완료한 것으로 판단하지 않는다.

## 1-11 기준 부하 실험

`run_baseline.py`는 1-10의 격리 환경(판매 JVM 2개, Caddy, Mock PG 전체 성공)을 실행마다 새로 만들고, 사용자 가입·로그인을 부하 전에 끝낸 뒤 k6 시나리오 `tests/load/baseline-order.js`로 주문·결제만 보낸다. 반복 하나가 사용자 한 명이며 `shared-iterations`로 동시 VU를 고정한다. 필요한 것은 위 실험과 같고 k6가 PATH에 있어야 한다.

```sh
./gradlew bootJar :mock-pg:bootJar
caffeinate -ims python3 -B tools/order-regression/run_baseline.py --scenario baseline --runs 3 --output benchmarks/load-baseline
caffeinate -ims python3 -B tools/order-regression/run_baseline.py --scenario under-demand --runs 1 --output benchmarks/load-baseline
```

- 시나리오: `baseline`(사용자 10,000·재고 1,000·VU 500), `under-demand`(사용자 800·재고 1,000·VU 500). `--users`, `--stock`, `--vus`는 스모크 확인용 덮어쓰기이며 결과에 `UNOFFICIAL`이 표시된다. `--label`은 모든 결과에 남는 설명 문구다. 공식 기준값이 아닌 JAR(예: 의존성 버전을 바꾼 JAR)로 돌릴 때 사용한다.
- 세션 파일: 쿠키·CSRF·요청 키를 담은 0600 임시 파일을 만들어 k6에만 넘기고, k6가 끝나면 실패해도 지운다. 출력 디렉터리에 복사하지 않는다.
- 통과 기준(`checks`): k6 종료 코드 0, 반복 완료 수와 누락 0, 5xx·연결 오류 0, 기대 외 상태 코드(200·201·202·409 밖) 0, 모든 주문의 종료 상태 수렴, 확정 수·잔여 수량(`baseline` 확정 1,000·잔여 0, `under-demand` 확정 800), `verify.py`·`reconcile.sql` 위반 0, k6가 센 생성·품절 건수와 DB 일치, 생성 주문마다 결제 요청 1건, 두 인스턴스의 주문 요청 수 합이 사용자 수와 같고 모두 처리, MySQL 관측 전체 수집. 품절 409는 오류가 아니라 업무 거절이다.
- 검증기 입력: k6는 요청별 기록을 남기지 않으므로 `verify.py`의 시도 결과는 서버가 모든 판정마다 저장하는 `order_request_results`에서 만든다. 클라이언트 관점은 k6 상태·업무 코드 건수를 DB와 대조해 교차 검증한다.
- 지표 수집: 부하 전후 두 관리 포트(`/actuator/prometheus`) 스냅샷의 차이(HTTP 서버, Hikari 획득, GC, 업무 카운터, JVM CPU 시간)와, 부하·수렴 동안 1초 간격으로 읽은 게이지의 최대·평균(Hikari 사용·대기, 프로세스·시스템 CPU, 힙, 스레드). Hikari 사용·대기 게이지는 HikariCP가 1초 단위로 캐시하므로 부하 직후 단발 스냅샷 값은 신뢰하지 않고 최대값만 쓴다. `scripts/mysql-observe.sh` 전후 차이(행 잠금 대기 수·시간, 교착·시간 초과, 소요 시간 상위 다이제스트)와 k6 프로세스의 CPU 시간을 함께 기록한다. 부하 생성기·JVM·MySQL이 한 컴퓨터를 나눠 쓰므로 CPU 값은 병목 해석에 함께 본다.
- 환경: CPU·메모리·OS·Java·MySQL·Redis·Docker·k6 버전, Docker VM 자원, git 커밋과 dirty 여부, JAR SHA-256, JAR에 들어간 Micrometer·Prometheus·HikariCP 라이브러리 버전, 회원별 주문 요청 제한 설정(`order_rate_limit_enabled`).
- 요청 제한: 이 도구가 만드는 판매 JVM은 모두 `LIMITED_SALE_ORDER_RATE_LIMIT_ENABLED=false`로 시작한다. 한 사용자의 연속 요청이 429로 거절되면 "제한 없음" 기준과 비교할 수 없기 때문이다. 셸에 같은 변수를 내보내도 JVM에는 전달되지 않는다. `Isolated(order_rate_limit=True)`로만 켠다.

출력 디렉터리는 없으면 만든다. 같은 시나리오의 `<시나리오>-aggregate.json`이 이미 있으면 거부한다.

| 파일 | 내용 | 커밋 |
|---|---|---|
| `<시나리오>-run-N.json` | 실행별 요약: 환경, 처리량, 엔드포인트별 p50/p95/p99, 상태·업무 코드 건수, 관리 포트·MySQL 전후 차이, 통과 기준 | 예 |
| `<시나리오>-aggregate.json` | 실행 수, 통과 여부, 환경, 지표별 중앙값·최소·최대·실행별 값 | 예 |
| `raw/<시나리오>-run-N/` | JVM·k6 로그, 관리 포트 원본, MySQL 스냅샷, 검증기 입력, 정리 증거 | 아니요(`.gitignore`) |

실행 시간은 환경 시작 약 25초, 사용자 준비(가입·Argon2 로그인), 부하, 수렴·검증으로 나뉘며 `durations_seconds`에 기록된다. 순수 로직(지표 파싱·차이·집계·통과 판정) 테스트는 `python3 -B -m unittest discover -s tools/order-regression -v`에 포함된다.

## 2-5 Redis 장애 실험

`run_redis_outage.py`는 1-10의 격리 환경(판매 JVM 2개, Caddy, Mock PG 전체 성공, 전용 MySQL·Redis)에서 **주문 요청 제한을 켠 채**(허용 횟수 5를 명시 전달한다. 측정 당시 앱 기본값이었고 이후 1로 바뀌었다) 부하를 보내다가 Redis 컨테이너를 `docker stop`으로 멈추고 `--outage-seconds` 뒤 `docker start`로 되살린다. 2-1·2-2·2-3·2-4의 Redis 장애 정책이 실제 부하에서 지켜지는지와 명령·연결 타임아웃(`--redis-timeout`) 값에 따른 차이를 잰다. 필요한 것은 위 실험과 같다(Java 25, Docker, Python 3.9+). `k6`는 쓰지 않는다.

```sh
./gradlew bootJar :mock-pg:bootJar
caffeinate -ims python3 -B tools/order-regression/run_redis_outage.py --redis-timeout 1s --output benchmarks/redis-outage/timeout-1s
caffeinate -ims python3 -B tools/order-regression/run_redis_outage.py --redis-timeout 2s --output benchmarks/redis-outage/timeout-2s
# 스모크: 결과에 UNOFFICIAL이 표시된다
python3 -B tools/order-regression/run_redis_outage.py --redis-timeout 1s --users 200 --stock 60 --outage-seconds 5 --output /tmp/redis-outage-smoke
```

- 인자: `--redis-timeout`(필수, `1s`·`500ms`처럼 단위 포함. 판매 JVM의 `spring.data.redis.timeout`·`connect-timeout`에 환경변수로 전달), `--users`(1,000)·`--stock`(300)·한도 1, `--concurrency`(동시에 진행하는 사용자, 100), `--outage-seconds`(15), `--outage-after`(사용자의 이 비율이 최종 결과를 받으면 Redis를 멈춤, 0.25), `--deadline-seconds`(Redis를 다시 켠 뒤 모든 사용자가 끝나야 하는 기한, 120), `--probe-interval`(공개 조회 프로브 간격, 0.5초), `--runs`(반복 횟수), `--seed`(재시도 backoff 지터). 그 밖의 앱 설정은 기본값이다. 예외 두 가지: Redis 호스트 포트를 컨테이너 재시작 뒤에도 같게 고정하고(Docker가 임의로 고른 포트는 재시작하면 바뀔 수 있다), Tomcat 스레드 지표(`tomcat_threads_busy_threads`)를 읽으려고 `SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true`를 판매 JVM에 준다. 둘 다 결과의 `environment`에 남는다.
- 순서: 사용자 가입·로그인(부하 전) → 이벤트(재고·한도 1) 생성 → 사용자마다 수량 1개 주문, 생성되면 결제 → 장애 주입 → 모든 사용자 종료 → 주문 종료 상태 수렴 → 캐시 확인 → 두 인스턴스 health UP 확인 → 스냅샷, `verify.py`, `reconcile.sql`.
- 클라이언트 재시도(상태기계는 `redis_outage.decide`): 모든 재시도는 같은 `Idempotency-Key`로 보낸다. 201·200(`ORDER_FOUND`)은 결제로 넘어가고, 404·409(품절 등 업무 거절)는 그 사용자의 최종 결과다. 503은 짧은 backoff(0.25초에서 두 배씩 최대 1초, ±20% 지터) 뒤 재시도, 429는 `Retry-After`(없으면 1초)만큼 기다린 뒤 재시도, 401은 세션과 쿠키를 버리고 CSRF 조회·로그인·CSRF 재조회 뒤 재시도한다. 세션이 사라지면 POST는 CSRF 검사에서 먼저 403 `CSRF_INVALID`가 되므로 이것도 401과 같게 처리한다. 로그인 중 503·429도 backoff·`Retry-After`로 처음부터 다시 한다. 한 요청에서 재로그인이 3번을 넘으면 실패, 그 밖의 5xx·연결 오류는 같은 키로 재시도하되 통과 기준에서 실패로 센다. 결제 202(`PAYMENT_PENDING`)는 1초 뒤 다시 보낸다.
- 기록은 상태·업무 코드·지연·시각(단조시계 초, 부하 시작이 0)·재시도 대기·인스턴스뿐이다. 쿠키·CSRF 토큰·비밀번호는 저장하지 않는다. 인스턴스는 요청마다 붙인 `X-Attempt-Seq` 헤더를 Caddy 접근 로그와 맞춰 알아내며, 접근 로그에서는 그 헤더와 upstream·상태만 남긴다(전체 로그에는 CSRF 헤더가 있다).
- 구간: 응답이 끝난 시각 기준으로 `before`(`docker stop` 명령 이전), `during`(stop 명령부터 `docker start`가 돌아올 때까지), `after`다. 응답이 Redis 복구 뒤에야 끝난 요청은 `after`에 센다.
- 공개 조회 프로브: 쿠키 없이 `GET /api/v1/sales/{id}`를 `--probe-interval`마다 앞 요청의 완료와 상관없이 보낸다.
- 통과 기준(`checks`): 확정 300·잔여 0, 주문 모두 종료 상태, `verify.py`·`reconcile.sql` 위반 0, 요청 키별 중복 주문 0, 503·429만 받은 요청 키는 `order_request_results`·주문이 없고 응답을 받은 키는 서버 기록과 일치, 사용자 최종 결과가 확정 300·품절 700이며 기한 안에 끝남, 503 `AUTHENTICATION_UNAVAILABLE` 외 5xx·연결 오류(응답 본문이 중간에 끊긴 경우 포함) 0, 클라이언트가 503을 본 적 있음, 장애 중 공개 조회 프로브가 모두 200이고 1건 이상, `sale_cache_requests_total{result=error}` 증가, 복구 뒤 캐시 키가 다시 채워져 MySQL 행과 모든 필드가 같음(복구 전에 쓰였거나 주문으로 낡은 값은 TTL이 끝나길 기다린 뒤 다시 채워진 값을 비교하며 기다린 시간을 남긴다), 클라이언트가 본 429 수와 서버의 `order_rate_limit_total{result=rejected}` 증가 일치, 두 인스턴스 management health UP, 시계 이상 없음. 기준을 완화하는 옵션은 없다.
- 결과: `<출력>/run-N.json`(커밋)에 환경(커밋·dirty·JAR 해시·버전·타임아웃·요청 제한), 장애 시작·종료(벽시계·단조시계), 구간별 상태·업무 코드 건수, 503 지연 분포(p50/p95/p99/max), 공개 조회 프로브, Tomcat busy·Hikari 대기 최대(1초 간격 게이지), 구간별 처리량, 복구 시간(장애 종료 뒤 첫 성공 요청·health UP), 지표 전후 차이(`order_rate_limit`·`sale_cache`·`lettuce`), 재로그인·재시도 수, 통과 기준과 실패한 항목을 담는다. `dirty`는 이 도구가 쓰는 `benchmarks/redis-outage/` 결과 파일을 뺀 변경 여부다. 원본(요청 기록 `attempts.json`, 상태 로그, 스냅샷, 지표 원본, JVM 로그)은 `<출력>/raw/run-N/`에 두고 `.gitignore`로 제외한다. 실패·누락·기한 초과는 0이 아닌 종료 코드다.
- 동시성: 기본 100이다. 200으로 1초 타임아웃 실험을 3번 돌렸을 때 세 번 모두 Caddy가 판매 인스턴스 한쪽에 연결하지 못해(`dial tcp ...: i/o timeout`, 3초) 결제·주문 요청 5~14건이 502가 되어 통과 기준(503 외 5xx 0)에서 실패했다. 100으로 낮추자 통과했다. 판매 앱의 응답이 아니라 로드 밸런서의 upstream 연결 실패이며, 원인은 확정하지 못했다(호스트 소켓 상태 표본에는 accept 대기열 포화나 임시 포트 고갈의 흔적이 없었다). 실패한 시도의 요약은 `benchmarks/redis-outage/concurrency-200-attempts/`에 있다.
- 한계: 클라이언트와 서버가 한 컴퓨터를 나눠 쓰고 게이지는 1초 간격이라 더 짧은 급등은 놓친다. `before` 구간은 부하가 빨리 진행돼 짧다. 이 실험은 Redis 데이터 유실이나 세션 유실 재시작을 하지 않으므로 401·재로그인 경로는 단위 테스트로만 검증한다(`docker stop`의 RDB 저장으로 세션이 남을 수 있다).

## 2-6 비교 실험

2-7 품절 사전 판정 비교(`c-off`·`c-on`)도 같은 실행기를 쓴다. 아래 표 끝의 조건과 "2-7 조건" 항목을 본다.

`run_comparison.py`는 1-11 기준 실험의 격리 환경(판매 JVM 2개, Caddy, Mock PG 전체 성공, 전용 MySQL·Redis), 사용자 준비, 지표 수집, `verify.py`·`reconcile.sql` 판정을 그대로 쓰고 조건 4개를 실행한다. 순수 로직은 `comparison.py`, k6 시나리오는 `tests/load/comparison-order.js`다. 필요한 것은 1-11 실험과 같다(Java 25, Docker, k6, Python 3.9+).

| 조건 | 부하 | 조회 캐시 | 주문 요청 제한 |
|---|---|---|---|
| `a-off` | 사용자 10,000·재고 1,000·VU 500, 반복마다 판매 상세 조회 5번 뒤 주문·결제 | 끔 | 끔 |
| `a-on` | 같음 | 켬 | 끔 |
| `b-off` | 1-11 기준 흐름(조회 없음) + 남용 사용자 100명 | 켬(기본) | 끔 |
| `b-on` | 같음 | 켬(기본) | 켬(`max-requests=5`를 명시 전달) |
| `b2-off` | `b-off`와 같으나 남용 사용자 100명이 각각 동시 VU 5개(총 500) | 켬(기본) | 끔 |
| `b2-on` | `b2-off`와 같음 | 켬(기본) | 켬(`max-requests=5`를 명시 전달) |
| `b3-off` | `b2-off`와 같음 | 켬(기본) | 끔(`max-requests=1`을 같이 전달) |
| `b3-on` | `b2-off`와 같음 | 켬(기본) | 켬, `limited-sale.order.rate-limit.max-requests=1`(창 1초) |
| `c-off` | 1-11 기준 흐름(조회·남용 없음), 사용자 10,000·재고 1,000·VU 500 | 켬(기본) | 켬(앱 기본값, `max-requests=1`을 명시 전달), 품절 사전 판정 끔 |
| `c-on` | `c-off`와 같음 | 켬(기본) | 켬(같음), 품절 사전 판정 켬 |

```sh
./gradlew bootJar :mock-pg:bootJar
caffeinate -ims python3 -B tools/order-regression/run_comparison.py --rounds 3 --output benchmarks/redis-comparison
# 스모크: 결과에 UNOFFICIAL이 표시된다
python3 -B tools/order-regression/run_comparison.py --users 500 --stock 100 --vus 50 --abusers 10 --output /tmp/redis-comparison-smoke
```

- 인자: `--conditions`(쉼표 구분, 기본 `a-off,a-on,b-off,b-on`), `--rounds`(조건당 실행 횟수), `--output`(없으면 만들고 조건별 하위 디렉터리를 만든다), `--users`·`--stock`·`--vus`·`--detail-views`(실험 A만)·`--abusers`(실험 B만)·`--abuser-vus-per-member`(실험 B만, 남용 사용자당 동시 VU 수)는 스모크용 덮어쓰기이며 `--label`은 모든 결과에 남는 설명 문구다. `--order-file`(기본 `execution-order.json`)은 실행 순서를 쓰는 파일이다. 같은 출력 디렉터리에 조건의 `aggregate.json`이나 순서 파일이 이미 있으면 거부한다. `b2-off`·`b2-on`·`b3-off`·`b3-on`은 기본 조건에 들어 있지 않아 `--conditions b2-off,b2-on --order-file execution-order-b2.json`처럼 따로 실행한다.
- 실행 순서: 한 라운드마다 `--conditions` 순서대로 조건 하나씩 실행하고 라운드를 반복한다(`a-off, a-on, b-off, b-on` × 3). 시간대 편향(컴퓨터 상태가 서서히 변하는 영향)을 조건에 나눠 갖게 하려는 것이다. 실제 순서와 각 실행의 상태는 `execution-order.json`에 남는다.
- `max-requests`: 모든 조건이 판매 JVM에 `LIMITED_SALE_ORDER_RATE_LIMIT_MAX_REQUESTS`를 명시 전달한다(`a`·`b`·`b2`는 5, `b3`는 1; 제한을 끈 조건도 같은 값을 전달해 환경 기록을 맞춘다). 앱 기본값은 2-6 b3 참고 결과로 5에서 1로 바뀌었고, 명시 전달 덕분에 이미 측정한 `b-on`·`b2-on`을 다시 실행해도 같은 조건이다. 결과의 `environment.settings`에 `order_rate_limit_max_requests`·`order_rate_limit_window`·`order_rate_limit_max_requests_passed_explicitly`와 현재 기본값과 다른 값을 보여 주는 `overridden_application_defaults`가 남는다. 정상 사용자는 주문 1회·결제 1회를 따로 세므로 429를 받으면(`normal_users_never_rate_limited`) 실패다. `b3-on`은 429가 한 번도 없으면(`rate_limit_override_reached_the_jvms`) 설정이 JVM에 닿지 않은 것으로 보고 실패시킨다(`b-on`·`b2-on`은 한도에 닿지 않는 것도 결과이므로 이 검사가 없다).
- 스위치: 캐시는 판매 JVM의 `LIMITED_SALE_SALE_CACHE_ENABLED`, 요청 제한은 `Isolated(order_rate_limit=...)`로 지정하고 둘 다 `resources.json`·결과의 `environment.settings`에 남는다. 캐시가 실제로 꺼졌는지는 `sale_cache_requests_total` 계열이 지표에 없는지로 확인한다(꺼진 캐시는 지표를 만들지 않는다). 나머지 설정(캐시 TTL 60초, 제한 창 1초)은 애플리케이션 기본값이고 허용 횟수는 위 `max-requests`다.
- 2-7 조건: `c-off`·`c-on`은 `--conditions c-off,c-on --order-file execution-order-c.json`으로 따로 실행한다. 품절 사전 판정 스위치는 판매 JVM의 `LIMITED_SALE_SALE_SOLD_OUT_GATE_ENABLED`이고 **모든 조건이 매번 명시 전달**한다(`c-on`만 `true`, 2-7 전에 측정한 조건은 `false`라서 다시 실행해도 같은 조건이다). 결과의 `environment.settings`에 `sold_out_gate_enabled`·`sold_out_gate_passed_explicitly`와 기본 TTL(`sold_out_gate_ttl`)이 남는다. 스위치가 실제로 닿았는지는 `sale_sold_out_gate_requests_total` 계열이 지표에 있는지로 확인한다(꺼진 사전 판정은 지표를 만들지 않는다). 추가 통과 기준: 켠 조건은 429가 아닌 모든 생성 요청이 표시 조회 1회(`every_create_was_a_gate_lookup`), 조회·쓰기 오류 0, `c-on`은 표시 적중 1회 이상(`gate_turned_requests_away`), 끈 조건은 표시 지표 증가 0. 기록에는 표시 적중·미적중·오류(`gate`), 쓰기·지우기 성공·오류(`gate_updates`), 정상 사용자의 `order_create` 지연을 응답별로 나눈 `latency_by_code`(`ORDER_CREATED`·`SALE_SOLD_OUT`의 건수·p50·p95·p99)가 더해진다. 행 잠금 대기·시간과 Hikari 대기는 기존 `mysql`·`management` 항목에 있다. 빠른 거절은 `ORDER_CREATED` 지연과 섞이지 않게 `latency_by_code`로 읽고, 처리량은 확정 수와 부하 시간을 함께 본다.
- 실험 A의 조회: 반복마다 `GET /api/v1/sales/{id}`를 `DETAIL_VIEWS`(5)번 연달아 보낸 뒤 주문·결제한다. 조회에는 로그인한 사용자의 세션 쿠키를 붙인다. 공개 엔드포인트라 쿠키가 필요 없지만 브라우저는 같은 출처 요청에 쿠키를 항상 보내고, 쿠키가 있으면 서버가 세션 확인 때문에 Redis를 부르므로(`PublicSessionFallbackFilter`) 캐시를 끈 조건에서도 이 Redis 호출이 남는다. 두 조건에서 같은 비용을 지불하므로 차이는 판매 이벤트 캐시만 반영한다.
- 실험 B의 남용 사용자: 정상 시나리오와 별도 k6 scenario(`abuse`)로 같은 k6 실행에 들어가며 사용자당 VU 수는 `ABUSER_VUS`(`b`는 1, `b2`는 5)다. 처음 설계(`b`)는 남용 요청이 행 잠금 대기열에 서서 요청당 약 1.6초가 걸려 회원당 약 0.5회/초밖에 못 보내 한도(당시 기본값 1초 5회)에 닿지 못했다. `b2`는 한 회원의 VU들이 같은 세션(쿠키·CSRF)을 공유해 병렬로 보내는 봇을 흉내 낸다. 정상 시나리오는 `shared-iterations` VU 500 그대로다. 각 남용 VU는 기다림 없이 매번 `crypto.randomUUID()` 새 요청 키로 `POST /api/v1/orders`를 반복한다. 429를 받아도 `Retry-After`를 무시하고 바로 다음 요청을 보낸다. 주문이 생성되면(201) 정상 사용자처럼 결제를 한 번 하고 계속 반복한다(그래야 생성된 주문이 종료 상태에 이른다). 정상 시나리오의 반복이 모두 끝나면 멈춘다. 남용 VU는 반복 하나를 그 시점까지 이어서 돌리므로 `exec.instance.iterationsCompleted`가 정상 반복 수에 도달하면 끝난다.
- 검증기 입력: 정상 사용자는 1-11처럼 사전에 고정한 요청 1건씩이다. 남용 요청은 키가 무작위이고 k6가 요청별 기록을 남기지 않으므로 서버가 저장한 `order_request_results`에서 남용 회원의 행을 시도로 만든다(429는 기록되지 않는 것이 정책이다). 허용 결과는 생성·거절, 거절 사유는 `SALE_SOLD_OUT`·`PURCHASE_LIMIT_EXCEEDED`, 최종 상태는 `CONFIRMED`다. 클라이언트가 센 남용 응답과 서버 기록의 수가 같은지 따로 대조한다.
- 통과 기준(`checks`, 모든 실행): k6 종료 코드 0, 정상 반복 완료·누락 0, 5xx·연결 오류 0, 기대 외 상태 코드(200·201·202·409·429 밖; 남용·조회 포함) 0, 주문 종료 상태 수렴, 확정 수=재고(1,000)·잔여 0, `verify.py`·`reconcile.sql` 위반 0, k6가 센 생성 수=DB 주문 수, 생성 주문마다 결제 요청 1건(정상·남용 따로), 두 인스턴스가 주문 요청을 모두 처리하고 합이 k6 요청 수와 같음, 서버 저장 요청 수=429를 뺀 생성 요청 수, MySQL 관측 전체 수집, 클라이언트가 센 429=`order_rate_limit_total{result=rejected}`, `bypassed` 0, 제한을 끈 조건은 제한 지표 증가 0, 켠 조건은 모든 생성 요청이 제한에서 판정됨, 실험 A는 조회가 전부 200이고 캐시를 켠 조건은 조회마다 캐시 조회 1회·오류 0, 캐시를 끈 조건은 캐시 지표 없음. 429·409는 업무 거절로 따로 센다. 기준을 완화하는 옵션은 없다.
- 기록: `<출력>/<조건>/run-N.json`(커밋)에 1-11과 같은 환경·처리량·엔드포인트별 지연(`order_create`·`order_payment`는 정상 사용자만, `sale_detail`, `abuse_order_create`·`abuse_order_payment`)·상태·업무 코드 건수·관리 포트·MySQL 전후 차이·통과 기준에 더해 캐시 적중·미적중·오류, 제한 허용·거절·통과(`operation`별), 남용 요청의 서버 기록(상태별·거절 사유별·확정 수), 파생 값(적중률, 실제 주문 처리량=확정 수÷부하 시간과 ÷정상 시나리오 종료 시각, 남용 거절률)이 들어간다. `<조건>/aggregate.json`(커밋)은 지표별 중앙값·최소·최대·실행별 값이다. 원본은 `<조건>/raw/run-N/`에 두고 `.gitignore`로 제외한다. `dirty`는 이 도구가 쓰는 결과 파일을 포함한 값이고 `dirty_excluding_results`는 그것을 뺀 값이다.
- 해석 주의: 거절이 늘어 짧아진 응답 시간을 주문 처리량 향상으로 읽지 않는다. 실제 주문 처리량은 DB의 확정 수로 센다. 부하 생성기와 서버가 한 컴퓨터를 나눠 쓴다.
