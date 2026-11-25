# 데이터베이스와 마이그레이션

[위키 시작](README.md) · 이전: [Mock PG](mock-pg.md) · 다음: [실행과 문제 해결](operations.md)

HTTP 응답을 잃거나 서버가 재시작돼도 DB의 기록은 남아 있어야 한다. 회원·주문·점유·결제 기록을 각각 어떤 테이블에 보관하고 어떤 FK·제약으로 연결하는지 설명한다. 한 주문이 만드는 행은 [구매 흐름의 저장 예](purchase-flow.md)에서 먼저 확인할 수 있다.

판매 앱과 Mock PG는 각자 소유한 MySQL 스키마와 Flyway 이력을 사용한다. [판매 V1](../../src/main/resources/db/migration/V1__sales_schema.sql)은 회원·제품과 이후 판매·주문·결제용 테이블 및 FK·제약을 만든다. [Mock PG V1](../../mock-pg/src/main/resources/db/migration/V1__mock_pg_schema.sql)은 별도 `mock_payments`를 만든다. 서로 다른 스키마 사이 FK는 없다. 양쪽 V2는 enum 비교 규칙을 변경한다.

아래 화살표는 자식 테이블에서 실제 FK가 참조하는 부모 테이블을 가리킨다. `members`·`products`·`sales`는 현재 회원·제품·판매 이벤트 기능에서 사용한다. 주문·회원별 수량·주문별 점유·결제·요청 결과는 주문 생성, 결제, 자동 복구에서 사용한다. Mock PG는 별도 스키마이므로 선으로 연결하지 않았다. 관계와 제약의 전체 정의는 [판매 V1 SQL](../../src/main/resources/db/migration/V1__sales_schema.sql)을 확인한다.

![판매 스키마의 외래 키 관계](diagrams/persistence-1.svg)

[Excalidraw 원본 편집](diagrams/persistence-1.excalidraw)

예를 들어 `sale_reservations`의 복합 FK는 `sale_buyer_quantities`의 `(sale_id, member_id)`를 참조한다. [판매 V4](../../src/main/resources/db/migration/V4__order_request_results.sql)의 `order_request_results`는 회원·주문에 FK를 둔다. 원본 요청 이벤트 ID에는 FK가 없으므로 없는 이벤트의 거절도 저장할 수 있다. CHECK는 성공에 주문 참조만, 거절에 사유만 허용한다. 요청 키는 회원별 유니크이며 UUID는 BINARY(16)이다.

[판매 설정](../../src/main/resources/application.yml)은 시작 시 SQL 경로와 Java 마이그레이션 경로를 Flyway에 지정하고 Hibernate `ddl-auto: validate`를 사용한다. 따라서 스키마 생성은 Flyway가 담당하고 JPA는 매핑을 검증한다. 적용된 마이그레이션은 수정하지 않고 새 버전을 추가한다. Flyway 이력에 성공한 버전은 정상 재시작 때 다시 적용하지 않는다.

두 앱의 버전은 별도 이력으로 관리한다. 다음 그림은 실행 순서를 나타내며, 같은 번호가 두 앱 사이의 의존성을 뜻하지 않는다.

![판매 서버와 Mock PG의 마이그레이션](diagrams/persistence-2.svg)

[Excalidraw 원본 편집](diagrams/persistence-2.excalidraw)

예를 들어 이미 판매 V1과 V2를 적용한 DB는 다음 시작에서 V3과 V4를 순서대로 적용한다. 중단 시 복구 주의사항은 아래를 따른다. [판매 V2](../../src/main/resources/db/migration/V2__exact_enum_comparisons.sql)와 [Mock PG V2](../../mock-pg/src/main/resources/db/migration/V2__exact_enum_comparisons.sql)도 각각 확인할 수 있다.

## 닉네임 이행과 현재 저장

[V3 Java 마이그레이션](../../src/main/java/db/nickmigration/V3__member_nickname.java)은 기존 회원 ID를 41진법 문자로 인코딩해 `nickname`과 `nickname_key`를 채운 후 NOT NULL과 유니크 제약을 추가한다. 이 결정적 변환은 기존 행 이행용이다. 신규 가입의 무작위 이름과 첫 빈 접미사 탐색은 [회원 가입](member.md)의 별도 로직이다. MySQL DDL은 전체가 하나의 원자적 트랜잭션이 아니므로 중단 시 Flyway 이력과 실제 컬럼 상태를 확인해야 한다. 자동 재실행만으로 부분 적용 복구를 보장하지 않는다. 근거와 주의사항은 [1-3 문서](../stages/01-3-member.md)에 있다.

예를 들어 `nickname=Happy 토끼`라면 비교 키는 `happy 토끼`다. DB는 `nickname_key`에 유니크 제약을 두므로 대소문자만 다른 이름의 동시 가입도 거절한다. 표시할 이름과 중복 비교용 값을 따로 저장하는 이유다.

현재 회원 읽기와 쓰기는 [JpaMemberStore](../../src/main/java/com/wooyongjeong/limitedsale/member/infrastructure/persistence/JpaMemberStore.java)와 Spring Data JPA 저장소가 담당한다. 접미사 조회에만 네이티브 SQL을 쓴다. V3와 일부 테스트에서 JDBC를 사용한다는 사실은 운영 회원 저장소가 JDBC라는 뜻이 아니다. [MemberEntity](../../src/main/java/com/wooyongjeong/limitedsale/member/infrastructure/persistence/MemberEntity.java)는 비교 키와 해시를 저장하고 도메인 `Member` 또는 인증용 `AuthenticationRecord`로 변환한다. [ProductEntity](../../src/main/java/com/wooyongjeong/limitedsale/product/infrastructure/persistence/ProductEntity.java)는 도메인 `Product`로 변환한다.

다음은 기존 행과 신규 가입의 닉네임 경로다. 두 결과 모두 유니크 비교 키에 저장하지만 이름을 만드는 방법은 다르다.

![기존 회원과 신규 회원의 닉네임 경로](diagrams/persistence-3.svg)

[Excalidraw 원본 편집](diagrams/persistence-3.excalidraw)

| 변환 | 사용하는 곳 | 외부 응답에 해시 포함 |
|---|---|---|
| `MemberEntity.toMember()` | 이메일로 회원 조회·관리자 기존 계정 확인 | 아니요 |
| `MemberEntity.toAuthenticationRecord()` | 로그인 검증용 내부 값 | HTTP 응답에는 포함하지 않음 |
| `ProductEntity.toProduct()` | 제품 도메인 값 | 해당 없음 |

회원 시각은 DB의 `UTC_TIMESTAMP(6)`으로 생성하고 제품 시각은 애플리케이션 `Instant`를 마이크로초로 잘라 저장한다. Hibernate JDBC 시간대와 로컬 MySQL 연결은 UTC로 설정한다. 두 앱 DataSource는 READ COMMITTED다. 주문 생성과 수량 점유·READY 결제 기록·요청 결과는 Spring Data JPA 저장소를 사용한다. 주문 생성 시각은 이벤트·회원 수량 잠금 뒤 읽은 MySQL UTC 시각이다. Mock PG는 자체 API와 별도 V3 시나리오 설정 테이블을 사용한다. 설정만 저장된 키에는 결제 기록이 없을 수 있다. 제약은 테이블 간 모든 업무 일치나 동시성·복구를 혼자 보장하지 않는다.

가입 성공 시 `MemberService`가 저장에 성공한 입력값으로 내부 `Member`를 구성하고 컨트롤러가 `MemberResponse`로 변환한다. 로그인은 `toAuthenticationRecord()`를 통해 조회한 엔티티를 변환한다. 모든 응답이 항상 `toMember()`의 별도 호출이나 DB 재조회를 거치는 것은 아니다.

[스키마 통합 테스트](../../src/integrationTest/java/com/wooyongjeong/limitedsale/MySqlSchemaIntegrationTest.java)는 실제 MySQL의 마이그레이션·제약·매핑을 다룬다. [V3 테스트](../../src/test/java/db/nickmigration/MemberNicknameMigrationTest.java)와 [회원 통합 테스트](../../src/integrationTest/java/com/wooyongjeong/limitedsale/member/application/MemberIntegrationTest.java)도 함께 본다. 전체 스키마 설계와 한계는 [1-2 문서](../stages/01-2-mysql-schema-and-boundaries.md)에 있다.

[Mock PG V3](../../mock-pg/src/main/resources/db/migration/V3__scenario_settings.sql)는 `mock_scenario_settings`를 추가한다. 결제 시작 전 설정을 보관하므로 mock_payments를 부모로 하는 FK는 없다. 기존 결제의 시나리오는 기본 지연값과 함께 이행한다. PG는 같은 키의 설정 행을 먼저 잠그고 결제 행을 잠그며, 두 테이블은 같은 PG 트랜잭션에 참여한다. UUID가 이미 채워진 신규 설정은 `Persistable.isNew()`로 INSERT를 강제해, merge가 다른 요청의 설정을 덮어쓰는 것을 막는다. 판매 스키마와는 트랜잭션·FK를 공유하지 않는다.

[V5](../../src/main/resources/db/migration/V5__order_recovery_warning.sql)는 주문에 next_review_warning_at을 추가한다. 임대·예약·운영 확인 표시는 [자동 복구](recovery.md)를 따른다.

[V6](../../src/main/resources/db/migration/V6__order_acceptances.sql)은 3-3의 비동기 접수용 테이블 두 개를 만든다. 위 FK 그림은 V1~V5까지의 관계이며 이 두 테이블은 아직 그림에 넣지 않았다.

- `order_acceptances`: 접수 한 건이다. `public_id`(공개 접수 UUID)·`member_id`·`request_key`·`sale_id`·`quantity`·`status`와 결과 열(`rejection_reason`·`failure_reason`·`order_id`)·`accepted_at`·`finished_at`을 둔다. `(member_id, request_key)`가 유니크라 v2의 중복 요청을 DB가 막으며 `order_request_results`와는 섞이지 않는다. 회원과 주문에는 FK(RESTRICT)를 두지만 `sale_id`에는 FK가 없다. 없는 이벤트에 대한 거절도 저장해야 하기 때문이다(`order_request_results`와 같은 이유). `status`는 `utf8mb4_0900_bin` 문자열이고, CHECK 하나가 `BINARY` 비교로 상태마다 필요한 열을 묶는다. `ACCEPTED`는 주문·사유·완료 시각이 없고, `ORDER_CREATED`는 주문과 완료 시각이 있고, `REJECTED`는 정해진 다섯 사유 중 하나와 완료 시각이 있고, `PROCESSING_FAILED`는 실패 사유와 완료 시각이 있다. 소문자나 뒤 공백이 붙은 상태값은 거절된다.
- `order_outbox`: 발행 대기 행이다. 자동 증가 `id`의 순서가 발행 순서다. `topic`·`message_key`·`payload`(JSON만 허용)·`created_at`·`published_at`(null이면 미발행)·`attempts`를 두고 `(published_at, id)` 인덱스로 미발행 행을 id 순서로 읽는다. 접수 행을 갱신하지 않고 별도 행으로 발행 상태를 따로 기록해서 이후 소비자가 접수 행을 잠글 때 발행기와 부딪치지 않는다. 3-4의 발행기는 이름 잠금으로 발행자를 하나로 정하므로 임대 같은 열을 더하지 않았고, 새 마이그레이션도 없다. 발행기는 `published_at`과 `attempts`만 갱신한다. [V7](../../src/main/resources/db/migration/V7__order_outbox_tracking.sql)(3-6)은 `acceptance_id`(접수 FK, 기존 행은 내용의 접수 ID로 채운 뒤 NOT NULL)·`partition_no`·`record_offset`(발행 결과, 둘 다 NULL이거나 둘 다 있고 발행 시각도 있음)과 `(acceptance_id, id)` 인덱스를 더하고, `order_acceptances`에 `(status, id)` 인덱스를 더한다. 발행기는 위치도 함께 기록하고, 정체 재발행은 같은 접수의 새 행을 넣는다. 그래서 한 접수에 아웃박스 행이 여럿일 수 있다. [V8](../../src/main/resources/db/migration/V8__order_acceptance_request_id.sql)(3-7)은 `order_acceptances.request_id`(`VARCHAR(64)` NULL, 접수 요청의 `X-Request-Id`)를 더한다. V8 이전 접수는 NULL이다.

접수가 `ACCEPTED`일 때만 아웃박스 행이 생기며 둘은 한 트랜잭션에 저장된다. 아웃박스 INSERT가 실패하면 접수 행도 남지 않는다. 흐름은 [주문 생성과 수량 점유의 v2 절](orders.md#v2-비동기-접수기본-꺼짐)에 있다. 접수 행은 3-5의 소비자가 `FOR UPDATE`로 잠그고 `ORDER_CREATED`(주문 ID)나 `REJECTED`로 끝낸다. 소비자가 만든 주문은 `purchase_orders.request_key`가 접수 ID이고 `order_request_results` 행이 없다. 대조 SQL은 이런 주문을 `ORDER_CREATED` 접수로 확인한다(`ACCEPTANCE_VS_ORDER`). 아웃박스는 3-4의 발행기가 읽어 Kafka로 보낸 뒤 `published_at`을 기록한다. 완료한 행은 지우지 않는다.
