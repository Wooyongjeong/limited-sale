# 주문 생성과 수량 점유

[위키 시작](README.md) · 이전: [판매 이벤트](sales.md) · 다음: [사용자 결제](payments.md)

주문 버튼을 누르는 순간 결제가 완료되는 것은 아니다. 먼저 다른 사람과 겹치지 않게 수량을 확보하고, 누가 무엇을 얼마에 주문했는지 저장한다. 여기서는 그 원자적 처리를 코드·DB 수준에서 설명한다. 수량 예시부터 보고 싶다면 [전체 구매 흐름](purchase-flow.md), 재시도가 궁금하면 [실패 사례](failure-cases.md)를 먼저 읽는다.

주문 생성은 이벤트 재고와 사용자 구매 한도를 확보하고 결제 준비 기록까지 한 번에 저장한다. 같은 요청을 다시 보내도 주문이 늘어나지 않으며, 업무상 거절도 요청 키별로 보관한다. 주문 확정과 점유 해제는 [사용자 결제](payments.md)와 [자동 복구](recovery.md)에서 이어진다. PG API와 ADMIN 시나리오 설정은 [Mock PG](mock-pg.md)에서 설명한다.

## 요청에서 트랜잭션까지

로그인한 사용자가 `POST /api/v1/orders`에 UUID `Idempotency-Key`, `X-CSRF-TOKEN`, 세션 쿠키와 아래 본문을 보낸다. 서버는 금액과 주문 UUID를 생성한다.

```json
{"saleId": 1, "quantity": 2}
```

[OrderController](../../src/main/java/com/wooyongjeong/limitedsale/order/presentation/OrderController.java)는 엄격한 JSON·양의 ID·수량 1~1,000,000·UUID 형식을 검증한다. 로그인 ID는 [MemberIdentity](../../src/main/java/com/wooyongjeong/limitedsale/member/api/MemberIdentity.java) 계약을 통해 얻는다. 다른 도메인의 보안 구현이나 JPA 엔티티를 직접 참조하지 않는다.

![주문 생성의 한 트랜잭션](diagrams/orders-1.svg)

[Excalidraw 원본 편집](diagrams/orders-1.excalidraw)

[OrderTransaction](../../src/main/java/com/wooyongjeong/limitedsale/order/application/OrderTransaction.java)이 트랜잭션을 소유한다. 도메인 판정·주문 결과·HTTP 코드의 enum은 명시적 switch로 매핑하므로 새 항목을 추가하면 누락된 변환을 컴파일 단계에서 확인할 수 있다. [SaleReservation](../../src/main/java/com/wooyongjeong/limitedsale/sale/api/SaleReservation.java)과 [PaymentPreparation](../../src/main/java/com/wooyongjeong/limitedsale/payment/api/PaymentPreparation.java)은 같은 로컬 트랜잭션에 참여한다. 구현 어댑터는 `MANDATORY`로 외부 트랜잭션 없는 호출을 거절한다. 여기에는 PG 네트워크 호출이 없다.

## 회원별 요청 제한

주문 생성(`POST /api/v1/orders`)과 결제(`POST /api/v1/orders/{id}/payment`)는 회원마다 기본 1초 1회로 제한한다. 2-6 비교 실험에서 1초 5회는 부하 중 남용 요청을 거의 거르지 못해 낮췄다([결과](../stages/02-redis.md#2-6-실험-결과와-검증)). 두 요청은 따로 센다. 그래서 주문 요청을 몰아 보내도 이미 받은 주문의 결제는 막히지 않는다. 검사는 [OrderService](../../src/main/java/com/wooyongjeong/limitedsale/order/application/OrderService.java)·[OrderPaymentService](../../src/main/java/com/wooyongjeong/limitedsale/order/application/OrderPaymentService.java)의 맨 앞에서 [OrderRateLimiter](../../src/main/java/com/wooyongjeong/limitedsale/order/application/OrderRateLimiter.java)가 한다. 인증·CSRF·입력 검증을 통과한 요청만 세고, 요청 키 조회와 DB 트랜잭션보다 먼저 끝난다. 조회 GET과 자동 복구에는 적용하지 않는다.

카운터는 Redis 키 `limited-sale:rate-limit:order:{create|payment}:{memberId}` 하나다. [RedisOrderRateLimit](../../src/main/java/com/wooyongjeong/limitedsale/order/infrastructure/redis/RedisOrderRateLimit.java)의 Lua 스크립트가 `INCR`, 첫 요청의 `PEXPIRE`, 남은 시간 조회를 한 번에 처리한다. 창은 첫 요청부터 시작하므로 인스턴스끼리 시계를 맞출 필요가 없다. 두 인스턴스로 나뉜 요청도 같은 키를 쓰므로 합산된다. 만료가 없는 비정상 키를 만나면 만료를 다시 건다.

창 안의 2번째 요청은 429 `ORDER_RATE_LIMITED`와 `Retry-After`(남은 시간을 초 단위로 올림, 최소 1)를 받는다. 이때 요청 키·주문·수량을 건드리지 않는다. 그래서 창이 지난 뒤 같은 키로 다시 보내면 처음 요청처럼 처리된다. 같은 키의 재시도도 한 번으로 센다.

Redis 명령이 실패하면 제한 없이 통과시키고 경고 로그를 남긴다. 수량과 구매 한도는 MySQL이 계속 지킨다. 다만 Redis가 완전히 멈추면 세션 인증이 먼저 503으로 실패한다. 값이 숫자가 아닌 키가 생기면 해당 회원은 키가 만료되거나 지워질 때까지 계속 통과한다. 지표는 `order_rate_limit_total{operation=create|payment, result=allowed|rejected|bypassed}`다. 거절된 요청은 `order_creation_results_total`·`order_payment_results_total`에 들어가지 않는다.

설정은 `limited-sale.order.rate-limit.enabled`(기본 `true`), `max-requests`(기본 1), `window`(기본 `1s`)다. 정합성·기준 부하 실험 도구는 "요청 제한 없음" 조건을 지키려고 판매 JVM을 끈 상태로 띄우고 결과에 그 값을 기록한다. 검증은 [OrderRateLimitIntegrationTest](../../src/integrationTest/java/com/wooyongjeong/limitedsale/order/OrderRateLimitIntegrationTest.java)를 본다.

## 품절 사전 판정

재고가 0이 된 뒤의 요청까지 이벤트 행 잠금 대기열에 세우지 않으려고, 요청 제한 다음에 Redis 품절 표시를 한 번 조회한다([OrderService](../../src/main/java/com/wooyongjeong/limitedsale/order/application/OrderService.java)). 이 조회는 트랜잭션 밖에서 하므로 판매 행 잠금을 쥔 채 Redis를 부르지 않는다. 표시는 `sale.api`의 `SoldOutGate`로만 읽는다.

- 표시가 없거나 Redis 조회가 실패·타임아웃이면 위 트랜잭션이 그대로 판정한다. 최종 판단은 계속 MySQL이다.
- 표시가 있으면 [OrderTransaction.rejectSoldOut](../../src/main/java/com/wooyongjeong/limitedsale/order/application/OrderTransaction.java)이 짧은 트랜잭션을 연다. 요청 키 조회를 먼저 하므로 이미 성공한 키는 표시가 있어도 `200`으로 재생되고, 다른 입력이면 `IDEMPOTENCY_CONFLICT`다. 새 키면 판매 행을 잠그지도 고치지도 않고 `SALE_SOLD_OUT` 거절을 요청 결과에 저장해 409를 돌려준다. 저장했으므로 같은 키를 다시 보내면 같은 거절이 반복된다. 이 경로에는 주문·결제·점유가 없다.

표시를 두고 지우는 규칙, TTL, 장애 처리는 [판매 이벤트](sales.md#품절-표시-redis)에 있다. 아래 순서 설명과 위 그림(`orders-1`)은 표시가 없는 경로이며, 그림에는 이 사전 판정이 없다.

표시가 있을 때는 판정 순서가 달라진다. 중단·기간 외·사용자 한도보다 품절을 먼저 응답하므로, 기간이 끝났거나 중단된 품절 이벤트, 한도를 넘긴 회원도 `SALE_SOLD_OUT`을 받을 수 있다. 어느 경우든 구매할 수 없다는 결과는 같다. 정확한 사유를 위해 잠금을 다시 잡으면 이 단계의 목적이 사라지므로 차이를 받아들였다. 설정은 `limited-sale.sale.sold-out-gate.enabled`(기본 `true`, 끄면 Redis를 부르지 않는다)와 `ttl`(기본 5초)이다. 검증은 [SoldOutGateIntegrationTest](../../src/integrationTest/java/com/wooyongjeong/limitedsale/sale/SoldOutGateIntegrationTest.java)를 본다. 10,000명이 재고 1,000개에 몰린 2-7 비교 실험에서는 거절 응답 p50이 1.34초에서 0.34초로 줄었지만, 확정 수는 같고 구매자의 `ORDER_CREATED`·결제 지연은 거의 그대로였다([결과](../stages/02-redis.md#2-7-구현과-검증)).

## 잠금 뒤에 주문 가능 여부를 판정하는 이유

[JpaSaleReservation](../../src/main/java/com/wooyongjeong/limitedsale/sale/infrastructure/persistence/JpaSaleReservation.java)은 항상 이벤트를 먼저 잠근다. 같은 이벤트의 요청은 이 행을 기준으로 순서대로 판정되므로 아직 사용자 수량 행이 없는 첫 주문도 서로 충돌해 중복 행을 만들지 않는다. 기존 사용자 수량 행도 잠근 뒤 DB 시각을 읽는다.

이벤트 잠금을 기다리는 동안 판매가 끝날 수 있다. 따라서 잠금을 기다리던 SQL의 시작 시각을 사용하지 않고, 잠금 획득 후 별도 시각 쿼리를 실행한다. [Sale.allocationDecision](../../src/main/java/com/wooyongjeong/limitedsale/sale/domain/Sale.java)은 기간 → 중단 → 사용자 한도 → 가용 재고 순서로 검사한다(품절 표시가 있으면 [사전 판정](#품절-사전-판정)이 먼저 품절로 답한다). 사용자 한도와 재고는 모두 점유 수량과 구매 확정 수량의 합을 사용한다. 모든 검사가 끝나기 전에는 수량을 쓰지 않는다.

예를 들어 총수량 5개, 사용자 한도 4개라면 한 사용자가 2개씩 두 주문을 생성할 수 있다. 그 사용자의 다음 주문은 한도 초과다. 다른 사용자는 남은 1개를 주문할 수 있다. 요청 수량의 일부만 확보하는 동작은 없다.

공개 이벤트 상태의 `SOLD_OUT`은 구매 확정 수량만으로 결정한다. 반면 주문의 `SALE_SOLD_OUT` 거절은 현재 점유를 포함한 재고 부족을 뜻한다. 전량 점유된 이벤트가 공개 조회에서는 OPEN이면서 새 주문은 거절될 수 있다.

## 같은 키를 다시 보내면

키는 `(member_id, request_key)`로 구분하고 TTL 없이 보관한다. 서로 다른 사용자는 같은 UUID를 독립적으로 사용할 수 있다. [OrderRequestResultEntity](../../src/main/java/com/wooyongjeong/limitedsale/order/infrastructure/persistence/OrderRequestResultEntity.java)는 입력 이벤트·수량과 주문 참조 또는 업무 거절 사유를 보관한다.

![동일한 요청 키로 다시 주문하면](diagrams/orders-2.svg)

[Excalidraw 원본 편집](diagrams/orders-2.excalidraw)

없는 이벤트도 `SALE_NOT_FOUND`로 보관한다. 같은 키의 거절 뒤 판매가 재개되거나 재고 조건이 바뀌어도 원래 거절을 반환한다. 새 구매 시도에는 새 키가 필요하다. 성공 결과는 응답 JSON을 복사하지 않고 주문을 다시 읽으므로 이후 주문 상태가 바뀌면 최신 상태를 반환한다.

입력·인증·CSRF 실패와 DB 장애는 요청 결과로 저장하지 않는다. 업무 거절은 예외를 던져 롤백시키는 대신 타입으로 반환하여 결과를 커밋한 다음 HTTP 응답으로 바꾼다.

### 같은 키의 두 요청이 동시에 들어오면

둘 다 기존 결과를 찾지 못해도 DB 유니크 제약이 최종 승자를 정한다. [JpaOrderStore](../../src/main/java/com/wooyongjeong/limitedsale/order/infrastructure/persistence/JpaOrderStore.java)는 MySQL 1062 중 두 요청 키 제약만 `OrderRequestKeyCollision`으로 변환한다. [OrderService](../../src/main/java/com/wooyongjeong/limitedsale/order/application/OrderService.java)는 실패 트랜잭션이 종료된 다음 별도 조회 트랜잭션으로 승자의 결과를 읽는다. 다른 유니크 충돌과 DB 오류는 500으로 전파한다.

![같은 키의 두 요청 중 하나만 커밋](diagrams/orders-3.svg)

[Excalidraw 원본 편집](diagrams/orders-3.excalidraw)

## 상세 응답과 현재 상태

`GET /api/v1/orders/{orderId}`는 외부 UUID를 사용하며 본인 주문만 반환한다. 없는 주문과 타인 주문은 모두 404다. 생성·재생·상세의 data 필드는 같다.

```json
{
    "orderId": "e54c9df2-73ef-4a12-93c2-d4bb6a3e7591",
    "saleId": 1,
    "productId": 1,
    "productNameSnapshot": "한정 제품",
    "quantity": 2,
    "unitPrice": 1000,
    "totalAmount": 2000,
    "status": "AWAITING_PAYMENT",
    "paymentStatus": "READY",
    "createdAt": "2030-01-01T00:00:00Z",
    "updatedAt": "2030-01-01T00:00:00Z",
    "expiresAt": "2030-01-01T00:05:00Z"
}
```

실제 HTTP 본문은 위 값을 공통 `ApiResponse`의 data에 담는다. 내부 PK·회원 ID·요청 키·PG 키는 노출하지 않는다. 기본 만료는 생성 후 5분이고 `limited-sale.order.expiration`으로 양의 기간을 지정한다. `next_recovery_at`에는 만료 시각을 기록한다. 만료 시각이 되면 자동 복구가 PG 종료를 확인해 주문을 정리한다. PG 결과를 모르면 점유를 유지한다.

[OrderIntegrationTest](../../src/integrationTest/java/com/wooyongjeong/limitedsale/order/OrderIntegrationTest.java)는 실제 MySQL·Redis에서 재고 소진, 회원 한도 경쟁, 요청 키 경쟁, 거절 재생, 소유권과 엄격 입력을 검증한다. 단가 10억 원·수량 100만 개의 총액 1,000조 원이 주문·결제·응답에 정확히 유지되는지도 검사한다. 결제 INSERT에 DB 오류를 주입해 전체 롤백을 확인하며, 잠금 대기 SQL을 확인한 뒤 판매 종료·중단을 반영한다. 응답 유실 검사는 첫 HTTP 응답을 버리고 재요청하는 방식이다. 네트워크 단절 자체를 주입한 검사는 아니다. 정책 원문과 실행 근거는 [1-7-1 단계 문서](../stages/01-7-1-order-reservation.md)에 있다.

주문 생성 본문은 [OrderRequest](../../src/main/java/com/wooyongjeong/limitedsale/order/presentation/request/OrderRequest.java)다. 내부 OrderView는 [OrderViewResponse](../../src/main/java/com/wooyongjeong/limitedsale/order/presentation/response/OrderViewResponse.java)로 변환한 후 공통 응답에 담는다. 생성·조회에도 paymentStatus를 함께 반환하며 멱등성 계약은 유지한다.

## 내 주문 목록

`GET /api/v1/orders?page=0&size=20`은 로그인한 회원의 주문만 최신순으로 반환한다. 정렬 기준은 생성 시각이 아니라 내부 주문 ID 내림차순이다. 생성 시각은 같은 값이 생길 수 있고, ID 순서는 `INDEX(member_id, id)`를 그대로 쓴다. 미로그인 요청은 401이다.

`page`는 0 이상, `size`는 1~100이고 기본값은 0과 20이다. 잘못된 값은 공개 판매 목록과 같은 400 본문을 반환한다. 주문이 없거나 마지막 페이지를 넘으면 빈 목록 200이다. 필터는 없다.

```json
{
    "items": [{"orderId": "e54c9df2-73ef-4a12-93c2-d4bb6a3e7591", "status": "CONFIRMED", "paymentStatus": "SUCCEEDED", "...": "상세와 같은 필드"}],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
}
```

항목은 상세 응답과 같은 필드다. 결제 대기·확인 중·확정·실패·만료 주문이 모두 나온다. 가격은 주문에 저장한 판매 가격과 총액이며, 이벤트 가격이 나중에 바뀌어도 그대로다. 품절·기간 외 같은 업무 거절은 주문을 만들지 않았으므로 목록에 없다. 같은 키 재시도로만 그 결과를 다시 확인한다.

같은 키 재시도는 목록에 주문을 늘리지 않는다. [OrderTransaction.create](../../src/main/java/com/wooyongjeong/limitedsale/order/application/OrderTransaction.java)가 요청 키 결과를 판매 기간 판정보다 먼저 읽기 때문이다. 판매가 끝난 뒤 같은 키로 다시 보내도 200과 기존 주문이 돌아온다. 새 키라면 `SALE_OUTSIDE_PERIOD`다.

[JpaOrderStore.list](../../src/main/java/com/wooyongjeong/limitedsale/order/infrastructure/persistence/JpaOrderStore.java)는 회원의 주문 수를 센 뒤 한 페이지를 읽는다. 결제 상태는 `payment.api`의 `PaymentOperations.statuses`로 페이지 전체를 한 번에 읽는다. 주문마다 결제를 따로 읽지 않는다. 오프셋이 주문 수 이상이면 페이지 조회 없이 빈 목록을 반환한다. READ COMMITTED이므로 복구 작업이 동시에 상태를 바꾸면 총수와 항목이 같은 순간의 값이 아닐 수 있다. 정책 원문은 [1-8 단계 문서](../stages/01-8-order-result-query.md)에 있다.
