# 1-2 MySQL 스키마·영속 매핑·패키지 경계

작성일: 2026-11-10

상태: 1-2 구현 및 주 에이전트 독립 검증 완료. 후속 업무 API는 아직 구현하지 않았다. [1단계 정책](01-mysql-order.md)과 [1-1 기본 계약](01-contracts.md)을 구체화한다.

## 이번에 확정한 선택

- 같은 판매 스키마의 도메인 간 ID에도 물리 FK를 적용한다. 삭제·키 갱신은 RESTRICT이며 cascade를 사용하지 않는다.
- JPA에서는 다른 도메인의 엔티티 연관관계 대신 ID 필드를 사용한다. 물리 FK와 Java 패키지 의존성을 구분한다.
- Mock PG와 판매 스키마 사이에는 FK·직접 SQL 접근이 없다.
- UUID는 BINARY(16), API는 UUID 문자열이다. 영속 계층에서 UUID를 일관된 16바이트 순서로 변환하며 시간 기반 byte swap은 하지 않는다.
- 상태·권한은 VARCHAR와 CHECK, Java에서는 문자열 enum 이름을 명시적으로 매핑한다. ordinal과 MySQL 네이티브 ENUM을 사용하지 않는다.
- 전체 복구 예약은 purchase_orders가 소유한다. 다음 실행 시각·임대 토큰·임대 만료를 이곳에 두고 payments는 PG 처리 상태를 보관한다.

결제 성공만 저장되고 주문 확정이 실패한 경우에도 주문 복구 대상에서 빠지지 않게 하는 선택이다. 주문 복구 작업은 payment.api에서 결제 결과를 확인하고 sale.api로 점유를 확정·해제한다. payment가 order 구현을 역호출하지 않는다.

## 확정한 구현 범위

1. 실제 MySQL 연결·Flyway 초기화·local health를 확인해 0단계 검증 대기를 해소한다.
2. 판매 스키마 7개 업무 테이블과 PG 스키마 1개 업무 테이블의 Flyway 마이그레이션을 작성한다.
3. 소유 도메인의 infrastructure.persistence에 JPA 엔티티를 두고 UUID·enum·시각·수량·금액 매핑을 검증한다.
4. 실제 MySQL 기반 마이그레이션·제약 테스트와 ArchUnit 경계 검사를 추가한다.
5. 두 앱의 마이그레이션·설정 분리를 위해 mock-pg 하위 프로젝트의 최소 실행 뼈대까지 구성한다.

회원·로그인 API, 주문·결제 유스케이스, Mock PG 업무 HTTP·시뮬레이션, 복구 스케줄러, Redis 세션은 이후 소단계에서 구현한다. 테이블과 상태 타입을 준비했다고 해당 기능을 완료 처리하지 않는다. 사용되지 않는 공개 인터페이스·서비스·컨트롤러를 빈 구현으로 만들지 않는다.

## 공통 컬럼·매핑 기준

| 항목 | 상세안 |
|---|---|
| 저장 엔진 | InnoDB |
| 문자·비교 | utf8mb4. 상태값은 대소문자와 뒤 공백을 정확히 구분하는 utf8mb4_0900_bin(NO PAD) 사용 |
| 내부 PK | signed BIGINT AUTO_INCREMENT |
| UUID | BINARY(16), Java UUID. 공개 주문 ID·요청 키·PG 결제 키에 적용 |
| 금액 | signed BIGINT, Java long. 개당 1~1,000,000,000원, 주문 금액은 단가×수량이며 최대 10^15원 |
| 수량 | INT. 이벤트 총수량 1~1,000,000, 사용자 한도 1~총수량 |
| 시각 | DATETIME(6), UTC. created_at·updated_at는 필수. 앱·JDBC·DB 연결의 UTC 설정과 왕복 변환 검증 |
| 변경 버전 | 수량·상태 변경 테이블에 BIGINT version, 초기 0. JPA @Version과 잠금 기반 변경을 일관되게 적용 |
| 문자열 상태 | VARCHAR(32)와 허용값 CHECK. 역할은 VARCHAR(16) |

Hibernate의 MySQL enum 기본 매핑이 네이티브 ENUM을 선택할 수 있으므로 `@Enumerated(STRING)`만으로 VARCHAR가 보장된다고 가정하지 않는다. 명시적 VARCHAR JDBC 매핑과 `ddl-auto=validate`로 마이그레이션과의 일치를 확인한다. [Hibernate 공식 매핑 안내](https://docs.hibernate.org/orm/6.5/migration-guide/#datatype-for-enums)

아래에서 별도 언급이 없는 업무 컬럼은 NOT NULL이다. 선택 컬럼만 NULL을 허용한다. CHECK가 UNKNOWN인 NULL을 허용할 수 있으므로 NOT NULL을 CHECK로 대신하지 않는다.

## 판매 스키마 테이블

### members — member 소유

| 컬럼 | 타입·의미 |
|---|---|
| id | BIGINT PK |
| email | VARCHAR(254), 정규화한 이메일. 앞뒤 공백 제거·소문자화 |
| password_hash | VARCHAR(255), Argon2id 인코딩 결과 |
| role | VARCHAR(16), USER 또는 ADMIN |
| created_at, updated_at | DATETIME(6) |

UNIQUE(email)을 둔다. 이메일 컬럼은 대소문자 비구분·악센트 구분 collation을 사용해 같은 이메일의 대소문자 변형도 DB에서 중복으로 거절하는 안이다. 애플리케이션 정규화는 별도로 수행한다. 비밀번호 원문과 세션 데이터는 이 테이블에 저장하지 않는다. 공개 회원가입의 USER 강제와 관리자 초기화 동작은 1-3에서 검증한다.

### products — product 소유

| 컬럼 | 타입·의미 |
|---|---|
| id | BIGINT PK |
| name | VARCHAR(100), 제품 이름 |
| description | VARCHAR(2000), 빈 문자열 허용 |
| base_price | BIGINT, 기본 제안 가격 |
| created_at, updated_at | DATETIME(6) |

CHECK: 이름 길이 1~100, 가격 1~10억원. 문자열이 공백뿐인지와 정규화는 입력·도메인 경계에서도 검증한다. 제품 이름은 유니크로 제한하지 않는다. 목록 필터가 정해지기 전에는 추측으로 검색 인덱스를 추가하지 않는다.

### sales — sale 소유

| 컬럼 | 타입·의미 |
|---|---|
| id | BIGINT PK |
| product_id | BIGINT, products.id FK |
| name, description | VARCHAR(100), VARCHAR(2000), 이벤트 표시 정보 |
| product_name_snapshot | VARCHAR(100), 이벤트에 보관한 제품명 |
| unit_price | BIGINT, 관리자 지정 판매 가격 |
| starts_at, ends_at | DATETIME(6) |
| total_quantity | INT, 이벤트 전용 총수량 |
| reserved_quantity, sold_quantity | INT, 초기 0 |
| per_member_limit | INT, 기본 1 |
| is_stopped | BOOLEAN, 기본 false |
| version | BIGINT, 초기 0 |
| created_at, updated_at | DATETIME(6) |

CHECK: 단가 범위, starts_at < ends_at, 총수량 범위, 1 ≤ per_member_limit ≤ total_quantity, 수량 각각 ≥ 0, reserved_quantity + sold_quantity ≤ total_quantity, is_stopped의 0/1 제한. 가용 수량은 계산하며 별도 변경 컬럼으로 중복 보관하지 않는다.

현재 시각과 비교한 판매 가능 여부·시작 후 수정 금지는 CHECK가 아니라 이벤트 행을 잠근 트랜잭션에서 판정한다. 시간 상태는 기간·중단 여부로 계산하고 별도의 SCHEDULED/OPEN/ENDED 컬럼과 이중 관리하지 않는 안이다.

### sale_buyer_quantities — sale 소유

| 컬럼 | 타입·의미 |
|---|---|
| id | BIGINT PK |
| sale_id | BIGINT, sales.id FK |
| member_id | BIGINT, members.id FK |
| reserved_quantity, purchased_quantity | INT, 초기 0 |
| version | BIGINT, 초기 0 |
| created_at, updated_at | DATETIME(6) |

UNIQUE(sale_id, member_id), 각 수량 ≥ 0, 두 수량 합계 ≤ 전역 이벤트 수량 상한 100만개. 이 행의 합계와 sales.per_member_limit 비교는 여러 테이블에 걸친 규칙이므로 CHECK만으로 보장하지 않는다. 같은 이벤트 잠금 아래 해당 사용자 수량을 검사·변경한다. 사용자별 행은 최초 주문에서 생성하고 이벤트 등록 때 모든 회원의 행을 미리 만들지 않는다.

### purchase_orders — order 소유

| 컬럼 | 타입·의미 |
|---|---|
| id | BIGINT PK |
| public_id | BINARY(16), 외부 주문 UUID |
| member_id | BIGINT, members.id FK |
| request_key | BINARY(16), 사용자별 요청 UUID |
| sale_id, product_id | BIGINT, sales.id·products.id FK |
| product_name_snapshot | VARCHAR(100) |
| unit_price, total_amount | BIGINT, 주문 시점 단가·총액 |
| quantity | INT |
| status | VARCHAR(32), 합의한 주문 상태 |
| expires_at | DATETIME(6), 생성 시각+기본 5분 |
| next_recovery_at | DATETIME(6), 선택. 최종 처리 뒤에는 NULL |
| lease_token | BINARY(16), 선택. 복구 작업 획득마다 새 토큰 |
| lease_until | DATETIME(6), 선택. 임대 만료 |
| requires_review | BOOLEAN, 기본 false. PG 장기 무응답 등 운영 확인 표시 |
| version | BIGINT, 초기 0 |
| created_at, updated_at | DATETIME(6) |

UNIQUE(public_id), UNIQUE(member_id, request_key). CHECK: 단가·수량 범위, total_amount = unit_price × quantity, 허용 상태, lease_token과 lease_until이 함께 NULL 또는 함께 값 존재. 같은 사용자의 여러 정상 주문을 허용하므로 (sale_id, member_id)는 유니크가 아니다.

상태는 AWAITING_PAYMENT/PAYMENT_PENDING/CONFIRMED/PAYMENT_FAILED/EXPIRED다. 최종 주문의 next_recovery_at·임대 해제와 비최종 주문의 복구 예약은 애플리케이션 상태 전이에서 관리하고 1-7에서 검증한다.

### sale_reservations — sale 소유

| 컬럼 | 타입·의미 |
|---|---|
| id | BIGINT PK |
| order_id | BIGINT, purchase_orders.id FK·유니크 |
| sale_id, member_id | BIGINT, sale_buyer_quantities의 (sale_id, member_id) 참조 |
| quantity | INT |
| status | VARCHAR(32), RESERVED/CONFIRMED/RELEASED |
| version | BIGINT, 초기 0 |
| created_at, updated_at | DATETIME(6) |

주문당 점유는 하나다. CHECK: 수량 1~100만개와 허용 상태. 주문의 이벤트·사용자·수량과 점유 기록의 값이 같아야 하는 규칙은 도메인 계약과 통합 테스트로 검증한다. 단순 FK 존재 확인만으로 이 일치까지 증명했다고 설명하지 않는다.

### payments — payment 소유

| 컬럼 | 타입·의미 |
|---|---|
| id | BIGINT PK |
| order_id | BIGINT, purchase_orders.id FK·유니크 |
| pg_key | BINARY(16), 고정 결제 키·유니크 |
| amount | BIGINT |
| status | VARCHAR(32), READY/PROCESSING/UNKNOWN/SUCCEEDED/FAILED/CLOSED |
| requested_at | DATETIME(6), 선택. 실제 결제 요청을 시작한 이력 |
| last_checked_at | DATETIME(6), 선택 |
| finalized_at | DATETIME(6), 선택 |
| version | BIGINT, 초기 0 |
| created_at, updated_at | DATETIME(6) |

CHECK: 금액 1~10^15원과 허용 상태. order_id·pg_key 각각 유니크. 주문 총액과 결제 금액의 일치, 최종 상태 역행 금지, 요청 시작 이력과 재시도 규칙은 유스케이스와 테스트로 검증한다. 복구 예약·임대 정보를 이곳에 중복 보관하지 않는다.

## PG 스키마 — Mock PG 앱 소유

### mock_payments

| 컬럼 | 타입·의미 |
|---|---|
| id | BIGINT PK |
| pg_key | BINARY(16), 유니크 |
| amount | BIGINT |
| status | VARCHAR(32), PROCESSING/SUCCEEDED/FAILED/CLOSED |
| scenario | VARCHAR(32), SUCCESS/DECLINE/DELAY/RESPONSE_LOSS |
| completion_due_at | DATETIME(6), 선택. 지연 시나리오의 처리 기준 |
| response_loss_consumed | BOOLEAN, 기본 false. 최초 응답 유실 재현 여부 |
| finalized_at | DATETIME(6), 선택 |
| version | BIGINT, 초기 0 |
| created_at, updated_at | DATETIME(6) |

CHECK: 금액 1~10^15원, 허용 상태·시나리오·boolean 값. 판매 스키마의 order_id·member_id·FK는 없다. pg_key와 금액만으로 처리하고 종료 요청이 먼저 오면 CLOSED 기록을 생성해 늦은 요청을 막는다. SQL 제약만으로 최종 상태의 역행을 막지는 못하므로 PG 상태 전이 구현·경쟁 테스트는 1-7에서 수행한다.

시나리오 실행 로직은 1-7에서 구체화한다. 스키마 설계의 지연·최초 응답 유실용 컬럼은 해당 구현에서 검토하고 필요하면 추가 마이그레이션으로 변경한다.

## FK·인덱스 설계

모든 FK는 참조 PK·유니크 키와 같은 타입·문자 비교 규칙을 사용하고 ON DELETE RESTRICT·ON UPDATE RESTRICT를 적용한다. FK 인덱스는 아래 목록으로 명시하되 유니크 키의 선두 컬럼으로 이미 충족하면 중복 생성하지 않는다.

| 테이블 | 키·인덱스 | 사용 목적 |
|---|---|---|
| members | UK(email) | 가입 중복·로그인 조회 |
| sales | INDEX(product_id) | FK 참조 지원 |
| sale_buyer_quantities | UK(sale_id, member_id), INDEX(member_id) | 사용자 한도 행 유일성·잠금 조회·FK |
| purchase_orders | UK(public_id), UK(member_id, request_key) | 공개 주문 조회·요청 중복 방지 |
| purchase_orders | INDEX(member_id, id), INDEX(sale_id), INDEX(product_id) | 내 주문 목록·FK |
| purchase_orders | INDEX(status, next_recovery_at, id) | 비최종 주문 복구 후보 조회 |
| sale_reservations | UK(order_id), INDEX(sale_id, member_id) | 한 번의 점유·사용자 수량 복합 FK |
| payments | UK(order_id), UK(pg_key) | 주문별 결제·고정 PG 키 |
| mock_payments | UK(pg_key) | PG 중복 요청·조회·종료 |

아직 사용하지 않는 임의 검색·단일 상태 인덱스는 추가하지 않는다. 1-7에서 복구 쿼리를 구현한 뒤 EXPLAIN으로 복구 인덱스와 임대 만료 필터를 재확인한다. 실제 후보 조건은 status·next_recovery_at·lease_until을 함께 사용하며 인덱스가 전체 조건을 모두 해결한다고 가정하지 않는다.

도메인 간 물리 FK를 두더라도 다른 도메인의 테이블을 SQL로 직접 수정·조인하는 권한으로 해석하지 않는다. 같은 DB 트랜잭션 참여는 공개 인터페이스 구현에서 처리한다. 테이블 생성 순서는 members/products → sales → sale_buyer_quantities/purchase_orders → sale_reservations/payments로 한다. PG는 독립 마이그레이션이다.

## 마이그레이션·실행 구성

- 판매 DB와 PG DB는 서로 다른 스키마·계정·Flyway 이력을 사용한다. 앱은 자기 스키마의 마이그레이션만 실행한다.
- 계정·스키마 준비는 로컬 Compose 초기화·별도 재실행 가능한 준비 명령으로 관리하며 업무 Flyway가 다른 스키마나 사용자를 생성하지 않는다.
- 기존 볼륨에서는 Docker 최초 초기화 스크립트가 다시 실행되지 않음을 안내하고, 데이터 삭제를 요구하지 않는 준비 방법을 제공한다.
- 이미 적용된 마이그레이션을 수정하지 않고 변경은 후속 버전으로 추가한다. 비밀값은 예제와 실제 환경 파일을 구분한다.
- 현 H2 health 테스트는 MySQL 마이그레이션 호환성의 증거로 쓰지 않는다. MySQL 검증용 컨텍스트·health 테스트로 옮기는 안이다.

## 검증 계획

| 검증 | 확인할 실제 결과 |
|---|---|
| 빈 DB 마이그레이션 | 두 스키마의 테이블·키·CHECK·독립 Flyway 이력 생성 |
| 재실행 | 같은 마이그레이션을 다시 실행해도 데이터 보존, 이력 중복·체크섬 오류 없음 |
| 유니크 | 이메일·사용자 요청 키·주문 공개 ID·주문별 점유/결제·PG 키 중복 거절 |
| FK | 없는 부모 참조 거절, 참조 중 부모 삭제·키 변경 거절, cascade 없음 |
| CHECK | 음수 수량, 수량 합계 초과, 0원·가격 상한 초과, 잘못된 기간·상태·총액 거절 |
| 매핑 | UUID→16바이트→UUID 왕복, enum 문자열, UTC 마이크로초 시각, 최대 금액 저장·조회 |
| 앱별 접근 | 판매 계정으로 PG 테이블 접근 불가, PG 계정으로 판매 테이블 접근 불가 |
| 패키지 경계 | 외부 도메인 api 외 참조·순환·api의 내부 참조·domain의 프레임워크/구현 의존 거절 |

Testcontainers MySQL 8.4.7의 격리 DB에서 검증하고 개발 DB를 테스트 초기화 대상으로 쓰지 않는다. 순수·구조 검사는 test, DB 검사는 integrationTest로 나누고 check/build에 DB 검증까지 포함한다. Docker 미실행을 자동 skip으로 숨기지 않고 실패로 보고한다.

ArchUnit 규칙은 현재 코드가 통과하는지만 확인하지 않고 의도적인 위반 fixture가 거절되는지도 검증한다. 실제 업무 수량 동시성·PG 복구 검증은 1-7~1-10에서 수행하며 이번 제약 검증으로 대체하지 않는다.

## DB 제약의 한계

MySQL CHECK는 같은 행의 범위·관계를 검사하며 다른 테이블 조회·NOW()·서브쿼리를 사용할 수 없다. 따라서 판매 시간, 사용자별 실제 한도, 주문과 점유·결제의 수량·금액 일치, 상태 전이, DB 전체 집계는 애플리케이션 트랜잭션과 대조 쿼리로 검증한다. AUTO_INCREMENT PK와 FK 컬럼에는 별도 양수 CHECK를 붙이지 않는다. [MySQL CHECK 공식 문서](https://dev.mysql.com/doc/refman/8.4/en/create-table-check-constraints.html)

FK는 유효한 부모와 삭제 제한을 보장한다. 해당 부모가 업무적으로 올바른 주문·사용자인지는 공개 도메인 계약에서 확인해야 한다. [MySQL FK 공식 문서](https://dev.mysql.com/doc/refman/8.4/en/create-table-foreign-keys.html)

## 실행 체크리스트

- [x] 기존 1-1 정책과 계약 확인
- [x] 사용자와 FK·UUID 저장·enum 매핑 확정
- [x] 사용자와 전체 복구 예약을 주문에 모으는 책임 배치 확정
- [x] 이 상세안의 테이블·제약·인덱스·매핑과 구현 범위 검토 완료
- [x] Mock PG 하위 프로젝트 최소 뼈대·독립 마이그레이션 포함 확정
- [x] test/integrationTest 분리, 전체 check/build에 DB 검증 포함, Docker 미실행은 실패 보고 확정
- [x] 워커가 실제 MySQL 연결·Flyway·local health 확인
- [x] 사용자 구현 시작 지시 수신
- [x] Terra high 워커의 마이그레이션·매핑·테스트·경계 검사 구현
- [x] 주 에이전트가 실제 MySQL·산출물·테스트 근거 검증
- [x] 검증 결과·한계 문서화 및 1-2 완료 기록

## 워커 실행 근거 (2026-11-10)

Java 21에서 `./gradlew clean build`가 성공했고, 후속 제약 테스트 보강 뒤 `./gradlew integrationTest`가 성공했다. test 4개와 integrationTest 6개 모두 실패·skip 0개다. Testcontainers MySQL 8.4.7에서 두 마이그레이션 재실행과 데이터 보존, 계정 격리, 제약, JPA UUID·문자열 상태·마이크로초 UTC·최대 금액 및 두 앱 health를 검사한다. 구조 규칙은 실패 fixture도 실행한다.

로컬 `docker compose up -d`, `./scripts/prepare-local-db.sh` 반복 실행 후 MySQL 8.4.7에 판매 테이블 7개와 PG 테이블 1개 및 각 Flyway 이력이 생성됐다. 판매 앱과 Mock PG의 `/actuator/health`는 각각 HTTP 200과 `UP`을 반환했다. Flyway가 MySQL 8.4를 미검증 버전으로 경고하므로 실제 통합 테스트 근거와 함께 추후 버전 갱신 시 재검토한다. 스키마 제약은 주문·점유·결제 간 업무 일치나 동시성·복구를 보장하지 않는다. 주 에이전트의 독립 검증은 남아 있다.

## 추가 수정과 검증 (2026-11-10)

V1의 체크섬은 보존하고 두 스키마의 V2에서 enum 컬럼을 NO PAD `utf8mb4_0900_bin`으로 변경했다. 격리된 MySQL에서 V1 데이터를 넣은 뒤 V2를 적용하고 보존·반복 실행·독립 이력과 여섯 컬럼 각각의 소문자·뒤 공백 거절을 확인한다. 로컬 MySQL에서도 두 앱이 V1에서 V2로 정상 이행하고 각 health가 `UP`으로 응답했다. 기존 V1 SQL의 표현은 이미 적용된 마이그레이션이므로 유지한다.

두 앱의 Hikari 연결을 READ COMMITTED로 설정하고 각 앱 DataSource에서 JDBC 격리 수준과 MySQL UTC 세션을 확인한다. ArchUnit은 정확한 api 패키지 경계 및 domain의 프레임워크·클라이언트 의존을 검사하며 실제 규칙과 위반 fixture를 공유한다. 요청 키의 사용자별 유니크 범위와 제약별 MySQL 오류 코드도 검증한다. Flyway의 MySQL 8.4 지원 경고는 유지되며 주 에이전트의 검증 체크박스는 열어 둔다.

## 주 에이전트 독립 검증 (2026-11-10)

최종 코드에서 Java 21 `./gradlew clean build`를 직접 실행해 성공했다. XML 결과 집계는 test 4개, integrationTest 8개이며 실패·오류·skip은 모두 0이다. 테스트는 MySQL 8.4.7에서 양쪽 앱의 HTTP health, 실제 DataSource의 READ COMMITTED·UTC, UUID·문자열 enum·마이크로초·최대 금액 왕복을 검증한다.

로컬 MySQL을 직접 조회해 판매 스키마 업무 테이블 7개와 PG 1개, 각 Flyway V1·V2 성공 이력, enum 관련 6개 컬럼의 utf8mb4_0900_bin 적용을 확인했다. 처음 검토에서 발견한 trailing space 허용, 격리 수준 누락, api 접두어 경계 누락을 수정한 코드·회귀 테스트도 재검토했다. 문서·공백 검사도 통과했다.

1-2를 완료한다. Flyway의 MySQL 8.4 지원 범위 경고는 남아 있으며 버전 갱신 시 재검토한다. 실제 주문·구매 한도 동시성·PG 처리·복구 기능은 이후 단계의 범위다. 현재 MySQL Compose 컨테이너는 실행 상태이고 별도 앱 프로세스는 워커가 종료했다. 커밋·푸시는 하지 않았다.


## 1-7-1 요청 결과 추가

V4의 `order_request_results`는 회원별 요청 UUID와 원본 이벤트 ID·수량, 생성 주문 참조 또는 안정적인 거절 사유를 TTL 없이 저장한다. 회원과 주문에는 FK RESTRICT를 적용하지만 존재하지 않는 이벤트 요청도 보관하므로 원본 이벤트 ID에는 FK가 없다. 생성·거절의 필수 값은 CHECK로 구분하며 상세 계약은 [1-7-1](01-7-1-order-reservation.md)을 따른다.
