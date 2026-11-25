# 3단계: Kafka 기반 비동기 주문

작성일: 2026-11-10

[전체 기획](../project-plan.md) · [구조 설계](../architecture.md)

## 상태

3-1 비동기 계약 확정(2026-11-24): DB 접수 + 아웃박스, 점유부터 비동기, 판매 이벤트 ID 파티션, v2 비동기 API 추가. 3-2 Kafka 실행·연결 정책 확정(2026-11-24), 구현·검증 완료. 3-3 영속 접수·상태 조회 구현·검증 완료(v2는 기본 꺼짐). 3-4 아웃박스 발행기(이름 잠금 단일 발행자) 구현·검증 완료. 3-5 주문 소비자 구현·검증 완료. 3-6 재시도·실패 복구 구현·검증 완료, v2 기본 켜짐. 3-7 비동기 관측 정책 확정(2026-11-25).

## 확정한 방향

Kafka를 단계적으로 도입해 비동기 처리 경험을 보여준다. 앞 단계에서 확정한 구매 한도·Mock PG·재고 점유 규칙을 유지하며 중복 메시지가 중복 주문·결제를 만들지 않게 한다.

## 구현 계획

3-1에서 DB 영속 접수와 아웃박스 방식으로 확정했다(아래 "3-1 확정 정책"). 아래 작업 순서는 이 방식을 기준으로 한다.

| 소단계 | 작업과 결과물 | 완료 기준 |
|---|---|---|
| 3-1. 비동기 계약과 전달 방식 | 접수·확정 응답, 요청 식별자, 상태 전이, 멱등성, 순서 기준·파티션 키, 재시도·실패 정책 정의. DB 접수·아웃박스와 Kafka 직접 접수를 비교 | 어느 시점부터 접수를 보장하는지와 장애별 복구 책임을 설명할 수 있음. 종료 전 접수와 종료 후 처리 규칙을 포함하고 API 변경 방식도 정함 |
| 3-2. Kafka 실행과 연결 | Compose·토픽·프로듀서·소비자 설정과 연결 관측 구성 | 테스트 메시지의 발행·소비와 브로커 중단 시 오류를 확인함. 로컬 단일 브로커 검증을 고가용성 검증으로 설명하지 않음 |
| 3-3. 영속 접수와 상태 조회 | 접수 저장·중복 접수 대응·상태 조회 구현. 접수와 발행 대기 기록을 같은 트랜잭션에 저장 | 접수 응답 후 서버가 재시작돼도 요청을 조회할 수 있음. 실패한 접수를 성공으로 응답하지 않으며 종료 후 신규 접수는 거절함 |
| 3-4. 신뢰할 수 있는 메시지 전달 | 발행 대기 기록의 조회·발행·완료 표시와 재전송 구현 | 저장·발행 사이와 발행·완료 표시 사이의 중단을 재현해 접수 요청이 유실되지 않음을 확인함. 중복 전달 가능성은 명시함 |
| 3-5. 소비와 주문 확정 | 서버 접수 기록 검증, 기존 주문 생성 트랜잭션 재사용(결제는 기존 사용자 결제 API), DB 상태 전이와 소비 완료 경계 구현 | 같은 메시지 재전달과 DB 커밋 직후 소비자 종료에도 주문이 한 번만 확정됨. 종료 전 접수 요청이 종료 후에도 처리됨 |
| 3-6. 재시도와 실패 복구 | 일시 장애·영구 실패 구분, 제한된 재시도와 실패 보관, 정체 요청 탐지·재처리 구현 | 계속 실패하는 요청이 다른 요청을 무기한 막지 않음. 재처리에도 중복 주문이 없고 최종 실패 사유를 추적할 수 있음. 순서 정책과의 충돌도 확인함 |
| 3-7. 비동기 관측 | 소비 지연, 접수부터 확정까지 시간, 미완료·재시도·실패 수, 발행 적체와 요청 ID 추적 추가 | 소비자 중단 시 적체를 확인하고 재개 후 감소를 관측함. API 응답 지연과 주문 완료 지연을 별도로 확인할 수 있음 |
| 3-8. 종단 간 검증과 비교 | 브로커·소비자 중단과 재시작, 중복 전달, 종료 시각 경계, 부하 실험 및 접수·주문 기록 대조 | 사전 정의한 검증 기한 내 모든 접수 요청이 최종 상태에 도달함. 정상 수량 기준을 유지하고 동기 방식과 접수 처리량·최종 주문 처리량·완료 지연을 비교함 |

## 3-1 확정 정책

2026-11-24 사용자와 선택지로 확정했다(8개 질문 모두 추천안). 아래 #1~#8이 사용자의 선택이고, #9~#16은 그 선택을 따르도록 주 에이전트가 정한 세부다. 세부는 해당 소단계에서 바꿀 수 있지만 바꾸면 이 표를 먼저 고친다.

### 출발점이 된 사실

- 2-7 이후에도 처리량의 상한은 판매 이벤트 행 잠금이다. 구매 경로의 주문·결제는 이 잠금을 한 줄로 기다린다([2-7 결과](02-redis.md#2-7-구현과-검증)).
- Kafka는 파티션 안의 순서만 보장한다. DB 트랜잭션과 Kafka 발행은 하나로 묶이지 않는다. Kafka만으로 종단 간 정확히 한 번 처리를 보장하지 않는다.

### 사용자 선택

| # | 항목 | 확정 내용 | 이유 |
|---|---|---|---|
| 1 | 전달 방식 | DB 영속 접수 + 아웃박스. API가 접수 기록과 발행 대기 기록을 한 트랜잭션에 저장하고 커밋 뒤 202를 응답한다. 별도 발행기가 Kafka로 보낸다. Kafka 직접 접수는 채택하지 않는다 | 기간 내 접수 증거가 DB에 남고 응답 직후 상태를 조회할 수 있다. 기존 요청 키 멱등성을 그대로 쓴다. 접수 처리량은 DB 쓰기로 제한된다는 비용을 받아들인다 |
| 2 | 비동기 경계 | API는 인증·요청 제한·품절 사전 판정·접수 저장만 한다. 수량 점유와 주문 생성은 소비자가 한다. 결제는 지금의 사용자 결제 API(`POST /api/v1/orders/{id}/payment`)를 그대로 쓴다 | 병목인 행 잠금 경쟁을 소비자 쪽으로 옮긴다. 결제 계약과 1단계 복구 흐름을 바꾸지 않는다 |
| 3 | 순서·파티션 | 파티션 키는 판매 이벤트 ID. 선착순은 "DB 접수 순서"로 정의하고(3-5 #2에서 "커밋되어 발행된 순서"로 수정) 발행기는 이벤트별 접수 순서대로 보낸다. 같은 이벤트는 한 소비자가 차례로 처리한다 | 같은 이벤트 안의 잠금 경쟁이 사라지고 처리 순서가 접수 순서와 같다. 이벤트 하나의 처리량 상한은 소비자 하나라는 한계를 문서에 남긴다 |
| 4 | API | 기존 `POST /api/v1/orders`(동기, 201)는 그대로 둔다. 비동기 접수 API(202)와 접수 상태 조회 API를 새로 추가한다 | 3-8에서 같은 환경으로 동기·비동기를 비교한다. 응답 계약이 설정에 따라 바뀌지 않는다 |
| 5 | 기간·중단 판단 | 접수 API는 잠금 없이 이벤트 존재·판매 기간·중단을 DB 시각으로 확인해 거절한다. 소비자는 기간을 **저장된 접수 시각**으로 판단하고(종료 전 접수는 종료 후에도 처리), 중단은 **처리 시점**에 판단한다(중단하면 대기 중인 접수도 멈춤). 구매 한도·재고는 소비자가 판단한다 | 종료 전 접수 보장과 긴급 중단의 효과를 함께 지킨다 |
| 6 | 멱등 소비 | 소비자는 한 DB 트랜잭션에서 접수 행을 잠그고, 이미 최종 상태면 건너뛴다. 아니면 점유·주문 생성 후 결과를 저장한다. Kafka 오프셋은 DB 커밋 뒤에 커밋한다(최소 한 번 전달). Kafka 트랜잭션은 쓰지 않는다 | MySQL 쓰기는 Kafka 트랜잭션에 들어가지 않는다. 중복 전달은 생길 수 있고, 효과를 한 번으로 만드는 것은 DB다 |
| 7 | 재시도 | 일시 오류(DB 연결·잠금 타임아웃 등)는 같은 메시지를 제자리에서 지수 백오프로 최대 약 30초 재시도한다. 그래도 실패하면 접수를 `PROCESSING_FAILED` 최종 상태로 저장하고 다음 메시지로 넘어간다. 데이터 오류는 바로 실패로 저장한다. 실패를 저장할 수도 없는 DB 장애면 소비를 멈추고 재개 때 다시 처리한다. 재시도 토픽·DLT는 쓰지 않는다 | 같은 이벤트의 처리 순서를 접수 순서로 유지한다. 계속 실패하는 요청 하나가 이벤트 전체를 무기한 막지 않는다 |
| 8 | 정체·검증 기한 | (3-6 #1에서 정체 기준 수정) 발행 뒤 60초 넘게 최종 상태가 아닌 접수는 정체로 보고 지표로 남기고 다시 발행한다(중복은 #6이 막는다). 3-8은 부하 종료 뒤 5분 안에 모든 접수가 최종 상태에 도달해야 통과한다 | 부하 중 정상 대기열을 오탐하지 않으면서 유실을 기한 안에 찾는다 |

### 주 에이전트가 정한 세부

| # | 항목 | 내용 |
|---|---|---|
| 9 | 엔드포인트 | `POST /api/v2/orders`(본문은 v1과 같은 `saleId`·`quantity`, 헤더 `Idempotency-Key`)는 202와 접수 ID·상태·조회 URL을 응답한다. `GET /api/v2/order-requests/{acceptanceId}`는 본인 접수만 조회한다(남의 것은 404). 세션·CSRF·요청 제한(주문 생성 카운터)은 v1과 같다 |
| 10 | 요청 키 | (3-3에서 수정, 사용자 선택) v1과 v2는 요청 키 공간을 **분리**한다. v2 중복은 접수 테이블의 (회원, 요청 키) 유니크가 막는다. 같은 회원·키·입력이면 기존 접수를 재생하고, 입력이 다르면 409 `IDEMPOTENCY_CONFLICT`다. 같은 UUID를 v1과 v2에 보내면 별개의 구매 시도다. 소비자는 `order_request_results`를 쓰지 않고 결과를 접수 행에 기록한다. 처음 정한 "키 공유"는 v1·v2 동시 요청이 각자의 유니크만 통과해 한 키로 두 번 구매할 수 있어 바꿨다 |
| 11 | 접수 시 거절 | (3-3에서 수정) 없는 이벤트·기간 외·중단·품절 표시는 아웃박스 없이 접수 행을 `REJECTED`(사유 포함)로 저장하고 v1과 같은 409/404로 즉시 응답한다. 같은 키 재요청은 같은 응답이다. 입력 오류·인증·CSRF·429는 저장하지 않는다 |
| 12 | 접수 상태 | `ACCEPTED`(저장됨, 발행 전·처리 전) → 최종 상태 `ORDER_CREATED`(주문 ID 포함, 이후 v1 결제), `REJECTED`(사유: `SALE_SOLD_OUT`·`PURCHASE_LIMIT_EXCEEDED`·`SALE_STOPPED`), `PROCESSING_FAILED`(사유 코드). 발행 여부는 아웃박스가 따로 기록하며 사용자에게 노출하지 않는다 |
| 13 | 토픽·브로커 | 토픽 `limited-sale.order-acceptances.v1`, 로컬 KRaft 단일 브로커, 복제 1. 파티션 수와 소비자 동시성은 3-2에서 정한다. 프로듀서는 `acks=all`·멱등 프로듀서로 파티션 안 순서를 지킨다. 단일 브로커 결과를 고가용성으로 설명하지 않는다 |
| 14 | 발행기 | 아웃박스를 접수 ID 순서로 읽어 보내고 브로커 확인 뒤 완료로 표시한다. 저장~발행, 발행~완료 표시 사이의 중단은 재전송으로 복구하며 중복 전달은 #6이 흡수한다. 인스턴스 2개에서 한 행을 동시에 보내지 않는 방식(임대 또는 단일 발행자)은 3-4에서 정한다 |
| 15 | 주문 만료 | 미결제 만료 5분은 소비자가 주문을 만든 시각부터 센다. 접수 대기 시간은 결제 시간을 깎지 않는다 |
| 16 | 3-8 비교 | 2-6·2-7과 같은 조건(사용자 10,000·재고 1,000·VU 500)에서 v1 동기와 v2 비동기를 비교한다. 접수 응답 지연, 접수~최종 상태 지연, 최종 주문 처리량, 접수 기록과 주문·결제 대조를 보고한다. 빠른 202를 주문 처리량 향상으로 해석하지 않는다 |

### 후속 소단계에 미친 영향

- 3-2: #13의 파티션 수·동시성, 연결·중단 관측.
- 3-3: #1·#4·#5·#9~#12의 접수 저장·즉시 거절·상태 조회. 스키마는 새 Flyway 버전으로 추가하고 적용된 파일은 고치지 않는다.
- 3-4: #14 발행기와 다중 인스턴스 처리.
- 3-5: #2·#3·#5·#6·#15 소비와 주문 생성.
- 3-6: #7·#8 재시도·실패 저장·정체 재발행.
- 3-7·3-8: #8·#16.

## 3-2 확정 정책

2026-11-24 사용자와 선택지로 확정했다(4개 모두 추천안). #5~#9는 주 에이전트가 정한 세부다. 3-2는 업무 기능 없이 실행·연결·관측만 만든다. 접수·발행·소비 업무 로직은 3-3 이후다.

| # | 항목 | 확정 내용 | 이유 |
|---|---|---|---|
| 1 | 브로커 | Apache 공식 `apache/kafka` 이미지의 KRaft 단일 브로커를 Compose 기본 서비스로 추가한다(복제 1, 태그 고정). 통합 테스트는 같은 이미지의 Testcontainers를 쓴다. Bitnami·Confluent 이미지는 쓰지 않는다 | 공식 배포판이고 ZooKeeper가 없다. 단일 브로커 결과를 고가용성으로 설명하지 않는다 |
| 2 | 파티션·동시성 | `limited-sale.order-acceptances.v1` 파티션 6, 앱 인스턴스당 소비자 동시성 3(인스턴스 2개 × 3) | 한 인스턴스가 죽으면 남은 인스턴스가 6개를 가져간다. 인기 이벤트 하나는 파티션 하나라 처리량 상한이 소비자 하나라는 한계를 남긴다 |
| 3 | 관측 | 앱은 Micrometer로 프로듀서·소비자 클라이언트 지표를 노출한다. 소비 지연(lag)은 `monitoring` 프로필의 kafka-exporter가 브로커 기준으로 수집하고 Prometheus가 수집한다 | 소비자가 죽어도 lag을 볼 수 있어야 적체를 관측한다. 2-2의 redis_exporter와 같은 구성이다 |
| 4 | health | Kafka 상태를 `/actuator/health`에 넣지 않는다. 지표와 로그로만 알린다 | 브로커 장애로 로드 밸런서가 인스턴스를 빼면 v1 주문과 조회까지 멈춘다. 아웃박스 덕분에 접수는 브로커 없이도 받을 수 있다 |
| 5 | 기동 | 앱은 브로커 없이도 기동한다. 토픽은 앱의 관리 설정(NewTopic)으로 만들고 브로커 자동 생성은 끈다. 브로커가 없으면 기동 중 토픽 생성 실패를 경고로 남기고 계속한다 | v1은 Kafka와 무관하다. 토픽 설정을 코드로 관리한다 |
| 6 | 프로듀서 | `acks=all`, 멱등 프로듀서, 파티션 안 순서 유지(`max.in.flight.requests.per.connection` 5 이하), 키는 판매 이벤트 ID 문자열. `max.block.ms`·`delivery.timeout.ms`는 약 5초 수준으로 짧게 둔다. 값은 메시지 계약이 정해지는 3-3·3-4에서 쓴다 | 기본 `max.block.ms` 60초면 브로커 장애 때 발행 스레드가 오래 묶인다 |
| 7 | 소비자 | 자동 커밋 끔, 수동 확인(DB 커밋 뒤 오프셋 커밋은 3-5에서 구현), 그룹 ID `limited-sale-order-consumer`, `auto.offset.reset=earliest` | 3-1 #6을 따른다. 처음 붙는 그룹이 기존 접수를 놓치지 않는다 |
| 8 | 연결 설정 | 주소는 `KAFKA_BOOTSTRAP_SERVERS`(기본 `localhost:9092`). 1-10 격리 환경(앱 2개)에도 같은 브로커를 붙인다. 비밀값이 없는 평문 로컬 연결이며 운영 보안 설정은 범위 밖이다 | 기존 MySQL·Redis 설정 방식과 같다 |
| 9 | 완료 기준 | 통합 테스트로 확인한다: 테스트 토픽에 키가 있는 메시지를 발행하고 소비해 같은 키가 같은 파티션에 순서대로 도착함, 앱 기동 시 운영 토픽이 파티션 6으로 생성됨, 브로커를 멈추면 발행이 약 5초 안에 실패하고 오류가 지표·로그에 남으며 health는 UP을 유지함, 브로커 재시작 뒤 앱 재시작 없이 다시 발행됨, 브로커 없이 앱이 기동하고 v1 주문이 정상 동작함. Compose로 브로커·kafka-exporter를 띄워 Prometheus에서 브로커·lag 지표가 보임을 수동 확인하고 기록한다. `./gradlew clean build` 통과 | 로컬 단일 브로커의 연결·장애 감지까지만 검증한다 |

### 3-2 구현과 검증

2026-11-24 워커가 구현했다. 업무 접수·발행·소비 코드는 없고 실행·연결·관측만 있다.

- 의존성: `spring-boot-starter-kafka`(Spring Boot 4에서 Kafka 자동 설정은 `spring-kafka`만이 아니라 이 스타터에 있다). 버전은 BOM이 관리해 spring-kafka 4.1.2, kafka-clients 4.2.2다. 통합 테스트는 `testcontainers-kafka`(2.0.5)를 추가했다.
- 구조: 토픽 정의는 `order.infrastructure.messaging.OrderAcceptanceTopicConfiguration`의 `NewTopic` 빈(`limited-sale.order-acceptances.v1`, 파티션 6, 복제 1)이다. 생성은 `common.messaging.KafkaTopicProvisioner`가 맡는다. 도메인 패키지에는 Kafka 타입이 없고 기존 `ArchitectureTest`의 `org.apache.kafka..` 금지 규칙을 그대로 통과한다.
- 기동(#5): Spring Kafka의 `KafkaAdmin`은 컨텍스트 기동 중에 토픽을 만들고 브로커가 없으면 관리 클라이언트의 `default.api.timeout.ms` 60초를 기다린다(실측: `createOrModifyTopics`가 브로커 없이 60초 뒤 실패. 이 경로를 기동 중에 두면 그만큼 기동이 늦어지고 모든 통합 테스트 컨텍스트가 같은 대기를 겪는다). 그래서 `spring.kafka.admin.auto-create=false`로 기동 중 생성을 끄고(`fail-fast=false`, `modify-topic-configs=false`도 명시) `KafkaTopicProvisioner`가 기동 뒤 백그라운드 스레드에서 `createOrModifyTopics`를 부른다. 실패하면 WARN을 남기고 1초에서 최대 30초까지 두 배씩 늘려 성공할 때까지 재시도한다. 브로커가 앱보다 늦게 떠도 토픽이 만들어진다(브로커의 자동 생성을 껐으므로 한 번 놓치면 재시작 전까지 발행이 계속 실패한다). 관리 클라이언트의 `default.api.timeout.ms`는 5초, `request.timeout.ms`는 3초로 줄였다.
- 기동 중 클라이언트 폭주 결함과 수정(주 에이전트 검토에서 발견): 첫 구현 뒤 `clean build`가 약 5분에서 18분으로 늘었다. 원인은 `KafkaAdmin`의 기동 중 토픽 생성이었다. 다른 `application.yml`(Mock PG의 것)이 클래스패스에서 판매 앱의 것을 가리는 통합 테스트 컨텍스트는 이 앱의 Kafka 설정이 하나도 적용되지 않아 Spring Boot 기본값(`spring.kafka.admin.auto-create=true`, 브로커 `localhost:9092`)으로 돌았다. 그 컨텍스트에서 `KafkaAdmin`이 기동 중에 `NewTopic` 빈을 만들려고 관리 클라이언트를 열어 `default.api.timeout.ms` 60초 동안 기다렸고, 그동안 Kafka 4 클라이언트가 연결 거부마다 `AdminMetadataManager`로 재부트스트랩을 거의 쉬지 않고 반복해 컨텍스트마다 약 9만 줄(`Rebootstrapping…`, `Connection to node -1 … could not be established`)을 썼다. 기동도 컨텍스트마다 60초씩 늦어졌다(로그의 `mock-pg-admin-N`은 그 컨텍스트의 애플리케이션 이름이다). 앞서 "Kafka 없는 통합 테스트는 접속하지 않는다"고 설명한 것은 틀렸다. `topic-provisioning.enabled=false`는 `KafkaTopicProvisioner`만 끄고 `KafkaAdmin`의 기동 중 생성은 끄지 못했다. 수정: (1) `KafkaAdminStartupGuard`(`BeanPostProcessor`)가 설정과 무관하게 `KafkaAdmin.setAutoCreate(false)`를 강제해 기동 중에는 Kafka 클라이언트를 만들지 않는다. (2) 토픽 생성은 `KafkaTopicProvisioner`가 시도마다 열고 닫는 관리 클라이언트로만 한다. (3) 재접속 간격을 늘렸다. 관리·소비자는 `reconnect.backoff.ms=1000`, `reconnect.backoff.max.ms=5000`, `retry.backoff.ms=1000`, `retry.backoff.max.ms=5000`, 프로듀서는 `retry.backoff.ms=500`, `retry.backoff.max.ms=2000`(재접속은 관리·소비자와 같다). 관리 클라이언트는 시도마다 새로 만들고 버리므로 `metadata.recovery.strategy=none`으로 재부트스트랩을 껐다(이 값이 없으면 브로커 없이 25초에 Kafka 로그 131줄, 있으면 100줄 미만). 프로듀서·소비자의 재부트스트랩은 기본 동작이다. 결과: 통합 테스트 결과 XML 전체에서 `9092`를 포함한 줄이 0이고 `clean build`는 7분이다.
- 프로듀서(#6): `acks=all`, `enable.idempotence=true`, `max.in.flight.requests.per.connection=5`, `linger.ms=5`, `request.timeout.ms=3000`, `max.block.ms=5000`, `delivery.timeout.ms=5000`. Kafka는 `delivery.timeout.ms >= linger.ms + request.timeout.ms`를 요구하므로(5000 ≥ 5 + 3000) 요청 타임아웃을 3초로 맞췄다. 키·값 직렬화는 `StringSerializer`이며 메시지 계약은 3-3·3-4에서 정한다.
- 소비자(#7): 그룹 `limited-sale-order-consumer`, `enable-auto-commit=false`, `auto-offset-reset=earliest`, 리스너 `ack-mode=manual`, `spring.kafka.listener.concurrency=3`(프로퍼티). `@KafkaListener`는 없다.
- health(#4): Spring Boot 4.1.2의 Kafka 자동 설정에는 `HealthIndicator`가 없다(`spring-boot-kafka` 확인). 별도 끄기 설정 없이 `/actuator/health`에 들어가지 않으며 테스트가 이를 고정한다.
- 브로커(#1): `compose.yaml`의 `kafka` 서비스는 `apache/kafka:4.4.0` KRaft 단일 노드(broker+controller)이고 프로필 없는 기본 서비스다. 자동 토픽 생성 끔, 기본·오프셋·트랜잭션 로그 복제 1, 힙 512MB, `kafka_data` 볼륨, `kafka-broker-api-versions.sh` 헬스체크. 리스너는 호스트용 `HOST`(`localhost:${KAFKA_PORT:-9092}`, 호스트와 컨테이너 포트를 같게 해 광고 주소가 유효하다)와 컨테이너 네트워크용 `INTERNAL`(`kafka:19092`)로 나눴다.
- 관측(#3): `monitoring` 프로필에 `danielqsj/kafka-exporter:v1.11.0`(`127.0.0.1:9308`, `--kafka.server=kafka:19092`)을 redis_exporter와 같은 방식으로 넣고 Prometheus에 `kafka` 작업을 추가했다. 앱의 프로듀서·소비자 클라이언트 지표는 Spring Boot가 Micrometer에 자동으로 연결하며 기존 `limited-sale` 작업이 읽는 `/actuator/prometheus`에 나온다.
- 연결(#8): `KAFKA_BOOTSTRAP_SERVERS`(기본 `localhost:9092`), Compose 게시 포트는 `KAFKA_PORT`, `.env.example`에 추가했다. 1-10 격리 환경(`tools/order-regression/isolated.py`)은 실행마다 같은 이미지의 브로커 컨테이너를 네 번째로 만들고(`containers[0..2]`를 인덱스로 쓰는 기존 코드를 지키려고 Redis 뒤에 둔다) 두 판매 앱에 `KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:<포트>`를 준다. 호스트와 컨테이너 안의 포트를 같게 해야 광고 주소가 호스트의 JVM에 유효하다. 쉘의 `KAFKA_*` 변수는 기존 규칙대로 상속하지 않는다. 브로커 버전·이미지는 `resources.json`에 남는다.
- 이미지·버전: kafka-clients 4.2.2에 대해 `apache/kafka`의 4.2.2·4.3.2·4.4.0 태그를 모두 받아 존재를 확인했다. 가장 최신 정식 태그 4.4.0(2026-10-22)을 골랐다. 4.4.0 브로커와 4.2.2 클라이언트 조합으로 통합 테스트와 수동 확인을 통과했다. kafka-exporter는 `v1.11.0`(2026-10-23), 4.4.0 브로커에서 동작했다(다이제스트 `sha256:54d4296c…`). 통합 테스트의 이미지 상수(`FixedPortKafka.IMAGE`)와 `service_env.KAFKA_IMAGE`는 `compose.yaml`과 같은 태그를 쓴다. Boot를 올려 클라이언트가 바뀌면 브로커 태그를 같이 확인한다.
- 다른 통합 테스트: `./gradlew test`와 `integrationTest`는 `limited-sale.kafka.topic-provisioning.enabled=false`를 시스템 속성으로 받아 토픽 생성 스레드를 만들지 않는다. `KafkaAdminStartupGuard`가 기동 중 생성도 막으므로(위 결함 항목) 기존 통합 테스트는 Kafka 컨테이너를 띄우지 않고 `localhost:9092`에 접속하지도 않으며 기동 시간도 늘지 않는다. Kafka 테스트는 명령행 인자(`--limited-sale.kafka.topic-provisioning.enabled=true`, 시스템 속성보다 우선)로 다시 켠다.
- 통합 테스트(실제 Kafka): [KafkaConnectionIntegrationTest](../../src/integrationTest/java/com/wooyongjeong/limitedsale/common/KafkaConnectionIntegrationTest.java) 7개 — 운영 토픽이 파티션 6·복제 1로 생성됨, 키 5개×20건을 앱의 리스너 컨테이너 설정(수동 확인·동시성 3)으로 소비하면 키마다 한 파티션에서 발행 순서대로 도착함, 프로듀서 설정 값, health에 Kafka 기여자가 없음, 앱 지표에 `kafka_producer_record_send_total`이 나옴, 브로커를 `docker stop`하면 발행이 9초 안에 `TimeoutException`으로 실패하고 ERROR 로그와 `kafka_producer_record_error_total` 증가가 남으며 health는 UP, 브로커를 다시 시작하면 같은 컨텍스트(앱 재시작 없음)에서 발행이 다시 됨. [KafkaAbsentBrokerIntegrationTest](../../src/integrationTest/java/com/wooyongjeong/limitedsale/common/KafkaAbsentBrokerIntegrationTest.java) 4개 — 브로커 없이 기동하고 토픽 생성 실패가 WARN(ERROR 없음)으로 남음, 브로커 없이 25초(프로듀서 발행 실패 포함)를 둬도 Kafka 클라이언트 로그가 100줄 미만·관리 클라이언트 스레드 1개 이하·토픽 생성 경고 1~4번(시도마다 한 번), v1 주문이 201이고 health UP, 브로커가 나중에 뜨면 앱 재시작 없이 토픽이 파티션 6으로 생성됨. [KafkaIdleClientsIntegrationTest](../../src/integrationTest/java/com/wooyongjeong/limitedsale/common/KafkaIdleClientsIntegrationTest.java) 1개 — Kafka 설정이 적용되지 않는 컨텍스트(위 결함 재현 조건)가 30초 안에 기동하고 15초 동안 Kafka 클라이언트 스레드와 로그가 없음. 가드를 빼면 이 테스트가 실패하는 것을 확인했다.
- 고정 포트: Testcontainers는 브로커가 처음 뜰 때의 매핑 포트를 광고 주소로 박는다. 재시작 뒤 임의 포트가 바뀌면 클라이언트가 옛 주소로 접속하므로 `RedisConnectionIntegrationTest`와 같은 방식으로 호스트 포트를 미리 하나 골라 고정 바인딩한다(`FixedPortKafka`). 컨테이너를 지우지 않고 `docker stop`·`start`만 하므로 브로커 데이터도 남는다.
- 전체 검증: `JAVA_HOME=…/openjdk-25.0.1 PYTHONDONTWRITEBYTECODE=1 ./gradlew clean build` 성공(결함 수정 뒤 7분, 수정 전 18분). 단위 135개, 통합 199개(이번에 12개 추가), mock-pg 단위 11개·통합 15개, Python 183개(2개 추가) 모두 통과했고 건너뜀·실패는 없다. `ArchitectureTest` 3개도 통과했다.
- 수동 확인(2026-11-24): `docker compose --profile monitoring up -d mysql redis kafka kafka-exporter prometheus`로 브로커(약 30초 뒤 healthy)와 exporter를 띄웠다. 3-2에는 업무 프로듀서·소비자가 없어서, 저장소 밖에서 만든 일회용 Java 프로그램이 실제 앱 컨텍스트를 띄워(관리 포트 8090) 운영 토픽에 발행하고 앱의 `ConsumerFactory`로 그룹 `limited-sale-order-consumer`의 오프셋을 커밋한 뒤 6건을 더 발행하게 했다(두 번 실행해 토픽에 26건, 그룹의 미소비는 16건). Prometheus `/targets`에서 `kafka`(`kafka-exporter:9308`)와 `limited-sale`(`host.docker.internal:8090`)이 up이었다(8092·redis-exporter는 이번에 띄우지 않아 down). 확인한 값: `kafka_brokers`=1, `kafka_topic_partitions{topic="limited-sale.order-acceptances.v1"}`=6, `kafka_topic_partition_current_offset`(파티션 0=16, 5=10, 나머지 0), `kafka_consumergroup_lag`(그룹 `limited-sale-order-consumer` 파티션 0=10, 5=6, 합계 `kafka_consumergroup_lag_sum`=16), `kafka_consumergroup_members`=1, 앱의 `kafka_producer_record_send_total`=16(두 번째 실행의 프로듀서)·`kafka_producer_record_error_total`=0(`client_id=limited-sale-producer-1`, `kafka_version=4.2.2`), `kafka_consumer_fetch_manager_records_lag_max`, `kafka_producer_*` 시리즈 85개·`kafka_consumer_*` 시리즈 88개. 같은 프로그램으로 Compose 브로커를 `docker compose stop kafka`하자 발행이 5,013ms 뒤 `TimeoutException: Topic … not present in metadata after 5000 ms`로 실패했고 `/actuator/health`는 `UP`이었다. `docker compose start`가 돌아오고 1.6초 뒤 앱 재시작 없이 발행이 다시 성공했다. 격리 환경의 브로커도 따로 확인했다(`Isolated.container`로 띄우고 `wait_kafka`와 호스트 JVM의 `describeCluster`가 `127.0.0.1:<포트>`로 성공, `kafka-topics.sh --version`=4.4.0).
- 정리: 수동 확인으로 띄운 컨테이너는 `docker compose --profile monitoring down`으로 내렸다. 이 실행이 새로 만든 볼륨(`ticketing_kafka_data`, `ticketing_prometheus_data`)은 지웠고 기존 `ticketing_mysql_data`는 그대로 뒀다. 시작 전에 실행 중이던 컨테이너는 없었다.
- 한계·편차:
  - 정책 #5의 "기동 중 토픽 생성 실패를 경고로 남기고 계속"을 `KafkaAdmin` 기본 동작이 아니라 기동 뒤 백그라운드 생성과 재시도로 구현했다. 정책 내용은 바꾸지 않았다. 경고는 기동이 끝난 뒤 첫 시도(약 10초 걸림)가 실패할 때 나온다.
  - 브로커는 한 개이고 복제 1이다. 브로커를 멈추면 메시징이 멈춘다. 이 검증은 연결과 장애 감지까지이며 고가용성 검증이 아니다.
  - 앱의 클라이언트 지표는 프로듀서·소비자가 처음 만들어진 뒤에 나타난다. 3-2의 앱은 업무 클라이언트를 만들지 않으므로 Prometheus의 확인은 일회용 프로그램으로 했고, 자동 검증은 통합 테스트의 `/actuator/prometheus` 확인이다. 3-4·3-5에서 실제 발행기·소비자로 다시 본다.
  - 첫 발행 실패가 "메타데이터에 토픽이 없음"(`max.block.ms` 경로)일 수도 `delivery.timeout.ms` 경로일 수도 있어 둘 다 약 5초이며, 테스트는 9초 미만과 `TimeoutException`만 단언한다. 소비자 동시성 3은 컨테이너 설정으로 확인했고 인스턴스 두 개가 파티션을 나눠 갖는 동작과 리밸런스는 검증하지 않았다(3-5 이후).
  - 1-10 격리 환경은 이제 실행마다 브로커 JVM(힙 512MB)을 하나 더 쓴다. 판매 앱은 발행하지 않아 유휴이지만 과거 실험 결과와 같은 컴퓨터 자원 조건은 아니다. 이전 결과는 다시 측정하지 않았다.
  - 통합 테스트는 4.4.0 브로커 1개만 시험했다. 브로커 재시작은 컨테이너 파일시스템이 남는 `docker stop`·`start`이며 데이터가 사라지는 재생성은 시험하지 않았다. 운영 보안(TLS·SASL)은 범위 밖이다.
- 주 에이전트 독립 검증: 커밋 7개의 공동 작성자 표기와 `src/main`·`build.gradle`·`compose.yaml`·Prometheus 설정 변경을 직접 읽었다. 첫 `./gradlew clean build`가 18분으로 늘어난 것을 보고 로그를 확인해, Kafka를 쓰지 않는 통합 테스트 문맥에서 `KafkaAdmin`이 기동 중 `localhost:9092`에 재접속을 반복한 문제(문맥당 로그 약 9만 줄)를 찾아 워커에게 고치게 했다(`25539d0`). 수정 뒤 직접 다시 빌드했다: 6분 58초, 판매 앱 단위 135·통합 199, Mock PG 11·15, 실패·오류 0. 통합 테스트 결과 전체에서 `9092`가 든 줄은 1줄이다. `git diff --check`도 통과했다.
- 남은 사실: 일부 통합 테스트 문맥에서는 테스트 클래스패스의 Mock PG `application.yml`이 판매 앱의 것을 가린다(3-2 이전부터 있던 구성). 이번 문제의 배경이었고, Kafka 설정은 가드로 막았지만 다른 설정도 같은 영향을 받을 수 있다. 4단계 정리 때 테스트 구성을 점검한다.

## 3-3 확정 정책

2026-11-24 사용자와 선택지로 확정했다(4개 모두 추천안). #5~#10은 주 에이전트가 정한 세부다. 3-3은 접수 저장과 상태 조회까지다. Kafka 발행(3-4)과 소비(3-5)는 없으므로 접수는 `ACCEPTED`에 머문다.

| # | 항목 | 확정 내용 | 이유 |
|---|---|---|---|
| 1 | 요청 키 | v1·v2 키 분리(3-1 #10 수정) | 키를 확인하는 지점이 API마다 하나라 경쟁 조건이 없다 |
| 2 | 스키마 | 새 Flyway 버전(V6)으로 `order_acceptances`(접수·상태·결과)와 `order_outbox`(토픽·키·내용·발행 시각·시도 횟수)를 만들고 한 트랜잭션에 함께 넣는다. 적용된 마이그레이션은 고치지 않는다 | 재발행·정체 재발행이 접수 행을 갱신하지 않아 소비자의 접수 행 잠금과 부딪치지 않는다 |
| 3 | 메시지 | JSON `{"schemaVersion":1,"acceptanceId":"<UUID>","saleId":<id>}`. 키는 판매 이벤트 ID 문자열. 소비자는 DB 접수 기록을 읽어 검증한다 | 메시지 조작·오래된 내용으로 처리할 여지를 없앤다 |
| 4 | 공개 | `limited-sale.order.async.enabled`(기본 `false`). 끄면 v2 두 경로 모두 404이고 아웃박스도 쌓이지 않는다. 테스트·실험에서만 켜고 3-6 완료 때 기본값을 켠다 | 기획: 3-3만 끝난 상태는 중간 결과다 |
| 5 | 접수 흐름 | 요청 제한(v1과 같은 회원별 주문 생성 카운터) → 품절 표시 조회(트랜잭션 밖) → 트랜잭션: 기존 키 조회·재생 → 판매 행을 **잠그지 않고** 읽어 DB 시각으로 존재·기간·중단 판정 → 거절이면 `REJECTED` 접수 저장, 허용이면 `ACCEPTED` 접수 + 아웃박스 저장 → 커밋 뒤 응답 | 3-1 #5. 접수 시각(`accepted_at`)은 같은 판정에 쓴 DB 시각이며 소비자의 기간 판정 기준이 된다 |
| 6 | 응답 | 첫 접수 202(`acceptanceId`·상태·조회 URL, `Location` 헤더). 같은 키 재요청은 접수 상태면 200과 현재 상태, 거절이었으면 같은 409/404. 입력이 다르면 409 `IDEMPOTENCY_CONFLICT`. 동시 같은 키는 유니크 충돌 뒤 승자의 결과를 읽는다(v1과 같은 방식) | v1의 201/200 구분과 같은 원칙이다 |
| 7 | 상태 조회 | `GET /api/v2/order-requests/{acceptanceId}`: 로그인 필요, 본인 접수만(남의 것·없는 것은 404). 접수 ID·판매 ID·수량·상태·거절/실패 사유·주문 ID(`ORDER_CREATED`일 때, v1 주문 조회·결제에 쓰는 공개 ID)·접수 시각·완료 시각 | 상태 조회만으로 다음 행동(결제·재주문)을 정할 수 있게 한다 |
| 8 | 상태 값·제약 | `ACCEPTED`·`ORDER_CREATED`·`REJECTED`·`PROCESSING_FAILED`를 문자열 enum + CHECK로 둔다(1-2 규칙). 상태별 필수 컬럼(주문 ID·사유·완료 시각)을 CHECK로 묶는다. `order_outbox`는 자동 증가 ID 순서가 발행 순서이고 미발행 조회용 인덱스를 둔다. 발행 임대 등 3-4 컬럼은 3-4에서 새 버전으로 추가한다 | 1단계와 같은 스키마 규칙 |
| 9 | 실패 | DB 오류면 접수·아웃박스가 모두 롤백되고 500이다. 실패한 접수를 202로 응답하지 않는다 | 기획의 3-3 완료 기준 |
| 10 | 완료 기준 | 실제 MySQL 통합 테스트: 202 뒤 새 애플리케이션 문맥(재시작)에서도 조회됨, 접수와 아웃박스 행이 함께 생기고 메시지 형식이 맞음, 아웃박스 INSERT 실패를 주입하면 접수도 없음(500), 종료 후·중단·없는 이벤트·품절 표시는 `REJECTED` 저장과 409/404·아웃박스 없음, 같은 키 재요청·입력 변경·동시 같은 키(접수 1개), 429는 저장 안 함, 남의 접수 404, 스위치 끔이면 404와 행 없음, v1 동작 그대로, 판매 행을 잠그지 않음(다른 연결이 `FOR UPDATE`로 잡아도 즉시 응답). `./gradlew clean build` 통과 | 기획 3-3 완료 기준과 3-1 #5·#9~#12 |

### 3-3 구현과 검증

2026-11-24 워커가 구현했다. 접수 저장·아웃박스 행 저장·v2 접수/조회 API·스위치까지이며 Kafka 발행(3-4)·소비(3-5)·재시도·정체 처리(3-6)는 없다. 접수는 `ACCEPTED`에 머문다.

- 구조
  - 스키마: [V6__order_acceptances.sql](../../src/main/resources/db/migration/V6__order_acceptances.sql). 적용된 마이그레이션은 고치지 않았다.
  - sale 쪽: `sale.api`에 비잠금 읽기 포트 `SaleAdmission`(`check(saleId)` → `SaleAdmissionResult`: `ADMITTED`·`SALE_NOT_FOUND`·`SALE_OUTSIDE_PERIOD`·`SALE_STOPPED`와 판정 DB 시각)을 새로 두었다. 구현 `JpaSaleAdmission`은 `findById`(잠금 없음) 뒤 `UTC_TIMESTAMP(6)`을 읽고 `MANDATORY` 트랜잭션에서만 호출된다. 기간·중단 규칙은 `Sale.admissionDecision(now)`가 기존 `allocationDecision`을 수량 0으로 호출해 재사용한다(0개는 한도·재고를 넘을 수 없으므로 기간·중단만 판정한다).
  - order.domain: `AcceptanceStatus`, `OrderAcceptance`(조회용 값), `AcceptanceMessage`(토픽·키·내용, Kafka 타입 없음), `OrderAcceptanceStore`. 거절 사유는 기존 `OrderRejection`을 쓰고 키 충돌은 기존 `OrderRequestKeyCollision`을 쓴다. 토픽 이름·파티션 수·복제 수는 `OrderAcceptanceTopic`(도메인 상수, Kafka 타입 없음)에 두고 3-2의 `OrderAcceptanceTopicConfiguration`과 Kafka 통합 테스트가 이 상수를 참조한다. 그래서 application이 infrastructure를 import하지 않는다.
  - order.application: `OrderAcceptanceService`(요청 제한 → 품절 표시 → 트랜잭션 → 충돌 시 승자 재조회)와 `OrderAcceptanceTransaction`(키 재생 → 비잠금 판정 → `REJECTED` 또는 `ACCEPTED`+아웃박스). 요청 제한은 v1의 `OrderRateLimiter`와 같은 `create` 카운터를 쓴다. 기존 `OrderService`·`OrderTransaction`은 바꾸지 않았다.
  - order.infrastructure.persistence: `OrderAcceptanceEntity`·`OrderOutboxEntity`와 저장소, `JpaOrderAcceptanceStore`. 접수를 먼저 `saveAndFlush`하고 이어서 아웃박스를 `saveAndFlush`한다. `uk_order_acceptances_member_key`의 1062만 키 충돌로 분류한다.
  - presentation: `OrderAcceptanceController`(`@ConditionalOnProperty limited-sale.order.async.enabled=true`), `OrderAcceptanceResponse`·`OrderAcceptanceStatusResponse`, 새 코드 `ORDER_ACCEPTED`(202)·`ORDER_ACCEPTANCE_FOUND`(200)·`ORDER_ACCEPTANCE_NOT_FOUND`(404). 거절·충돌·429는 v1 코드를 그대로 쓴다. 예외 처리는 기존 `OrderExceptionHandler`에 컨트롤러를 추가했다.
- 스키마 요약(DDL)
  - `order_acceptances(id BIGINT AI PK, public_id BINARY(16) UNIQUE, member_id BIGINT FK→members RESTRICT, request_key BINARY(16), sale_id BIGINT(FK 없음), quantity INT CHECK 1..1000000, status VARCHAR(32) utf8mb4_0900_bin, rejection_reason VARCHAR(32), failure_reason VARCHAR(64), order_id BIGINT FK→purchase_orders RESTRICT NULL, accepted_at DATETIME(6), finished_at DATETIME(6) NULL, UNIQUE(member_id, request_key))`.
  - CHECK `ck_order_acceptances_state`: `BINARY status`로 비교해 `ACCEPTED`는 사유·주문·완료 시각 없음, `ORDER_CREATED`는 주문+완료 시각, `REJECTED`는 다섯 사유 중 하나(`SALE_NOT_FOUND`·`SALE_OUTSIDE_PERIOD`·`SALE_STOPPED`·`PURCHASE_LIMIT_EXCEEDED`·`SALE_SOLD_OUT`)+완료 시각, `PROCESSING_FAILED`는 실패 사유+완료 시각이고 나머지 열은 모두 비어 있어야 한다.
  - `order_outbox(id BIGINT AI PK, topic VARCHAR(255), message_key VARCHAR(64), payload VARCHAR(2000) CHECK JSON_VALID, created_at DATETIME(6), published_at DATETIME(6) NULL, attempts INT DEFAULT 0 CHECK >= 0, INDEX ix_order_outbox_unpublished(published_at, id))`. 접수와의 FK는 없다(정책 #2·#3: 메시지는 접수 ID만 담고 행은 접수 행과 독립이다).
- 접수 흐름과 응답은 정책 #5·#6·#9와 같다. 응답 계약은 [HTTP 계약 위키](../wiki/http-contracts.md#v2-비동기-주문-접수-계약), 흐름은 [주문 위키](../wiki/orders.md#v2-비동기-접수기본-꺼짐), 테이블은 [DB 위키](../wiki/persistence.md)에 정리했다.
- 테스트
  - 단위(24개 추가): `AcceptanceMessageTest`(정확한 내용·키), `OrderAcceptanceTransactionTest`(재생 우선·거절 저장·입력 충돌·품절 표시 경로·소유자 조회), `OrderAcceptanceServiceTest`(요청 제한·게이트 순서·충돌 폴백), `OrderAcceptanceControllerMvcTest`(202·`Location`·코드 매핑·`Retry-After`·입력 오류), `SaleTest`의 `admissionDecision`.
  - 통합(실제 MySQL 8.4.7 + Redis 7.4, 19개 추가, [OrderAcceptanceIntegrationTest](../../src/integrationTest/java/com/wooyongjeong/limitedsale/order/OrderAcceptanceIntegrationTest.java)): 정책 #10 항목 전부. 202와 `Location`·본문, 접수·아웃박스 행의 정확한 열과 내용(`{"schemaVersion":1,"acceptanceId":…,"saleId":…}`·키=판매 ID), 응용 컨텍스트를 닫고 새로 띄운 뒤에도 같은 접수 조회와 같은 키 재생, 아웃박스에 `BEFORE INSERT` 트리거로 오류를 주입하면 500이고 접수도 없으며 트리거를 지운 같은 키 재시도가 202, 종료·시작 전·중단·없는 이벤트·품절 표시가 `REJECTED` 저장과 409/404·아웃박스 없음·같은 키 재생·입력 변경 충돌, 같은 키 12개 동시 요청에서 202 한 번과 접수·아웃박스 한 행, 같은 키 재생 200과 이후 상태 반영, 남의 접수·없는 접수 404, 인증·CSRF·입력 오류 규칙, 429가 저장하지 않음(v1 주문이 쓴 `create` 카운터를 v2가 공유함도 확인), 스위치 끔(기본값, 인자 없음)에서 두 경로 404와 행 없음(v1은 정상), v1 동작 불변과 같은 UUID의 v1·v2 분리, 다른 연결이 판매 행에 `SELECT … FOR UPDATE`를 잡아도 접수·조회가 즉시 응답함(`findForUpdate`로 바꾸면 8초 클라이언트 타임아웃으로 실패함을 확인하고 되돌렸다), 상태별 CHECK(유효 4상태 통과, 필수 열 누락·소문자·뒤 공백·알 수 없는 값은 3819, FK 1452, 키 중복 1062, 아웃박스 JSON·attempts 3819, 미발행 인덱스의 열 순서).
  - 기존 `MySqlSchemaIntegrationTest`가 판매 마이그레이션 실행 수(4)와 이력 수(5)를 고정하고 있어 5와 6으로 고쳤다.
- 전체 검증: `JAVA_HOME=…/openjdk-25.0.1 PYTHONDONTWRITEBYTECODE=1 ./gradlew clean build` 성공, 7분 33초(3-2 검증 6분 58초 대비 약 35초 늘었고, 추가한 통합 테스트 클래스 하나가 컨테이너 둘을 띄우고 컨텍스트를 여러 번 시작한다). 단위 159개(24개 추가)·통합 218개(19개 추가)·mock-pg 단위 11개·통합 15개·Python 183개 모두 통과, 실패·건너뜀 없음(ArchUnit 규칙 추가 뒤 다시 실행한 결과이며, 같은 날 첫 실행에서는 `KafkaAbsentBrokerIntegrationTest`의 로그 줄 수 상한 단언 `< 100`이 정확히 100으로 한 번 실패했다. 단독 재실행 3회와 전체 재실행은 통과했다. 3-2부터 있는 경계값 근처의 시간 민감 단언이며 이번 변경과 무관하다). 통합 테스트 결과 XML 전체에서 `9092`가 든 줄은 0이다. `ArchitectureTest`와 `HttpContractStructureTest`, `ResponseCodeContractTest`도 통과했다. `ArchitectureTest`에 규칙 두 개를 추가했다. `..application..`은 `..infrastructure..`에 의존하지 않고, `..domain..`은 `..application..`·`..infrastructure..`에 의존하지 않는다(위반 사례 fixture로 규칙이 위반을 찾는지도 확인한다). 추가하면서 기존 위반은 나오지 않았다. 반대 방향인 `AdminInitializer`(infrastructure→application)는 이 규칙의 대상이 아니다.
- 편차
  - `SaleAdmission` 포트와 `Sale.admissionDecision`을 새로 만들었다(지침이 허용한 "sale.api를 통한 새 비잠금 읽기"). 기존 `SaleQuery.get`은 캐시와 앱 시계를 쓰고 같은 트랜잭션 안의 DB 시각이 아니어서 쓰지 않았다.
  - 상태 열은 1-2 규칙대로 `utf8mb4_0900_bin`이다. V4는 `utf8mb4_bin`+`BINARY` 비교다. 새 테이블은 규칙 문서와 V2를 따르고 CHECK는 `BINARY` 비교를 병행해 이중으로 대소문자·뒤 공백을 막는다.
  - 품절 표시 경로의 `accepted_at`·`finished_at`은 DB 시각이 아니라 앱 시계(마이크로초로 절삭)다. v1의 `rejectSoldOut`이 같은 방식이고 이 경로는 판매 행과 DB 판정을 아예 건너뛰기 때문이다. 접수 허용 경로와 판매 행 판정 거절은 판정 DB 시각이다.
  - `failure_reason`은 VARCHAR(64)이고 값 목록 CHECK가 없다. 실패 사유 코드는 3-6에서 정한다. `order_outbox.payload`는 JSON 타입 대신 `VARCHAR(2000)`+`JSON_VALID` CHECK다(엔티티가 문자열로 읽고 쓰므로).
  - `PURCHASE_LIMIT_EXCEEDED` 거절은 3-3에서 만들어지지 않지만(소비자 판단) 스키마·재생·응답 변환은 정책 #12의 사유 집합 때문에 지원한다.
  - 스위치가 꺼진 경로의 404는 컨트롤러 매핑이 없어 Spring Boot 기본 404 JSON(`{"timestamp":…,"status":404,"error":"Not Found","path":…}`)이며 `ApiResponse` 본문이 아니다. 로그인하지 않은 요청은 먼저 401(또는 CSRF 403)이다.
  - 위키의 FK 도식(`persistence-1`)은 갱신하지 않고 본문에 V6 테이블이 도식에 없다고 적었다.
  - 이 문서 맨 위 상태 줄과 3-3 체크리스트의 "구현 시작 지시"·"주 에이전트 확인" 항목은 바꾸지 않았다.
- 한계
  - 발행기와 소비자가 없으므로 `ORDER_CREATED`·`PROCESSING_FAILED` 접수는 실제로 만들어지지 않는다. 그 상태의 응답은 테스트가 행을 직접 갱신해 확인했다.
  - 아웃박스 행은 읽는 쪽이 없어 쌓이기만 한다(3-4 전).
  - 접수 판정은 잠금 없는 읽기다. 읽은 직후 중단·수정이 커밋되면 그 요청은 접수되고, 중단은 3-5 소비자가 처리 시점에 판단한다(정책 #5).
  - 재고·구매 한도는 접수 때 판단하지 않는다. 접수가 성공해도 나중에 거절될 수 있다.
  - 스위치는 인스턴스마다 시작 시 읽는다. 여러 인스턴스를 섞어 켠 상태, 여러 인스턴스의 동시 같은 키 경쟁, 접수 처리량·지연 측정은 하지 않았다. 동시 같은 키는 한 프로세스의 12개 스레드(DB 유니크 경쟁)로만 확인했다.
  - 재시작 검증은 같은 Redis·MySQL 위에서 응용 컨텍스트를 닫고 다시 띄운 것이며 MySQL 컨테이너 재시작은 아니다(커밋된 행은 MySQL이 보존한다).
- 주 에이전트 독립 검증: 커밋 10개의 공동 작성자 표기, V6 스키마, 접수 서비스·트랜잭션·컨트롤러, 잠금 없는 `JpaSaleAdmission`을 직접 읽었다. application 계층이 infrastructure의 토픽 상수를 import하던 것을 고치게 하고 계층 방향 ArchUnit 규칙을 더하게 했다(`30000be`). 3-2의 `KafkaAbsentBrokerIntegrationTest` 로그 줄 수 상한(100)이 정상 값(약 100)과 겹쳐 간헐 실패해 상한을 400으로 넓혔다(폭주는 분당 약 10만 줄이라 여전히 잡힌다). 그 뒤 직접 `./gradlew clean build`를 실행했다: 7분 30초, 판매 앱 단위 159·통합 218, Mock PG 11·15, 실패·오류 0, 통합 테스트 결과의 `9092` 줄 0.

## 3-4 확정 정책

2026-11-24 사용자와 선택지로 확정했다(4개 모두 추천안). #5~#10은 주 에이전트가 정한 세부다. 3-4는 아웃박스 행을 Kafka로 보내고 완료로 표시하는 데까지다. 소비(3-5)가 없으므로 접수는 여전히 `ACCEPTED`에 머문다. 3-1 #14를 구체화한다.

| # | 항목 | 확정 내용 | 이유 |
|---|---|---|---|
| 1 | 다중 인스턴스 | MySQL 이름 잠금(`GET_LOCK`)을 잡은 인스턴스 하나만 발행한다. 잠금을 잡은 연결이 끊기면 MySQL이 잠금을 풀고 다른 인스턴스가 이어받는다. 새 컬럼은 없다 | 판매 이벤트별 접수 순서(3-1 #4)를 지킨다. SKIP LOCKED·행 임대는 같은 이벤트의 접수가 두 인스턴스에서 섞여 나가 순서가 깨질 수 있다. 발행 처리량 상한이 인스턴스 하나라는 한계를 남긴다 |
| 2 | 실패 시 순서 | 한 묶음에서 첫 실패 행보다 앞의 성공 행만 완료로 표시하고, 다음 시도는 첫 실패 행부터 다시 보낸다. 그 뒤의 성공 행은 다시 보내져 중복 전달이 된다 | 장애가 없으면 순서를 지킨다. 장애 중에는 순서가 어긋날 수 있고 중복은 소비자 멱등성(3-1 #6)이 흡수한다. 이 한계를 문서에 남긴다 |
| 3 | 발행 주기 | 묶음(최대 100행)이 가득 차면 쉬지 않고 이어서 보내고, 비어 있으면 100ms 뒤 다시 본다 | 구조가 단순하고 접수~발행 지연이 수십 ms 수준이다. 실제 지연은 3-7·3-8에서 잰다 |
| 4 | 완료 행 | 행을 지우지 않고 `published_at`만 기록한다. 정리 작업은 3단계 범위 밖이다 | 3-7 관측·3-8 대조에 쓴다. 행이 계속 늘어난다는 한계를 남긴다 |
| 5 | 잠금 세부 | 잠금 이름 `limited-sale:order-outbox-publisher`, `GET_LOCK(name, 0)`. 잠금은 커넥션 풀에서 떼어 둔 전용 연결 하나가 잡고 있고, 묶음마다 그 연결로 `IS_USED_LOCK(name) = CONNECTION_ID()`를 확인한 뒤에만 보낸다. 확인이 실패하거나 연결이 끊기면 발행을 멈추고 연결을 닫은 뒤 대기자로 돌아간다. 잠금이 없는 인스턴스는 1초마다 다시 시도한다 | 프로세스가 죽으면(강제 종료 포함) 소켓이 닫혀 잠금이 바로 풀린다. 살아 있지만 멈춘 인스턴스(긴 GC 등)는 연결이 살아 있어 넘겨주지 않는다. 이 한계와 네트워크 단절 때 `wait_timeout`까지 걸릴 수 있음을 문서에 남긴다 |
| 6 | 묶음 처리 | `published_at IS NULL ORDER BY id LIMIT 100`을 잠금 없이 읽고(`ix_order_outbox_unpublished`), 모두 비동기로 보낸 뒤 결과를 기다린다(프로듀서 `delivery.timeout.ms` 5초). 완료 표시는 `UPDATE ... SET published_at = UTC_TIMESTAMP(6) WHERE id IN (...)`, 실패한 행은 `attempts`를 1 늘린다. 실패가 있으면 1초에서 최대 10초까지 두 배씩 늘려 기다린 뒤 다시 시도하고 성공하면 초기화한다 | 단일 발행자라 행 잠금이 필요 없다. 브로커가 돌아오면 최대 약 10초 안에 다시 보낸다 |
| 7 | 메시지 | 3-3에서 저장한 `topic`·`message_key`·`payload`를 그대로 보낸다. 헤더는 붙이지 않는다 | 3-3 #3의 계약을 그대로 쓴다 |
| 8 | 켜기 | 발행기는 `limited-sale.order.async.enabled=true`이고 `limited-sale.order.outbox-publisher.enabled`(기본 `true`)일 때만 돈다. 단위·통합 테스트의 기본은 `false`로 두고(3-2의 토픽 생성 스위치와 같은 방식) 발행기 테스트에서만 켠다. Kafka와 발행기 상태는 health에 넣지 않는다(3-2 #4) | v2를 끈 상태에서는 아웃박스가 쌓이지 않으므로 돌 필요가 없다. 발행기와 무관한 테스트가 브로커 없이 재접속 로그를 남기지 않게 한다 |
| 9 | 지표 | `order_outbox_publish_total{result=success\|failure}`(행 단위), `order_outbox_publisher_leader`(이 인스턴스가 발행자면 1). 미발행 적체·접수~발행 지연은 3-7에서 추가한다 | 발행자 교대와 실패를 지금 볼 수 있게 한다 |
| 10 | 완료 기준 | 실제 MySQL·Kafka 통합 테스트: 접수가 키·내용 그대로 토픽에 도착하고 `published_at`이 기록됨, 한 판매 이벤트의 접수가 접수 순서대로 도착함, 저장 뒤 발행 전 중단(발행기를 끈 채 저장 → 새 문맥에서 켬)에도 전송됨, 전송 뒤 완료 표시 전 중단(주입)에도 재전송되고 중복 1건이 생김을 확인함, 브로커 중단 중 행은 미발행으로 남고 `attempts`가 늘며 health는 UP, 브로커 재시작 뒤 앱 재시작 없이 전송됨, 앱 문맥 2개가 같은 DB를 쓸 때 발행자는 하나이고 정상 운영 중 같은 행이 두 번 가지 않음, 발행자 문맥을 닫으면 다른 문맥이 이어받아 남은 행을 보냄. 묶음 중간 실패 규칙(#2)은 가짜 전송기를 쓴 단위 테스트로 확인한다. `./gradlew clean build` 통과 | 기획 3-4 완료 기준. 중복 전달 가능성을 명시한다 |

### 3-4 구현과 검증

2026-11-24 워커가 구현했다. 아웃박스 행을 Kafka로 보내고 완료로 표시하는 데까지이며 소비(3-5)·재시도 정책(3-6)·적체 지표(3-7)는 없다. 접수는 여전히 `ACCEPTED`에 머문다. 새 마이그레이션과 새 컬럼은 없다(정책 #1: 이름 잠금).

- 구조
  - domain: 포트 네 개를 두었고 Kafka·JDBC 타입은 없다. `OutboxMessage`(id·topic·key·payload), `OutboxSender`(`CompletableFuture<Void> send`), `OutboxStore`(미발행 조회·완료 표시·실패 기록), `OutboxLeadership`(`tryAcquire`·`stillHeld`·`release`).
  - application: `OutboxBatch`가 정책 #2·#6의 한 묶음 규칙을 맡고 지표 `order.outbox.publish{result}`를 올린다. `OrderOutboxPublisher`(`SmartLifecycle`)가 백그라운드 스레드 `order-outbox-publisher`에서 잠금 대기(1초)·묶음 확인·유휴(100ms)·백오프(1초에서 두 배씩 최대 10초, 성공하면 초기화)를 돌리고 게이지 `order.outbox.publisher.leader`를 올린다. `OutboxBackoff`는 백오프 계산이다. 컨텍스트가 닫히면 `stop()`이 스레드를 멈추고 잠금을 풀 때까지 기다린다.
  - infrastructure: `MySqlOutboxLeadership`(커넥션 풀 밖에서 `DriverManager`로 연 전용 연결에 `GET_LOCK('limited-sale:order-outbox-publisher', 0)`, 묶음마다 `IS_USED_LOCK(name) = CONNECTION_ID()` 확인, 실패하면 연결을 닫음, `connectTimeout` 3초·`socketTimeout` 10초), `JdbcOutboxStore`(`JdbcTemplate`로 `published_at IS NULL ORDER BY id LIMIT 100` 읽기, `UPDATE … SET published_at = UTC_TIMESTAMP(6) WHERE published_at IS NULL AND id IN (…)`, `attempts = attempts + 1`), `KafkaOutboxSender`(기존 `KafkaTemplate`으로 저장된 topic·key·payload를 그대로, 헤더 없이).
  - 스위치(#8): 위 빈은 모두 `limited-sale.order.async.enabled=true`이고 `limited-sale.order.outbox-publisher.enabled=true`일 때만 만들어진다. 두 값 중 하나라도 없으면 만들어지지 않으므로, 이 앱의 `application.yml`을 가린 컨텍스트(Mock PG의 것)에서는 켜질 수 없다. `application.yml`에 `limited-sale.order.outbox-publisher.enabled: true`를 두었고, `build.gradle`의 `test`와 `integrationTest`는 시스템 속성 `-Dlimited-sale.order.outbox-publisher.enabled=false`를 준다(3-2의 토픽 생성 스위치와 같은 방식). 발행기 테스트는 명령행 인자로 다시 켠다. health에는 넣지 않았다.
  - 지표(#9): `order_outbox_publish_total{result="success"}`는 완료 표시까지 끝난 행 수, `{result="failure"}`는 전송이 실패한 행 수다. `order_outbox_publisher_leader`는 1이면 이 인스턴스가 발행자다.
- 정책에서 구체화한 부분과 편차
  - 이미 실패가 확인된 전송 뒤의 행은 그 묶음에서 보내지 않는다. 브로커가 없으면 `send`가 행마다 `max.block.ms`(5초) 동안 막히므로 100행을 모두 보내면 약 500초가 걸린다. 첫 실패 행 뒤의 행은 어차피 완료 표시되지 않으므로 보내지 않아도 규칙 #2의 결과는 같고 중복도 줄어든다. 보내지 않은 행은 `attempts`를 올리지 않는다. 실패가 `send` 반환 뒤에 비동기로 알려지면 정책대로 모두 보낸 뒤 결과를 본다(단위 테스트로 확인).
  - 완료 표시가 실패하면(예외) 그 묶음의 모든 행이 미발행으로 남아 다시 전송된다. 이때 `attempts`는 올리지 않고 성공 지표도 올리지 않는다.
  - 대기자도 첫 시도 뒤에는 전용 연결 하나를 열어 둔다(1초마다 `GET_LOCK`만 다시 묻는다). 발행기를 켠 인스턴스는 풀 밖에서 MySQL 연결을 하나 더 쓴다.
  - 접수 순서의 기준은 `order_outbox.id`(자동 증가)이다. 트랜잭션이 동시에 진행되면 id가 작은 행이 더 늦게 커밋돼 발행기가 큰 id를 먼저 읽고 보낼 수 있다(자동 증가 값은 INSERT 때 정해지고 가시성은 커밋 때 정해진다). 정책 #3·#6이 "id 순서"를 접수 순서로 정의했으므로 그대로 따랐고, 이 역전은 막지 않았다. 정상 상태에서 한 접수 요청이 다른 요청 사이에 끼어드는 간격은 접수 트랜잭션의 길이(밀리초)다. 필요하면 3-5에서 소비자가 접수 행의 `accepted_at`·id로 판단하게 하거나 발행기가 최근 행을 잠시 건너뛰게 할 수 있다.
- 테스트
  - 단위: [OutboxBatchTest](../../src/test/java/com/wooyongjeong/limitedsale/order/application/OutboxBatchTest.java) 10개(가짜 전송기·가짜 저장소). 모두 성공, 100행 상한, 첫 실패 행 앞만 완료 표시, 이미 실패한 전송 뒤 행 미전송, 뒤 행이 성공해도 늦게 실패하면 미표시·다음 시도에서 재전송(중복), 전송기 예외, 같은 행의 연속 실패가 `attempts`를 올림, 완료 표시 실패 시 전부 재전송, 백오프 1·2·4·8·10·10초와 초기화.
  - 통합(실제 MySQL 8.4.7 + Redis 7.4 + `apache/kafka:4.4.0` Testcontainers, 7개, [OrderOutboxPublisherIntegrationTest](../../src/integrationTest/java/com/wooyongjeong/limitedsale/order/OrderOutboxPublisherIntegrationTest.java)). 발행하지 않는 앱 하나가 HTTP로 접수를 받고, 각 테스트가 발행하는 앱을 같은 DB에 따로 띄운다. 모든 앱은 실제 `application.yml`을 쓴다.
    - 접수한 v2 주문이 토픽에 저장된 키·내용 그대로 도착하고 헤더가 없으며 `published_at`이 UTC로 기록되고 `attempts`는 0, 성공 지표 1, 게이지 1.
    - 한 판매 이벤트의 접수 12건이 접수 순서대로 한 파티션에 도착함.
    - 발행하는 앱 없이 저장한 행이 3초 뒤에도 미발행이고, 발행을 켠 새 앱이 보냄.
    - 전송은 성공하고 완료 표시만 실패하도록 주입한 경우(`OutboxStore` 빈을 감싸 첫 `markPublished`만 예외를 던지는 테스트 설정) 행이 1초 백오프 뒤 다시 전송돼 토픽에 정확히 2건(중복 1건)이고 이후 `published_at`이 기록되며 4초를 더 기다려도 늘지 않음.
    - 브로커를 `docker stop`하면 접수는 202를 유지하고 행은 미발행으로 남으며 `attempts`가 2 이상으로 늘고 실패 지표가 오르며 `/actuator/health`는 UP이고 게이지는 1을 유지함. 브로커를 다시 켜면 같은 앱(재시작 없음)이 그 행을 보냄.
    - 같은 DB에 앱 두 개: 게이지 합이 정확히 1이고 3초 뒤에도 같으며 `IS_FREE_LOCK`이 0. 접수 15건이 접수 순서대로 정확히 한 번씩 도착하고 두 앱의 성공 지표 합이 15. 발행자를 닫는 동안 접수한 5건을 남은 앱이 발행자가 되어 보내고(총 20건 순서·한 번씩), 마지막 앱을 닫으면 잠금이 풀리고(`IS_FREE_LOCK`=1) `order-outbox-publisher` 스레드가 남지 않음.
    - 발행자의 잠금 연결을 `KILL`하면 다음 묶음 전 확인(`IS_USED_LOCK`)이 실패해 발행을 멈추고, 새 연결로 잠금을 다시 잡은 뒤(연결 번호가 바뀜) 이후 접수를 보냄.
  - 위 일곱 개가 정책 #10의 통합 항목 전부다. `ArchitectureTest`(application → infrastructure 금지, domain의 Kafka 타입 금지)도 그대로 통과한다.
- 로그 폭주 점검: 발행기 테스트 결과에서 `localhost:9092` 줄은 0이고 `Rebootstrapping`은 브로커를 멈춘 구간에서 17줄이다(3-2의 재접속 간격 설정이 유효하다). 발행기가 없는 컨텍스트는 Kafka 클라이언트를 만들지 않는다(두 스위치가 모두 켜져야 빈이 생긴다).
- 전체 검증: `PYTHONDONTWRITEBYTECODE=1 ./gradlew clean build` 성공, 9분 12초(다른 프로세스 때문에 이 컴퓨터의 부하 평균이 10~19였다. 3-3 검증의 7분 33초와 직접 비교하지 않는다). 판매 앱 단위 169개(10개 추가)·통합 225개(7개 추가)·mock-pg 단위 11개·통합 15개·Python 183개 모두 통과, 실패·건너뜀 없음. 통합 테스트 결과 XML 전체에서 `9092`가 든 줄은 0이다. 발행기 통합 테스트 클래스는 약 80초(브로커 중단 테스트 38초 포함)다. 같은 날 첫 `clean build`는 `SaleIntegrationTest.stateFiltersUseConfirmedQuantitiesAndShareResponseTimeRules`의 HTTP 요청(`HttpClient.send`, 요청 시간 제한 없음)이 연결만 맺은 채 12분 넘게 응답을 받지 못해 멈추어 워커를 직접 종료했다(이 클래스는 발행기와 무관하고 스위치가 꺼진 컨텍스트다). 부하가 큰 컴퓨터에서 한 번 났고 같은 코드의 재실행에서는 재현되지 않았다.
- 한계·검증하지 못한 것
  - 프로세스 강제 종료(`kill -9`)와 네트워크 단절은 시험하지 않았다. 잠금 연결을 `KILL`로 끊는 경우와 컨텍스트를 닫는 경우만 확인했다. 연결이 정리되지 않는 단절은 MySQL의 `wait_timeout`(기본 8시간)까지 다른 인스턴스가 이어받지 못할 수 있다. 살아 있지만 멈춘 인스턴스(긴 GC 등)는 연결이 살아 있어 넘겨주지 않는다. 전용 연결의 `socketTimeout`은 10초라 응답하지 않는 연결을 확인 단계에서 실패로 본다.
  - 접수 순서는 정상 운영에서만 보장된다. 장애 중에는 첫 실패 행 뒤의 행이 먼저 도착하고 나중에 다시 도착할 수 있다(정책 #2). 위 id 커밋 순서 역전도 막지 않았다.
  - 발행 처리량·접수~발행 지연은 측정하지 않았다(3-7·3-8). 한 묶음이 가득 차 바로 이어지는 경로는 통합 테스트에서 100행 넘게 만들지 않았고 단위 테스트로만 확인했다.
  - 새 지표와 게이지를 Prometheus에서 수동으로 확인하지 않았다. 통합 테스트가 앱의 `MeterRegistry` 값을 읽었다.
  - 브로커는 한 개이고 복제 1이다. 완료 행을 지우지 않아 `order_outbox`가 계속 늘어난다(정책 #4).

- 주 에이전트 독립 검증(2026-11-24): 발행기·잠금·묶음·저장소·전송기 코드를 직접 읽고 정책 #1~#9와 맞는지 확인했다. 미발행 조회가 커서가 아니라 `published_at IS NULL`이라 커밋이 늦은 작은 id 행도 유실되지 않고 다음 묶음에 실린다(순서만 어긋날 수 있다). 완료 표시가 `published_at IS NULL` 조건을 함께 걸어 발행자 교대 중 겹친 전송에도 완료 시각을 덮어쓰지 않는다. `PYTHONDONTWRITEBYTECODE=1 ./gradlew clean build`를 직접 실행해 8분 46초에 성공했다(부하 평균 약 12). 판매 앱 단위 169·통합 225, mock-pg 11·15, Python 183, 실패 0. 통합 테스트 결과 XML에 `9092`가 든 파일은 0개다. 워커의 첫 빌드에서 멈춘 `SaleIntegrationTest`는 이번 실행에서 재현되지 않았다.

## 3-5 확정 정책

2026-11-24 사용자와 선택지로 확정했다(4개 모두 추천안). #5~#10은 주 에이전트가 정한 세부다. 3-5는 메시지를 소비해 점유·주문·결제 준비(READY)까지 만들고 접수 결과를 기록하는 데까지다. 제한된 재시도·`PROCESSING_FAILED` 저장·정체 재발행은 3-6이다. v2 스위치는 계속 기본 꺼짐이다.

| # | 항목 | 확정 내용 | 이유 |
|---|---|---|---|
| 1 | 처리 단위 | 메시지 하나에 DB 트랜잭션 하나. 커밋 뒤 오프셋을 확인한다(3-1 #6) | 기존 v1 주문 생성 흐름을 재사용하고 한 메시지의 실패가 다른 메시지로 번지지 않는다. 커밋 횟수가 처리량에 주는 영향은 3-8에서 잰다 |
| 2 | 선착순 기준 | (3-1 #3 수정) 선착순은 "아웃박스 행이 커밋되어 발행된 순서"다. 동시에 커밋된 접수는 id가 큰 쪽이 먼저 처리될 수 있으며 막지 않는다. 3-8에서 id 순서와 처리 순서가 어긋난 건수를 센다 | 어긋나는 폭은 접수 트랜잭션 길이(밀리초) 수준이다. 발행 지연으로 줄여도 0이 되지 않는다 |
| 3 | v2 주문의 요청 키 | 소비자가 만드는 `purchase_orders.request_key`에는 접수 ID(`order_acceptances.public_id`)를 넣는다. `order_request_results`는 쓰지 않는다. 스키마 변경은 없다 | v1·v2 키 공간 분리(3-1 #10)를 지킨다. 같은 접수가 두 번 처리되더라도 (회원, 요청 키) 유니크가 한 번 더 막는다 |
| 4 | 3-6 전 오류 처리 | 일시 오류(DB 연결·잠금 대기 시간 초과 등)는 같은 메시지를 1초 간격으로 계속 재시도한다(그 파티션은 멈추고 유실은 없다). 형식이 깨졌거나 접수 행이 없거나 메시지의 판매 ID가 접수 행과 다른 메시지는 경고 로그·지표를 남기고 확인 처리한다. 3-6에서 약 30초 제한과 `PROCESSING_FAILED`로 바꾼다 | 재시도해도 소용없는 메시지가 파티션을 막지 않게 한다 |
| 5 | 처리 흐름 | 메시지 해석 → 트랜잭션: 접수 행을 공개 ID로 `FOR UPDATE` 잠금 → 이미 최종 상태면 건너뜀(중복) → 판매 이벤트 잠금·판정(중단·한도·재고는 처리 시점, 기간은 저장된 `accepted_at`) → 거절이면 `REJECTED`(사유 `SALE_SOLD_OUT`·`PURCHASE_LIMIT_EXCEEDED`·`SALE_STOPPED`, 판매 이벤트가 사라졌으면 `SALE_NOT_FOUND`) → 허용이면 v1과 같은 점유·주문·READY 결제 생성, 접수 행을 `ORDER_CREATED`(주문 ID·완료 시각)로 갱신 → 커밋 → 오프셋 확인 | 3-1 #2·#5·#6. 판정 규칙은 v1의 `Sale` 도메인 규칙을 재사용하고 기간 기준 시각만 접수 시각으로 바꾼다 |
| 6 | 만료·품절 표시 | 주문 만료 5분은 주문을 만든 처리 시각부터 센다(3-1 #15). 점유 결과에 따른 2-7 품절 표시 기록·해제는 v1과 같은 경로를 그대로 탄다 | v1과 같은 재고·만료 규칙을 쓴다 |
| 7 | 리스너 | 토픽 `limited-sale.order-acceptances.v1`, 그룹 `limited-sale-order-consumer`, 인스턴스당 동시성 3, 수동 확인(3-2 #7). 소비자는 `limited-sale.order.async.enabled=true`이고 `limited-sale.order.consumer.enabled`(기본 `true`)일 때만 만든다. 테스트 기본은 `false`(3-4 발행기와 같은 방식)이고 소비자 테스트에서만 켠다. health에 넣지 않는다 | 발행기와 같은 켜기 규칙이다. 소비자와 무관한 테스트가 Kafka 클라이언트를 만들지 않게 한다 |
| 8 | 지표 | `order_acceptance_processed_total{result=order_created\|rejected\|duplicate\|skipped}`. 소비 지연(lag)은 3-2의 kafka-exporter, 접수~최종 지연은 3-7 | 중복 전달과 건너뜀을 지금 볼 수 있게 한다 |
| 9 | 변경 범위 | 기존 v1 `OrderService`·`OrderTransaction`의 동작은 바꾸지 않는다. 공통 부분을 꺼내 쓰는 것은 괜찮지만 v1 테스트는 그대로 통과해야 한다. 적용된 마이그레이션은 고치지 않는다 | v1은 3-8 비교 기준이다 |
| 10 | 완료 기준 | 실제 MySQL·Kafka 통합 테스트: 접수 → `ORDER_CREATED`·READY 결제가 생기고 v1 결제 API로 결제됨, 같은 메시지 두 번 전달(아웃박스 행 재발행)에도 주문 1개, DB 커밋 뒤 오프셋 확인 전 소비자 종료(주입)에도 재전달 뒤 주문 1개, 종료 전 접수가 종료 후 처리돼도 주문 생성, 접수 뒤 중단하면 `REJECTED`(`SALE_STOPPED`), 재고 소진 `SALE_SOLD_OUT`·v1과 v2를 합친 회원 한도 `PURCHASE_LIMIT_EXCEEDED`, 깨진 메시지·없는 접수는 건너뛰고 같은 파티션의 다음 메시지를 처리함, 판매 행을 다른 연결이 잠가 잠금 대기 시간 초과가 나도 재시도 끝에 처리됨, 만료 시각이 처리 시각 + 5분, 앱 두 개가 파티션을 나눠 가져도 메시지마다 한 번만 처리됨, 처리 뒤 재고·구매 수량 대조가 맞음. `./gradlew clean build` 통과 | 기획 3-5 완료 기준과 3-1 #2·#5·#6·#15 |

### 3-5 구현과 검증

2026-11-25 주 에이전트가 직접 구현했다(사용자 지시로 워커 없이). 소비해서 점유·주문·READY 결제를 만들고 접수를 끝내는 데까지이며 제한된 재시도·`PROCESSING_FAILED`·정체 재발행(3-6)과 처리 지연 지표(3-7)는 없다. 새 마이그레이션은 없다.

- 구조
  - sale: `SaleReservation.allocateAccepted(saleId, memberId, quantity, acceptedAt)`를 추가했다. `JpaSaleReservation`은 기존 `allocate`와 같은 비공개 메서드를 쓰고 판정만 `Sale.acceptedAllocationDecision(acceptedAt, …)`로 바꾼다. 이 메서드는 기존 `allocationDecision`을 기간 기준 시각만 접수 시각으로 넣어 호출한다(중단·한도·재고는 엔티티의 현재 값이다). 품절 표시 기록 경로는 v1과 같다. v1 `allocate`의 동작은 그대로다.
  - order.domain: `AcceptanceInProcess`(잠근 접수 행의 회원·판매·수량·상태·접수 시각), `OrderAcceptanceStore`에 `lockForProcessing`·`finishWithOrder`·`finishRejected`.
  - order.application: `OrderAcceptanceProcessor.process(acceptanceId, saleId)` 한 트랜잭션(정책 #5). 결과는 `ORDER_CREATED`·`REJECTED`·`DUPLICATE`·`SKIPPED`. 주문 요청 키는 접수 ID, 만료는 처리 시각 + `limited-sale.order.expiration`(5분). `order_request_results`는 쓰지 않는다. `AcceptanceProcessingMetrics`가 `order.acceptance.processed{result}`를 미리 등록한다. v1 `OrderService`·`OrderTransaction`은 바꾸지 않았다.
  - order.infrastructure: 접수 저장소의 `FOR UPDATE` 조회와 엔티티 상태 전이(`ACCEPTED`에서만 가능), `OrderAcceptanceListener`(`@KafkaListener`, 수동 확인, 메시지 해석), `OrderAcceptanceConsumerConfiguration`(`DefaultErrorHandler` + `FixedBackOff(1000ms, 무제한)`, Spring Boot가 리스너 컨테이너 팩토리에 넣는다). 두 빈은 `limited-sale.order.async.enabled`와 `limited-sale.order.consumer.enabled`가 모두 `true`일 때만 생긴다. `build.gradle`이 테스트에 `-Dlimited-sale.order.consumer.enabled=false`를 준다.
  - 대조 SQL: `ORDER_MISSING_REQUEST_RESULT`가 v2 주문(같은 회원·주문 ID·요청 키=접수 ID인 `ORDER_CREATED` 접수가 있는 주문)을 위반으로 보지 않게 했고, `ACCEPTANCE_VS_ORDER`(`ORDER_CREATED` 접수의 주문이 없거나 회원·판매·수량·요청 키가 다름)를 추가했다.
- 구현 중 발견해 고친 결함: `@KafkaListener(id = …)`는 기본으로 리스너 ID를 그룹 ID로 쓴다. 첫 실행의 소비자 그룹이 `limited-sale-order-consumer`가 아니라 `order-acceptance-consumer`였다(테스트 로그의 `groupId=`로 확인). `idIsGroup = false`로 설정의 그룹을 쓰게 하고, 통합 테스트가 그룹 ID를 단언한다.
- 테스트
  - 단위: `SaleTest`에 접수 시각 판정 1개(접수가 기간 안이면 종료 뒤에도 허용, 중단·한도·재고는 현재 값), [OrderAcceptanceListenerTest](../../src/test/java/com/wooyongjeong/limitedsale/order/infrastructure/messaging/OrderAcceptanceListenerTest.java) 2개(3-3 형식만 읽고 버전·타입·UUID·누락이 다르면 읽지 않음).
  - 통합 [OrderAcceptanceConsumerIntegrationTest](../../src/integrationTest/java/com/wooyongjeong/limitedsale/order/OrderAcceptanceConsumerIntegrationTest.java) 10개. 실제 MySQL 8.4.7(`innodb_lock_wait_timeout` 2초) + Redis + `apache/kafka:4.4.0` + Mock PG. 접수·발행·소비를 모두 켠 앱 하나가 실제 `application.yml`로 돈다. 중복·깨진 메시지는 테스트가 직접 토픽에 넣는다. 테스트마다 끝에 `tools/reconciliation/reconcile.sql`이 빈 결과여야 한다.
    - HTTP 접수 → `ORDER_CREATED`(상태 조회에 주문 ID), READY 결제, 만료 창이 정확히 5분, `order_request_results` 없음, v1 결제 API가 `PAYMENT_SUCCEEDED`로 결제하고 판매 수량 1.
    - 같은 메시지를 두 번 더 넣으면 `duplicate` 2, 주문 1개, 점유 1.
    - 커밋 뒤 확인 전 실패(지표 기록 단계에서 한 번 예외를 주입) → 같은 메시지가 다시 와서 `duplicate` 1, 주문 1개.
    - 1분 전에 끝난 판매에 10분 전(기간 안) 접수 → 주문 생성.
    - 중단된 판매의 접수 → `REJECTED`·`SALE_STOPPED`, 주문 없음, 상태 조회에도 사유.
    - 재고 2에 접수 3 → 앞의 둘 `ORDER_CREATED`, 셋째 `SALE_SOLD_OUT`.
    - 회원 한도 1에서 v1 주문 뒤 v2 접수 → `PURCHASE_LIMIT_EXCEEDED`.
    - 같은 파티션에 깨진 JSON, 버전 2, 없는 접수, 판매 ID가 다른 접수, 정상 접수 순서로 넣으면 앞의 넷은 `skipped`이고 정상 접수는 주문이 됨.
    - 다른 연결이 판매 행을 `FOR UPDATE`로 잡은 7초 동안 접수는 `ACCEPTED`로 남고(잠금 대기 시간 초과 2회 로그), 놓으면 주문 1개.
    - 앱 두 개가 같은 그룹으로 파티션을 나눠 받은 뒤, 두 번째 앱이 가진 파티션의 판매 셋과 아닌 판매 셋에 2건씩 접수 → 12건 모두 주문 1개씩, 두 앱의 `order_created` 합 12, 두 번째 앱 6 이상.
  - 클래스 실행 약 40초. 이 클래스 결과에 `9092` 줄은 없다.
- 전체 검증: `PYTHONDONTWRITEBYTECODE=1 ./gradlew clean build` 성공, 9분 37초(부하 평균 6~12). 판매 앱 단위 172개(3개 추가)·통합 235개(10개 추가)·mock-pg 11·15개·Python 183개, 실패·오류 0. 통합 테스트 결과 XML 중 `9092`가 든 파일은 0개다.
- 한계·검증하지 못한 것
  - 일시 오류는 끝없이 재시도한다. DB가 오래 멈추면 그 파티션의 모든 접수가 멈춘다(3-6에서 제한).
  - 프로세스 강제 종료로 커밋과 확인 사이에 멈추는 경우는 예외 주입으로만 재현했다. 리밸런스 중 재전달은 두 앱 테스트에서 일어날 수 있지만 건수를 따로 세지 않았다.
  - 처리량·접수~최종 지연은 재지 않았다(3-7·3-8). 메시지마다 커밋하는 비용도 3-8에서 본다.
  - id 순서와 처리 순서의 역전(정책 #2)은 막지 않았고 세지도 않았다(3-8).
  - Prometheus에서 새 지표를 수동으로 보지 않았다. 통합 테스트가 `MeterRegistry`를 읽었다.

## 3-6 확정 정책

2026-11-25 사용자와 선택지로 확정했다(4개 모두 추천안). #5~#10은 주 에이전트가 정한 세부다. 3-1 #7·#8을 구체화하고, #1은 3-1 #8의 정체 기준을 고친다.

| # | 항목 | 확정 내용 | 이유 |
|---|---|---|---|
| 1 | 정체 기준 | (3-1 #8 수정) 발행 뒤 60초가 지났고 소비자 그룹의 커밋 오프셋이 그 메시지를 이미 지났는데 여전히 `ACCEPTED`인 접수를 정체로 본다. 발행 뒤 5분이 지난 `ACCEPTED` 접수는 오프셋과 관계없이 정체로 본다. 발행기가 보낸 메시지의 파티션·오프셋을 아웃박스 행에 기록한다 | 한 판매에 접수가 몰려 정상 대기가 60초를 넘을 때 대량 재발행(소비자 부하)을 막는다. 5분은 3-8 검증 기한과 같은 안전망이다 |
| 2 | 재발행 방식 | 같은 토픽·키·내용의 새 아웃박스 행을 넣고 발행기가 평소처럼 보낸다. 아웃박스 행에 접수 ID를 둔다 | 처음 발행 이력이 남고 접수별 재발행 횟수를 센다. 접수 행을 갱신하지 않는다(3-3 #2) |
| 3 | 실패 분류 | 알려진 일시 오류(DB 연결 실패·잠금 대기 시간 초과·데드락·쿼리 시간 초과)만 재시도한다. 그 밖의 예외는 바로 `PROCESSING_FAILED`로 저장한다 | 재시도해도 같은 결함이 파티션을 오래 막지 않게 한다 |
| 4 | DB 장애 | 재시도 예산이 끝나 실패를 저장하려 했는데 그것도 실패하면 확인 처리하지 않고 5초 간격으로 처리 자체를 다시 시도한다. DB가 돌아오면 실패가 아니라 정상 처리된다. 그동안 그 파티션은 멈추고 지표·로그로 알린다 | 3-1 #7의 "소비를 멈추고 재개 때 다시 처리"를 자동 복구로 구현한다 |
| 5 | 재시도 값 | 같은 메시지를 제자리에서 지수 백오프(0.5초에서 두 배씩, 한 번 최대 8초)로 재시도하고, 첫 실패부터 30초가 지나면 그만둔다. 끝나면 새 트랜잭션에서 접수를 `PROCESSING_FAILED`로 저장하고 확인 처리한다. 접수 행이 이미 최종 상태면 저장하지 않는다 | 3-1 #7. 실패한 처리 트랜잭션은 롤백되므로 재고·주문은 변하지 않는다 |
| 6 | 실패 사유 코드 | `TRANSIENT_RETRIES_EXHAUSTED`(일시 오류 30초 초과), `PROCESSING_ERROR`(일시 오류가 아닌 예외), `STALLED`(재발행 3회 뒤에도 정체). 상태 조회의 `failureReason`으로 보인다. 사용자는 새 요청 키로 다시 주문할 수 있다 | 최종 실패 사유를 추적한다(기획 3-6 완료 기준) |
| 7 | 정체 탐지 | 발행자(3-4의 이름 잠금 보유 인스턴스)가 10초마다 탐지한다. 접수마다 가장 최근 아웃박스 행으로 판단하고, 재발행은 접수당 최대 3회다. 3회 재발행 뒤에도 정체면 접수 행을 잠그고 아직 `ACCEPTED`일 때만 `PROCESSING_FAILED`(`STALLED`)로 저장한다 | 탐지도 인스턴스 하나만 해서 같은 접수를 두 번 재발행하지 않는다. 끝없는 재발행을 막는다 |
| 8 | 스키마 | V7: `order_outbox`에 `acceptance_id`(접수 FK, 기존 행은 내용의 접수 ID로 채움)·`partition_no`·`record_offset`(발행 결과, 미발행이면 NULL)을 추가하고 `(acceptance_id, id)` 인덱스를 둔다. 적용된 V1~V6은 고치지 않는다 | #1·#2·#7에 필요하다 |
| 9 | 지표·공개 | `order_acceptance_failed_total{reason}`, `order_acceptance_retries_total`, `order_acceptance_republished_total`, `order_acceptance_stalled`(마지막 탐지의 정체 수). 3-6 완료와 함께 `limited-sale.order.async.enabled` 기본값을 `true`로 바꾼다(3-3 #4). 테스트는 지금처럼 발행기·소비자를 기본으로 끈다 | 기획: 3-6까지 끝나야 v2가 중간 결과가 아니다 |
| 10 | 완료 기준 | 실제 MySQL·Kafka 통합 테스트: 일시 오류가 30초 안에 풀리면 정상 처리, 30초를 넘으면 `PROCESSING_FAILED`(`TRANSIENT_RETRIES_EXHAUSTED`)이고 같은 파티션의 다음 메시지가 처리됨(다른 메시지를 무기한 막지 않음), 일시 오류가 아닌 예외는 즉시 `PROCESSING_ERROR`, 실패 저장도 안 되는 DB 장애 뒤 DB가 돌아오면 정상 처리, 실패한 접수는 재고·주문을 남기지 않음, 소비자가 지나친 `ACCEPTED` 접수는 60초 뒤 재발행되어 주문 1개, 아직 차례가 오지 않은 접수는 60초가 지나도 재발행되지 않음, 5분 안전망, 재발행 3회 뒤 `STALLED`, 재처리·재발행에도 주문 중복 없음(대조 SQL), 같은 판매의 순서 정책과 충돌 없음(실패한 메시지 뒤 메시지가 실패 저장 뒤에 처리됨). 시간이 긴 기준(60초·5분·30초)은 테스트에서 설정으로 줄인다. `./gradlew clean build` 통과 | 기획 3-6 완료 기준 |

### 3-6 구현과 검증

2026-11-25 주 에이전트가 직접 구현했다(사용자 지시). 재시도·실패 저장·DB 장애 대기·정체 재발행과 v2 기본 켜기까지이며 처리 지연·적체 지표(3-7)와 종단 간 비교(3-8)는 없다.

- 구조
  - 스키마 [V7](../../src/main/resources/db/migration/V7__order_outbox_tracking.sql): `order_outbox`에 `acceptance_id`(기존 행은 내용의 접수 ID로 채운 뒤 NOT NULL, FK)·`partition_no`·`record_offset`, CHECK(둘 다 NULL이거나 둘 다 있고 발행 시각도 있음), `(acceptance_id, id)` 인덱스. `order_acceptances`에 `(status, id)` 인덱스. 적용된 V1~V6은 고치지 않았다.
  - 발행 위치: `OutboxSender`가 `OutboxPosition(partition, offset)`을 돌려주고 `OutboxStore.markPublished`가 행마다 위치를 기록한다. 3-4 묶음 규칙은 그대로다.
  - 재시도(#3~#5): `OrderAcceptanceConsumerConfiguration`의 `DefaultErrorHandler`. `TransientFailures`가 원인 사슬에서 Spring의 일시 DB 예외·연결 실패·트랜잭션 생성 실패·SQL 일시 예외와 MySQL 1205·1213·3024·SQLState 08xxx·40001을 일시 오류로 본다. 일시 오류는 `WallClockBackOff`(0.5초부터 두 배, 최대 8초, 첫 실패부터 실제 시간 30초)로, 그 밖은 재시도 없이 복구 단계로 간다. 복구 단계는 `OrderAcceptanceFailures.fail`(새 트랜잭션, 접수 잠금, `ACCEPTED`일 때만 저장)이다. 이것도 실패하면 `AcceptanceRetryState`에 표시하고 예외를 다시 던진다. 컨테이너는 그 메시지의 재시도 상태를 새로 만들고, 백오프 함수가 표시된 메시지에 5초 고정 무제한 간격을 준다. 성공하면 표시를 지운다. 기간 값은 `limited-sale.order.consumer.retry-budget`·`outage-retry-interval`.
  - 정체(#1·#2·#7): `OrderStallDetector`가 발행기 스레드에서 `limited-sale.order.stall.scan-interval`(10초)마다 돈다. `JdbcStallStore`가 `ACCEPTED` 접수의 마지막 아웃박스 행(발행 60초 경과)을 최대 500개 읽는다. `KafkaConsumerProgress`가 한 번 만든 관리 클라이언트로 그룹의 커밋 오프셋을 읽는다. 오프셋을 지났거나 5분이 지난 접수는 같은 내용의 새 아웃박스 행을 넣고, 행이 이미 4개(재발행 3회)면 `STALLED`로 끝낸다.
  - 실패 사유(#6): `ProcessingFailure`(`TRANSIENT_RETRIES_EXHAUSTED`·`PROCESSING_ERROR`·`STALLED`)를 `failure_reason`에 저장하고 상태 조회 `failureReason`으로 보인다.
  - 지표(#9): `order.acceptance.failed{reason}`, `order.acceptance.retries`(실패한 처리 시도), `order.acceptance.republished`, `order.acceptance.stalled`(게이지). 관측 테스트의 라벨 허용 목록에 `reason`을 더했다(값 3개, 식별자 아님).
  - 공개(#9): 배포 `application.yml`에 `limited-sale.order.async.enabled: true`. 테스트는 발행기·소비자 스위치를 계속 시스템 속성으로 끈다. 3-3 테스트는 스위치를 켜고 끄는 값을 항상 명시한다.
- 구현 중 발견해 고친 결함
  - V7의 위치 CHECK가 처음에는 `partition_no >= 0 AND record_offset >= 0 AND …`였다. 값 하나가 NULL이면 조건이 UNKNOWN이 되고, CHECK는 UNKNOWN을 통과시킨다. 위치가 반만 기록된 행이 들어갔다(제약 테스트로 발견). `IS NOT NULL`을 명시했다.
  - 정체 탐지가 처음에는 확인할 때마다 관리 클라이언트를 새로 만들었다. 그러면 Kafka 클라이언트가 설정 전체(수십 줄)를 매번 INFO로 남긴다. 클라이언트 하나를 유지하고 컨텍스트 종료 때 닫게 바꿨다.
  - "실패 저장도 안 되는 DB 장애" 테스트의 첫 버전은 앱 기동 직후 리밸런스로 재시도 상태가 초기화되어, 그 경로를 지나지 않고도 통과했다. 소비자 세 개의 파티션 배정이 끝난 뒤 시작하도록 바꾸고, `Could not store the failure` 로그가 실제로 나왔는지를 단언한다.
  - 3-5의 "커밋 뒤 확인 전 실패" 테스트는 일시 오류가 아닌 예외를 주입하고 있었다. 이제 그런 예외는 재전달 없이 실패 처리된다. 이미 끝난 접수라 저장할 것은 없고 확인만 된다. 연결이 끊긴 경우처럼 일시 오류를 주입하게 바꿨다.
- 테스트
  - 단위: [WallClockBackOffTest](../../src/test/java/com/wooyongjeong/limitedsale/order/infrastructure/messaging/WallClockBackOffTest.java) 3개(대기 0.5·1·2·4·8·8·6.5초 뒤 중단, 시도 시간도 예산에 포함, 시작마다 새 예산), [TransientFailuresTest](../../src/test/java/com/wooyongjeong/limitedsale/order/infrastructure/messaging/TransientFailuresTest.java) 2개, [OrderStallDetectorTest](../../src/test/java/com/wooyongjeong/limitedsale/order/application/OrderStallDetectorTest.java) 5개(지난 메시지만 재발행, 앞선 메시지·다른 파티션·위치 없음은 기한까지 대기, 오프셋을 모르면 기한만, 재발행 3회 뒤 `STALLED`, 게이지 초기화). `OutboxBatchTest`는 위치를 돌려주는 가짜 전송기로 바꿨다.
  - 통합 [OrderAcceptanceRecoveryIntegrationTest](../../src/integrationTest/java/com/wooyongjeong/limitedsale/order/OrderAcceptanceRecoveryIntegrationTest.java) 7개. 실제 MySQL(`innodb_lock_wait_timeout` 2초) + Redis + `apache/kafka:4.4.0`이고, 예산 6초·DB 장애 간격 1초·정체 3초·기한 20초·탐지 1초로 줄였다. 테스트마다 끝에 대조 SQL이 비어 있어야 한다.
    - 판매 행 잠금이 한 번의 잠금 대기 안에 풀리면 정상 주문.
    - 판매 A를 계속 잠근 채 A 접수와 같은 파티션의 판매 B 접수를 보내면, A는 `TRANSIENT_RETRIES_EXHAUSTED`로 끝나고(상태 조회에도 표시, 주문·점유 없음) 잠금이 남아 있는 동안 B가 주문이 되며 완료 시각이 A 뒤다.
    - 결제 준비에서 일시 오류가 아닌 예외를 주입하면 5초 안에 `PROCESSING_ERROR`, 시도 실패 1회, 주문·점유 없음.
    - 다른 연결이 접수 행을 잠그면 처리와 실패 저장이 모두 막혀 `Could not store the failure` 경고가 나온다. 4초 더 `ACCEPTED`로 남고, 잠금을 놓으면 실패가 아니라 `ORDER_CREATED`(실패 지표 그대로).
    - 소비자가 이미 지난 오프셋에 발행된 것처럼 꾸민 `ACCEPTED` 접수는 재발행되어 아웃박스 행 2개, 주문 1개.
    - 소비자가 아직 닿지 않은 오프셋의 접수는 발행 10초 뒤 5초를 더 기다려도 재발행되지 않고, 20초 기한이 지나면 재발행되어 주문이 된다.
    - 판매 ID가 다른 내용으로만 발행되는 접수는 매번 건너뛰어져 재발행 3회(행 4개) 뒤 `STALLED`.
  - [MySqlSchemaIntegrationTest](../../src/integrationTest/java/com/wooyongjeong/limitedsale/MySqlSchemaIntegrationTest.java): V6에서 데이터가 있는 아웃박스 행 2개를 V7로 올리면 각 행이 자기 접수를 가리킨다. 마이그레이션 수를 맞췄다. `OrderAcceptanceIntegrationTest`에 V7 제약(접수 ID 없음 1364, 없는 접수 1452, 위치 CHECK 4가지)을 더했다.
- 전체 검증: `PYTHONDONTWRITEBYTECODE=1 ./gradlew clean build` 성공, 10분 33초(부하 평균 약 8). 판매 앱 단위 182개(10개 추가)·통합 243개(8개 추가)·mock-pg 11·15개·Python 183개, 실패·오류 0. 통합 테스트 결과 XML 중 `9092`가 든 파일은 0개다. 같은 변경의 첫 전체 빌드에서는 위의 테스트 결함 4건이 실패했고 고친 뒤 통과했다.
- 한계·검증하지 못한 것
  - DB 장애는 행 잠금으로만 흉내 냈다. MySQL 프로세스를 멈추거나 네트워크를 끊는 시험은 하지 않았다. 실제 연결 장애에서는 Hikari `connectionTimeout`(30초) 동안 한 시도가 막힐 수 있다.
  - DB 장애 대기 중인 메시지는 DB가 돌아온 뒤 일시 오류가 아닌 예외가 나도 5초 간격 재시도를 계속한다. 컨테이너가 재시도 방식을 메시지마다 처음 실패할 때만 정하기 때문이다. 정상 처리나 재시작이 아니면 풀리지 않는다.
  - 30초 예산은 시도 사이의 실제 시간으로 잰다. 잠금 대기 하나(운영 기본 50초)가 예산보다 길면 그 시도가 끝날 때까지 파티션이 더 막힌다.
  - 5분 기한은 적체와 무관하게 재발행한다. 5분 넘게 밀리는 부하에서는 중복 메시지가 생긴다(주문은 하나다).
  - 정체 탐지는 한 번에 접수 500개까지 본다. 처리량·지연은 3-7·3-8에서 잰다. Prometheus에서 새 지표를 직접 보지 않았다.

## 3-7 확정 정책

2026-11-25 사용자와 선택지로 확정했다(4개 모두 추천안). #5~#10은 주 에이전트가 정한 세부다. API 응답 지연(기존 `http_server_requests_seconds{uri="/api/v2/orders"}`)과 주문 완료 지연을 따로 볼 수 있게 한다.

| # | 항목 | 확정 내용 | 이유 |
|---|---|---|---|
| 1 | 완료 지연 | 접수를 끝낸 트랜잭션이 커밋된 뒤 `finished_at − accepted_at`(DB 시각)을 히스토그램 `order_acceptance_completion_seconds{result}`로 기록한다. 발행 지연(아웃박스 행 생성~발행 확인)은 `order_outbox_publish_delay_seconds`로 따로 기록한다 | 접수~최종 지연의 p50·p95·p99를 실시간으로 보고, 발행과 소비 중 어느 구간이 느린지 나눈다 |
| 2 | 적체 | 기존 `order_unfinished`처럼 인스턴스마다 10초마다 DB를 세어 캐시한 게이지: 미발행 아웃박스 행 수·가장 오래된 미발행 행의 나이, `ACCEPTED` 접수 수·가장 오래된 접수의 나이. 인스턴스마다 같은 값이라 대시보드는 `max()`로 본다. 소비 지연(lag)은 kafka-exporter의 `kafka_consumergroup_lag`를 쓴다 | 스크레이프마다 DB를 읽지 않는다. 발행자 교대와 무관하게 시계열이 이어진다 |
| 3 | 요청 ID 추적 | V8로 `order_acceptances.request_id`를 추가해 접수 때 HTTP 요청 ID를 저장한다. 소비자 처리·실패 저장·정체 재발행이 그 값을 로그의 `[요청ID]`(MDC `requestId`)로 쓴다. 메시지 계약은 바꾸지 않는다 | 로그를 요청 ID 하나로 접수부터 최종 상태까지 찾는다 |
| 4 | 검증 | 통합 테스트로 소비자를 멈춘 동안 적체 게이지가 늘고 재개 뒤 0으로 돌아오는지, 지연 히스토그램이 쌓이는지 단언한다. Compose(MySQL·Redis·Kafka·`monitoring` 프로필)와 판매 앱으로 소비자를 끈 채 접수를 쌓은 뒤 켜는 과정을 Prometheus 쿼리 결과로 문서에 기록한다. Grafana 대시보드에 v2 패널을 더한다 | 기획 3-7 완료 기준 |
| 5 | 완료 지연 세부 | `result`는 `order_created`·`rejected`·`processing_failed`. 중복·건너뜀은 기록하지 않는다. 실패 저장(재시도 소진·즉시 실패·`STALLED`)도 같은 히스토그램에 기록한다. 히스토그램 버킷을 내보내고(`percentiles-histogram`) 최대 기대값은 10분이다 | 최종 상태 종류별 분포를 본다 |
| 6 | 발행 지연 세부 | 발행기가 읽을 때의 행 나이(DB 시각)에 전송~확인 시간을 더해 행마다 기록한다. 재발행 행도 같은 지표에 들어간다 | 읽기 쿼리 하나로 계산한다 |
| 7 | 적체 세부 | 게이지 이름 `order_outbox_unpublished`, `order_outbox_oldest_unpublished_seconds`, `order_acceptance_accepted`, `order_acceptance_oldest_accepted_seconds`. 없으면 0, 조회 실패면 NaN(기존 게이지와 같은 규칙). 갱신 간격은 기존 `limited-sale.observability.backlog-interval`(10초) | 기존 규칙을 따른다 |
| 8 | 요청 ID 세부 | 컬럼은 `VARCHAR(64)` NULL(V8 이전 접수는 NULL). 소비자는 처리 전에 접수 행의 요청 ID를 읽어 MDC에 넣고 다음 메시지에서 바꾼다. 처리 결과마다 DEBUG 로그 한 줄(접수 ID·결과)을 남기고, 실패·건너뜀·재발행은 기존 WARN에 요청 ID가 붙는다. 상태 조회 응답에는 넣지 않는다 | 요청 ID는 진단용이다 |
| 9 | 대시보드 | `infra/grafana/dashboards/limited-sale.json`에 v2 행을 더한다: v2 접수 API p95, 완료 지연 p50·p95(결과별), 발행 지연 p95, 적체 게이지 4개, 소비 지연, 실패·재시도·재발행 비율 | 한 화면에서 API 지연과 완료 지연을 나란히 본다 |
| 10 | 완료 기준 | 통합 테스트: 소비자를 멈춘 동안 `order_acceptance_accepted`와 가장 오래된 나이가 늘고 재개 뒤 0이 됨, 발행기를 멈춘 동안 미발행 게이지가 늘고 재개 뒤 0이 됨, 처리 뒤 완료·발행 지연 히스토그램 건수가 접수 수와 같음, 접수의 요청 ID가 저장되고 소비자 로그에 같은 요청 ID가 찍힘. 수동: Prometheus 쿼리로 적체 증가·감소와 지연 분위수를 기록. 관측 테스트의 라벨 허용 목록 유지(식별자 라벨 없음). `./gradlew clean build` 통과 | 기획 3-7 완료 기준 |

## 소단계 실행 체크리스트

각 소단계는 아래 순서로 진행한다. 정책 동의와 구현 시작 지시는 별개다. 구현은 별도 서브에이전트(워커)가 맡고 주 에이전트가 검증한다. 사용할 모델이 정해지지 않았다면 사용자에게 묻는다. 다음 소단계로 자동 진행하지 않는다. 완료 근거와 남은 문제는 해당 소단계 아래에 기록한다.

### 3-1. 비동기 계약과 전달 방식

- [x] 사용자와 미정 기획·정책·설계 선택 및 완료 기준 확정(2026-11-24, 선택지 8개 모두 추천안)
- [x] 합의 내용과 선택 이유, 후속 구현 범위를 문서에 기록(위 "3-1 확정 정책")
- [x] 주 에이전트가 문서 일관성과 남은 결정 사항을 검토하고 결과 보고

### 3-2. Kafka 실행과 연결

- [x] 현재 소단계의 미정 사항 확인·합의 및 범위·완료 기준 확정(2026-11-24, 위 "3-2 확정 정책")
- [x] 사용자로부터 이 소단계의 구현 시작 지시 받음(선택지 "지금 시작")
- [x] 지정 워커의 해당 범위 구현과 검증 결과 수령(2026-11-24, 위 "3-2 구현과 검증")
- [x] 주 에이전트가 변경·산출물·검증 근거를 확인하고 결과 보고(위 "3-2 구현과 검증")

### 3-3. 영속 접수와 상태 조회

- [x] 현재 소단계의 미정 사항 확인·합의 및 범위·완료 기준 확정(2026-11-24, 위 "3-3 확정 정책")
- [x] 사용자로부터 이 소단계의 구현 시작 지시 받음(선택지 "지금 시작")
- [x] 지정 워커의 해당 범위 구현과 검증 결과 수령(2026-11-24, 위 "3-3 구현과 검증")
- [x] 주 에이전트가 변경·산출물·검증 근거를 확인하고 결과 보고(위 "3-3 구현과 검증")

### 3-4. 신뢰할 수 있는 메시지 전달

- [x] 현재 소단계의 미정 사항 확인·합의 및 범위·완료 기준 확정(2026-11-24, 위 "3-4 확정 정책")
- [x] 사용자로부터 이 소단계의 구현 시작 지시 받음(2026-11-24)
- [x] 지정 워커의 해당 범위 구현과 검증 결과 수령(2026-11-24, 위 "3-4 구현과 검증")
- [x] 주 에이전트가 변경·산출물·검증 근거를 확인하고 결과 보고

### 3-5. 소비와 주문 확정

- [x] 현재 소단계의 미정 사항 확인·합의 및 범위·완료 기준 확정(2026-11-24, 위 "3-5 확정 정책")
- [x] 사용자로부터 이 소단계의 구현 시작 지시 받음(2026-11-24, 주 에이전트가 직접 구현하도록 지시)
- [x] 지정 워커의 해당 범위 구현과 검증 결과 수령(이번에는 주 에이전트가 직접 구현, 위 "3-5 구현과 검증")
- [x] 주 에이전트가 변경·산출물·검증 근거를 확인하고 결과 보고(2026-11-25)

### 3-6. 재시도와 실패 복구

- [x] 현재 소단계의 미정 사항 확인·합의 및 범위·완료 기준 확정(2026-11-25, 위 "3-6 확정 정책")
- [x] 사용자로부터 이 소단계의 구현 시작 지시 받음(2026-11-25, 주 에이전트가 직접 구현)
- [x] 지정 워커의 해당 범위 구현과 검증 결과 수령(주 에이전트가 직접 구현, 위 "3-6 구현과 검증")
- [x] 주 에이전트가 변경·산출물·검증 근거를 확인하고 결과 보고(2026-11-25)

### 3-7. 비동기 관측

- [x] 현재 소단계의 미정 사항 확인·합의 및 범위·완료 기준 확정(2026-11-25, 위 "3-7 확정 정책")
- [ ] 사용자로부터 이 소단계의 구현 시작 지시 받음
- [ ] 지정 워커의 해당 범위 구현과 검증 결과 수령
- [ ] 주 에이전트가 변경·산출물·검증 근거를 확인하고 결과 보고

### 3-8. 종단 간 검증과 비교

- [ ] 현재 소단계의 미정 사항 확인·합의 및 범위·완료 기준 확정
- [ ] 사용자로부터 이 소단계의 구현 시작 지시 받음
- [ ] 지정 워커의 해당 범위 구현과 검증 결과 수령
- [ ] 주 에이전트가 변경·산출물·검증 근거를 확인하고 결과 보고

## 검증 기준

접수 응답 지연과 최종 주문·결제 완료 지연을 구분한다. 메시지 중복 전달, 브로커·소비자 중단, DB 커밋 직후 소비자 종료에서도 중복 결제와 초과 판매가 없어야 한다. 종료 전 접수 후 종료 후 처리, 접수 요청과 최종 결과의 대조, 사전에 정한 검증 기한 내 처리 완료를 확인한다. 단일 로컬 브로커 결과를 고가용성 검증으로 설명하지 않는다.

## 미정 사항

3-1에서 전달 방식·접수 보장 시점·키 관계·순서·재시도·종료 후 처리·API를 확정했다. 남은 항목:

- 3-8의 세부 실험 조건과 지표

(3-4의 발행기 중복 방지는 "3-4 확정 정책", 3-6의 실패 사유 코드와 백오프 값은 "3-6 확정 정책"에서 확정했다.)
