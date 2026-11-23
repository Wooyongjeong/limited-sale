# 한정 수량 제품 판매 서비스

회원가입·관리자 초기화와 Redis 세션 인증, 제품 등록·조회, 판매 이벤트 관리·조회, 주문 생성·수량 점유·본인 주문 상세를 구현했다. 주문 생성 시 READY 결제 기록도 저장한다. Mock PG 결제·조회·종료와 키별 시나리오를 제공한다. 판매 앱 사용자 결제·주문 확정·점유 해제와 DB 기반 자동 복구도 구현했다. [기획](docs/project-plan.md), [구조](docs/architecture.md), [스키마 설계](docs/stages/01-2-mysql-schema-and-boundaries.md)를 참고한다.

코드와 요청 흐름은 [개발자 위키](docs/wiki/README.md)에서 살펴본다.

## 세션 인증

`GET /api/v1/csrf` 응답의 `data.token`을 변경 요청의 `X-CSRF-TOKEN` 헤더에 넣고 세션 쿠키를 함께 보낸다. 회원가입과 `POST /api/v1/sessions` 로그인 전에도 토큰을 조회한다. 로그인 또는 `DELETE /api/v1/sessions/current` 성공 후 다음 변경 요청 전에 토큰을 다시 조회한다. `GET /api/v1/members/me`는 현재 회원의 이메일·닉네임·역할을 반환한다.

로그인 실패는 정규화 이메일과 직접 연결 IP별로 첫 실패부터 5분간 집계한다. 다섯 번째 실패부터 429이며 구간 만료 전 올바른 비밀번호도 차단된다. 제한 전에 성공하면 해당 조합의 기록을 지운다. 기본 유휴 세션 시간은 30분이며 `SPRING_SESSION_TIMEOUT`, 실패 구간은 `LIMITED_SALE_AUTH_FAILURE_WINDOW`, 실패 횟수는 양의 정수인 `LIMITED_SALE_AUTH_FAILURE_LIMIT`으로 조정할 수 있다. Redis 주소는 `REDIS_HOST`, `REDIS_PORT`로 설정한다. 주문의 미결제 만료 시간은 기본 5분이며 `LIMITED_SALE_ORDER_EXPIRATION`으로 양의 기간(예: `PT5M`)을 설정한다. 쿠키는 HttpOnly·SameSite=Lax이며 HTTPS 요청에는 Secure가 적용된다. Redis 장애 시 인증과 CSRF 처리는 503으로 실패하고 복구 뒤 재시도한다.

## 제품 등록과 조회

ADMIN 세션과 CSRF 토큰으로 `POST /api/v1/admin/products`에 `{"name":"제품명","description":"설명","basePrice":1000}`을 보낸다. 설명은 생략할 수 있다. `GET /api/v1/products?page=0&size=20`과 `GET /api/v1/products/{id}`는 공개 조회다. 목록은 ID 내림차순이며 size는 1~100이다. 제품명은 앞뒤 공백을 제거하고 설명은 원문을 보존한다. 같은 이름의 제품을 여러 번 등록할 수 있다. 상세 계약은 [1-5 문서](docs/stages/01-5-product-registration-and-query.md)를 참고한다.

## 판매 이벤트 관리와 조회

`POST /api/v1/admin/sales`는 productId·name·startsAt·endsAt·totalQuantity를 필수로 받는다. 시각은 오프셋이 있는 ISO 8601로 보내며 미래 시작만 가능하다. 가격과 구매 한도를 생략하면 제품 기준 가격과 1개를 사용한다. 생성·수정·삭제·중단·재개는 ADMIN 세션과 CSRF가 필요하다.

`GET /api/v1/sales?productId=1&status=SCHEDULED&page=0&size=20`과 `GET /api/v1/sales/{id}`는 공개 조회다. 수량은 총수량만 공개한다. PATCH는 조회한 version을 보내야 하며 충돌 시 409다. 시작 후에는 이름·설명만 수정할 수 있다. 중단·재개는 시작 전에도 가능하며 종료 후에는 거절한다. [전체 계약](docs/stages/01-6-sale-management-and-query.md)을 참고한다.

## Mock PG 시나리오

두 앱에 같은 `MOCK_PG_API_KEY`를 환경변수로 설정한다. 실제 비밀값은 저장소에 기록하지 않는다. PG의 `/api/v1/payments` 결제·조회·종료와 `/api/v1/simulations/{pgKey}` 설정 요청은 `X-Mock-PG-Key` 헤더가 필요하며 health는 공개한다. 판매 서버는 `MOCK_PG_BASE_URL`로 PG 주소를 받는다.

local/test 환경에서 ADMIN 세션과 CSRF 토큰을 사용해 `PUT /api/v1/admin/orders/{orderId}/payment-simulation`에 `{"scenario":"DELAY","delaySeconds":10}`을 보내면 주문에 연결된 PG 키의 설정만 바뀌며 결제는 시작되지 않는다. `SUCCESS`, `DECLINE`, `DELAY`, `RESPONSE_LOSS`를 지원하며 지연값의 범위는 1~600초다. `DELAY`의 기본값은 10초, `RESPONSE_LOSS`는 5초이고 후자는 최초 응답만 지연해 클라이언트 타임아웃을 재현한다. 상세 계약은 [1-7-2 문서](docs/stages/01-7-2-mock-pg-http-and-scenarios.md)를 따른다.

## 준비와 실행

Java 25, Docker Compose가 필요하다. Spring Boot 4.1.2, Gradle 9.9.2, MySQL 8.4.7, Redis 7.4를 사용한다. 시작 전 3306, 6379, 8080, 8081 포트 사용 여부를 확인하고 필요하면 `.env`의 포트를 바꾼다.

Apple Silicon macOS에서 Homebrew로 설치한 Java 25는 다음과 같이 현재 셸에서 선택하고 버전을 확인한다. 다른 환경에서는 설치한 Java 25의 경로로 `JAVA_HOME`을 지정한다. 이 설정은 전역 Java 기본값을 변경하지 않는다.

```sh
brew install openjdk@25                 # 설치되지 않은 경우
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version                          # 25 확인
```

```sh
cp .env.example .env
# .env의 세 비밀번호와 MOCK_PG_API_KEY를 서로 다른 로컬 전용 값으로 변경한다.
# DB 이름·계정·비밀번호는 영문자, 숫자, 밑줄만 사용한다.
set -a; source .env; set +a
docker compose up -d
docker compose ps                    # healthy 확인
./scripts/prepare-local-db.sh        # 두 스키마와 계정 준비, 반복 실행 가능
./gradlew :bootRun                   # 판매 앱: 별도 터미널에서 실행
./gradlew :mock-pg:bootRun           # Mock PG: 별도 터미널에서 실행
curl -f http://localhost:8080/actuator/health
curl -f http://localhost:8081/actuator/health
```

회원가입은 `POST /api/v1/members`에 `email`, `password`, 선택 항목 `nickname`을 JSON으로 전달한다. 성공 응답에는 정규화 이메일·닉네임·USER 권한만 포함한다. 로그인 세션은 생성하지 않는다. 닉네임과 오류 조건은 [1-3 문서](docs/stages/01-3-member.md)를 따른다.

관리자는 웹 API가 아닌 별도 명령으로 초기화한다. DB 연결 환경과 `ADMIN_EMAIL`, `ADMIN_PASSWORD`를 실행 셸의 환경변수로 준비하고 필요한 경우 `ADMIN_NICKNAME`을 지정한다. 비밀번호는 명령 인수나 저장소 파일에 넣지 않는다.

```sh
./gradlew initAdmin
```

기존 같은 이메일의 ADMIN은 변경하지 않고, USER가 있으면 오류로 종료한다. 명령에는 DB의 마이그레이션과 조회·쓰기 권한이 필요하다.

두 앱의 실행 셸에도 `set -a; source .env; set +a`가 필요하다. 판매 앱 포트는 `SERVER_PORT`로 바꿀 수 있다. 준비 스크립트는 기존 데이터를 삭제하지 않으며 기존 볼륨에도 실행해야 한다. 기존 계정의 비밀번호를 변경하지 않으므로 최초 준비 후 `.env` 자격 증명을 바꿀 때는 DB 계정을 별도로 관리해야 한다. Compose의 최초 초기화 환경변수도 기존 볼륨의 자격 증명을 변경하지 않는다. `.env`는 Git에 포함하지 않는다.

```sh
./gradlew test                       # 빠른 구조·단위 테스트
./gradlew integrationTest            # 격리된 Testcontainers MySQL 검증
./gradlew clean build                # 두 테스트 모두 포함, Docker 필수
```

`check`는 `tools/order-regression`의 Python 검증기 테스트도 실행하므로 Python 3.9 이상이 필요하다. 실제 프로세스 종료와 PG 장기 중단을 포함한 정합성 실험은 별도 명령이며 [실험 도구 안내](tools/order-regression/README.md)를 따른다.

Docker가 실행되지 않으면 통합 테스트는 실패한다. 테스트는 개발 DB를 초기화하지 않는다. `docker compose down`은 컨테이너를 중지하고 볼륨은 보존한다. Flyway는 스키마별 이력을 독립적으로 관리하며 적용된 마이그레이션 수정 대신 새 버전을 추가한다. 현재 Flyway는 MySQL 8.4 지원 경고를 출력하므로 실제 MySQL 검증 결과와 함께 확인해야 한다.

의존성: [Spring Boot 4.1 관리 버전](https://docs.spring.io/spring-boot/4.1/appendix/dependency-versions/coordinates.html), [ArchUnit 사용 안내](https://www.archunit.org/userguide/html/000_Index.html), [Gradle Java 호환성](https://docs.gradle.org/current/userguide/compatibility.html).

## 사용자 결제

로그인 세션과 CSRF 토큰으로 `POST /api/v1/orders/{orderId}/payment`를 호출하며 본문은 보내지 않는다. 기본 PG 성공이면 200과 주문 CONFIRMED·paymentStatus SUCCEEDED를 반환한다. 결과 미확인은 202와 점유 유지, 명확한 거절·종료 확인된 만료는 409다. 재시도와 상세 GET의 차이는 [결제 위키](docs/wiki/payments.md)를 따른다.

`limited-sale.payment.timeout` 기본 PT3S는 한 요청의 PG 통신 예산, `limited-sale.payment.retry-interval` 기본 PT10S는 영속 재조회 간격이다. 자동 복구와 사용자 결제 요청은 같은 주문별 임대를 사용하며 만료 후 PG 종료 확인으로 진입한다.

## 관측 (1-9)

판매 앱은 Actuator 관리 서버를 공개 API와 다른 포트에서 연다. 기본 `127.0.0.1:8090`이며 `MANAGEMENT_SERVER_ADDRESS`, `MANAGEMENT_SERVER_PORT`로 바꾼다. 여기서는 `health`와 `prometheus`만 노출하고, 공개 포트(`8080`)는 `/actuator/health`의 상태만 돌려주며 지표는 내지 않는다. 같은 PC에서 판매 앱을 여러 개 띄우면 인스턴스마다 다른 관리 포트를 준다.

```sh
curl -s http://127.0.0.1:8090/actuator/prometheus | head   # HTTP, JVM, Hikari, 주문·결제 지표
curl -i -H 'X-Request-Id: demo-1' http://localhost:8080/api/v1/products   # 응답 헤더에 같은 값
```

모든 요청에 `X-Request-Id`가 붙는다. 영문·숫자·`-`·`_` 1~64자면 그대로 쓰고, 아니면 새 UUID로 바꾼다. 응답 헤더로 돌려주고 콘솔 로그 `[요청ID]`에 남는다. 업무 지표는 `order_creation_results_total{result}`, `order_payment_results_total{result}`, `order_unfinished`, `order_requires_review`이며 라벨에 회원·주문·요청 ID를 쓰지 않는다. 미완료·확인 필요 주문 수는 `limited-sale.observability.backlog-interval`(기본 `PT10S`)마다 DB에서 센다.

Prometheus(`127.0.0.1:9090`)와 Grafana(`127.0.0.1:3000`)는 `monitoring` 프로필로만 뜬다. 기본 `docker compose up`은 MySQL·Redis만 띄운다. 판매 앱을 먼저 실행한 뒤 시작한다. Prometheus는 `host.docker.internal:8090`을 5초마다 읽으므로 앱이 `127.0.0.1`에 묶여 있어도 Docker Desktop에서 수집된다. Linux는 `extra_hosts`의 `host-gateway`가 같은 이름을 연결한다. 다른 관리 포트를 쓰면 `infra/prometheus/prometheus.yml`의 대상 주소를 맞춘다.

```sh
docker compose --profile monitoring up -d prometheus grafana
# 포트 충돌 시 PROMETHEUS_PORT, GRAFANA_PORT 환경변수로 바꾼다. 모두 선택 값이다.
open http://127.0.0.1:3000/d/limited-sale-overview   # 익명 Viewer, 로그인 불필요
docker compose --profile monitoring down              # 볼륨은 보존한다
```

Grafana는 로컬 전용이며 익명 접근을 Viewer로만 허용한다. 데이터 소스와 대시보드(`infra/grafana/dashboards/limited-sale.json`)는 파일로 프로비저닝하고 저장소에 비밀번호는 없다. 관리자 계정은 Grafana 기본값이므로 필요하면 첫 로그인에서 바꾼다.

MySQL 잠금·쿼리 스냅샷은 부하 실험 전후에 저장해 차이를 본다. 계정은 `performance_schema` 조회와 `PROCESS` 권한이 필요하다. 비밀번호는 옵션이 아니라 `MYSQL_PWD`(없으면 `MYSQL_ROOT_PASSWORD`)에서만 읽고 출력하지 않는다. 호스트에 `mysql` 클라이언트가 없으면 `--container <이름>`으로 컨테이너 안 클라이언트를 쓴다.

```sh
set -a; source .env; set +a
scripts/mysql-observe.sh --user root > before.txt   # 호스트·포트는 --host, --port
# ... 부하 실험 ...
scripts/mysql-observe.sh --user root > after.txt
diff before.txt after.txt
```

## 자동 복구와 작업 임대

기본 자동 복구는 켜져 있다. `limited-sale.recovery.enabled=false`로 중지할 수 있다. 실행 간격 `interval=PT1S`, 회당 `batch-size=20`, 서버당 `concurrency=4`, 임대 `lease-duration=PT30S`, 반복 경고 `warning-interval=PT5M`이며 모두 `limited-sale.recovery` 아래 설정이다. 실행 대기열에 임대를 쌓지 않고 실행 직전에 취득한다.

미요청 주문을 만료 전 임의 결제하지 않는다. 만료 후 PG 결과를 모르면 점유 유지·운영 확인 표시·기본 10초 재시도이며 강제 실패나 자동 재고 반환은 없다. 종료·재시작과 오래된 토큰 차단은 [복구 위키](docs/wiki/recovery.md)를 본다.

## 여러 인스턴스 실행과 정합성 대조

판매 앱 JAR 두 개를 `8080`, `8082`로 띄우고 앞에 Caddy 로드 밸런서(`127.0.0.1:8000`)를 둔다. MySQL·Redis·Mock PG는 공유한다. 기본 `docker compose up`은 mysql과 redis만 시작하며 로드 밸런서는 `multi-instance` 프로필에서만 시작한다. 분산은 라운드 로빈이고 고정 세션과 실패 재시도는 없다. 세션과 CSRF 토큰은 Redis에 있으므로 한 로그인으로 두 인스턴스를 모두 사용할 수 있다. 설정은 [infra/caddy/Caddyfile](infra/caddy/Caddyfile)이며 `SALES_UPSTREAMS`(공백으로 구분한 `host:port`)로 대상을 바꾼다. 컨테이너는 `host.docker.internal`로 호스트의 JVM에 접속하므로 앱은 루프백 전용이 아닌 기본 바인딩이어야 한다.

```sh
./gradlew bootJar :mock-pg:bootJar
set -a; source .env; set +a
docker compose --profile multi-instance up -d    # mysql, redis, load-balancer
./scripts/prepare-local-db.sh
java -jar mock-pg/build/libs/mock-pg-0.0.1-SNAPSHOT.jar                         # 터미널 1
SERVER_PORT=8080 java -jar build/libs/limited-sale-0.0.1-SNAPSHOT.jar           # 터미널 2
SERVER_PORT=8082 MANAGEMENT_SERVER_PORT=8092 java -jar build/libs/limited-sale-0.0.1-SNAPSHOT.jar  # 터미널 3
curl -f http://127.0.0.1:8000/actuator/health                                   # 로드 밸런서 경유
```

클라이언트는 `http://127.0.0.1:8000`만 사용한다. 로드 밸런서 포트는 `LB_PORT`로 바꾼다. Caddy 접근 로그(JSON, stdout)의 `upstream` 필드가 요청을 처리한 인스턴스다. 응답에는 인스턴스 정보를 넣지 않는다.

```sh
docker compose logs --no-log-prefix load-balancer | python3 -c "
import collections, json, sys
n = collections.Counter()
for line in sys.stdin:
    try: e = json.loads(line)
    except ValueError: continue
    r = e.get('request') or {}
    if r.get('method') == 'POST' and r.get('uri') == '/api/v1/orders': n[e.get('upstream')] += 1
print(dict(n))"
```

정합성 대조 SQL [tools/reconciliation/reconcile.sql](tools/reconciliation/reconcile.sql)은 위반 행만 돌려준다. 빈 결과가 정합이다. 읽기 전용 단일 SELECT이며 개발 DB에도 그대로 실행한다. 진행 중인 주문(AWAITING_PAYMENT·PAYMENT_PENDING)은 위반으로 보지 않는다. 검사 항목은 판매 수량 범위와 점유·확정 합계, 사용자별 한도, 주문의 점유·결제·요청 결과 존재, 금액 일치, 확정 주문의 성공 결제, 요청 키 중복이다.

```sh
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE"' < tools/reconciliation/reconcile.sql
```

로드 밸런서를 거친 1·2 인스턴스 실험은 `tools/order-regression/run_balanced.py`이며 [실험 도구 안내](tools/order-regression/README.md)를 따른다. 대조 SQL 검증은 `./gradlew integrationTest`의 `ReconciliationSqlIntegrationTest`가 정합 DB와 의도적으로 깨뜨린 DB로 실행한다.

## 기준 부하 실험 (1-11)

> 참고: `benchmarks/load-baseline`은 Java 21·Spring Boot 3.5.16에서 측정한 1-11 결과다. 2단계 비교 기준선은 같은 시나리오를 Java 25·Spring Boot 4.1.2에서 다시 측정한 `benchmarks/load-baseline-java25`다([재측정 결과](docs/stages/02-redis.md#기준선-재측정-결과)).

k6 시나리오 [tests/load/baseline-order.js](tests/load/baseline-order.js)를 한 명령으로 준비·실행·수집·대조한다. 판매 앱 2개, Caddy, Mock PG, 전용 MySQL·Redis를 실행마다 새로 만들고 자기 자원만 정리한다. 개발 컨테이너와 DB는 건드리지 않는다. k6(v1 이상)와 Docker, Java 25(`JAVA_HOME`)이 필요하다.

```sh
./gradlew bootJar :mock-pg:bootJar
caffeinate -ims python3 -B tools/order-regression/run_baseline.py --scenario baseline --runs 3 --output /tmp/load-baseline
caffeinate -ims python3 -B tools/order-regression/run_baseline.py --scenario under-demand --runs 1 --output /tmp/load-baseline
```

`baseline`은 사용자 10,000명·재고 1,000, `under-demand`는 사용자 800명·재고 1,000이며 둘 다 동시 VU 500이다. 통과 기준을 하나라도 어기면 종료 코드가 0이 아니다. 비밀값(세션 쿠키·CSRF)은 0600 임시 파일로만 k6에 넘기고 실행 후 지운다. 결과 형식과 커밋 대상은 [실험 도구 안내](tools/order-regression/README.md)를 따른다.
