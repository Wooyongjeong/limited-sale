# 로컬에서 로그인하고 주문까지 따라 하기

[위키 시작](README.md) · 이전: [코드 읽기](code-walkthrough.md)

이 순서를 Postman 같은 쿠키를 보관하는 HTTP 클라이언트로 따라가면 계정 생성·로그인·주문 점유·같은 주문 재시도를 확인할 수 있다. **현재는 실제 결제 완료까지 실행할 수 없다.** 아래 요청 예시는 안내용이며 이번 문서 작업에서 로컬 서버에 실행한 기록은 아니다.

## 준비: 서버와 클라이언트 변수

[루트 README](../../README.md)의 Java 21·Docker·DB 계정 설정대로 MySQL·Redis와 판매 앱을 실행한다. 기본 주소는 `http://localhost:8080`이다. 아래 `{{baseUrl}}`은 이 주소를 저장한 클라이언트 변수이며, 포트를 바꿨다면 함께 바꾼다. `GET {{baseUrl}}/actuator/health`가 성공하는지 확인한다.

Postman에서는 같은 컬렉션의 변수에 아래 값을 준비한다. `{{변수명}}` 문법이 다른 도구라면 해당 값으로 직접 대체한다. 요청은 쿠키 저장 기능을 켠 같은 클라이언트로 순서대로 실행한다.

| 변수 | 준비하거나 저장할 값 |
|---|---|
| baseUrl | `http://localhost:8080` |
| adminEmail·adminPassword | 아래 초기화에 사용한 관리자 이메일·비밀번호 |
| userEmail·userPassword | 새 테스트 계정. 예: `minsu@example.test`·`Example123!` |
| csrfToken | 가장 최근 CSRF 조회의 `data.token` |
| productId·saleId | 제품·이벤트 등록 응답의 ID. 숫자로 저장 |
| startsAt·endsAt | 이벤트를 만들기 직전에 정할 시작·종료 시각 |
| orderRequestKey | 이번 구매 시도에 새로 생성한 UUID |
| orderId | 주문 성공 응답의 `data.orderId` |

비밀번호 예시는 로컬 실습용이다. 기존 계정으로 반복 실습한다면 실제 사용했던 비밀번호를 변수에 넣는다. 기존 대기 주문도 재고와 사용자 한도를 계속 점유하므로 **새 실습에는 새 이벤트와 새 요청 키**를 사용한다.

### 관리자 계정 초기화

판매 앱과 같은 DB 연결 환경을 준비한 터미널에서 `ADMIN_EMAIL`, `ADMIN_PASSWORD`를 환경변수로 설정한다. 관리자 이메일은 일반 가입에 사용하지 않은 값으로 고른다. `ADMIN_NICKNAME`은 생략한다. 비밀번호는 프로젝트 정책인 공백 없는 출력 가능 ASCII 8~64자에 맞춘다.

```sh
./gradlew initAdmin
```

명령이 성공하면 HTTP 클라이언트의 `adminEmail`·`adminPassword`에 같은 값을 설정한다. 기존 같은 이메일의 ADMIN은 변경 없이 종료하고, USER가 있으면 실패한다. 이 명령이 로그인 세션까지 만들지는 않는다.

### 관리자 로그인

먼저 `GET {{baseUrl}}/api/v1/csrf`를 호출해 `data.token`을 `csrfToken`에 저장한다. 응답 쿠키도 유지한다. 이어서 다음 요청을 보낸다.

```http
POST {{baseUrl}}/api/v1/sessions
Content-Type: application/json
X-CSRF-TOKEN: {{csrfToken}}

{"email":"{{adminEmail}}","password":"{{adminPassword}}"}
```

200과 `data.role: ADMIN`을 확인한다. 로그인 후 `GET {{baseUrl}}/api/v1/csrf`를 다시 호출해 토큰을 갱신한다. 이 새 토큰으로 제품·이벤트를 등록한다.

### 제품 등록 후 productId 저장

```http
POST {{baseUrl}}/api/v1/admin/products
Content-Type: application/json
X-CSRF-TOKEN: {{csrfToken}}

{"name":"실습용 텀블러","description":"첫 주문 연습","basePrice":12000}
```

201이면 응답 `data.productId`를 `productId` 변수에 저장한다. 이름이 같아도 별도 제품이 생성되므로 ID를 1로 가정하지 않는다.

### 이벤트 등록 후 saleId 저장

이 요청을 보내기 직전에 `startsAt`을 **현재보다 약 2분 뒤**, `endsAt`을 시작보다 1시간 뒤로 정한다. 오프셋이 명시된 ISO 8601 문자열을 사용한다. 예를 들어 서버 현재 시각이 `2030-01-01T12:00:00+09:00`일 때에만 시작 `2030-01-01T12:02:00+09:00`, 종료 `2030-01-01T13:02:00+09:00`를 사용할 수 있다. 예시 날짜를 그대로 복사하지 않고 실행하는 날의 시각으로 바꾼다.

Postman의 이 이벤트 등록 요청에만 다음 Pre-request Script를 붙이면 전송 직전에 두 변수를 정할 수도 있다. 재전송할 때는 다른 이벤트가 생성되는 점에 유의한다.

```javascript
const startsAt = new Date(Date.now() + 2 * 60 * 1000);
pm.collectionVariables.set("startsAt", startsAt.toISOString());
pm.collectionVariables.set("endsAt", new Date(startsAt.getTime() + 60 * 60 * 1000).toISOString());
```

```http
POST {{baseUrl}}/api/v1/admin/sales
Content-Type: application/json
X-CSRF-TOKEN: {{csrfToken}}

{
    "productId": {{productId}},
    "name": "첫 주문 실습",
    "unitPrice": 10000,
    "startsAt": "{{startsAt}}",
    "endsAt": "{{endsAt}}",
    "totalQuantity": 3,
    "perMemberLimit": 2
}
```

201이면 `data.saleId`를 `saleId`에 저장한다. 등록 직후 상태는 SCHEDULED다. 일반 사용자 실습을 준비하는 동안 시작 시각을 기다린다. 시각이 이미 지났다는 400이 나오면 시작·종료 변수를 새로 정해 다시 등록한다. 이벤트 등록 필드의 상세 규칙은 [판매 이벤트](sales.md)를 따른다.

### 관리자 로그아웃

같은 클라이언트에서 관리자 인증을 끝낸 다음 일반 계정으로 실습한다.

```http
DELETE {{baseUrl}}/api/v1/sessions/current
X-CSRF-TOKEN: {{csrfToken}}
```

200이면 관리자 세션이 무효화된다. 다음 단계에서 익명 CSRF 토큰을 새로 받는다. 쿠키·토큰을 직접 다른 계정에 복사하지 않는다.

## 1. 익명 CSRF 토큰 받기

```http
GET {{baseUrl}}/api/v1/csrf
```

응답 `data.token`을 클라이언트 변수 `csrfToken`에 보관한다. 응답 쿠키도 쿠키 저장소에 유지한다. 토큰 값만 복사하고 쿠키를 버리면 이후 CSRF 검증을 통과하지 못할 수 있다.

이후 변경 요청은 이 토큰과 같은 세션 쿠키를 사용한다.

## 2. 테스트 계정 만들기

```http
POST {{baseUrl}}/api/v1/members
Content-Type: application/json
X-CSRF-TOKEN: {{csrfToken}}

{"email":"{{userEmail}}","password":"{{userPassword}}"}
```

비밀번호는 예시용 값이다. 201과 이메일·자동 생성 닉네임·USER 역할을 확인한다. 기존에 같은 계정을 만들었다면 중복 409가 나오므로 그 계정으로 로그인하거나 새 이메일을 사용한다. 회원가입은 자동 로그인하지 않는다.

## 3. 로그인하고 CSRF 토큰 갱신하기

```http
POST {{baseUrl}}/api/v1/sessions
Content-Type: application/json
X-CSRF-TOKEN: {{csrfToken}}

{"email":"{{userEmail}}","password":"{{userPassword}}"}
```

200이면 로그인에 성공한 것이다. 클라이언트가 교체된 세션 쿠키를 보관하는지 확인한다. **다시 `GET {{baseUrl}}/api/v1/csrf`를 호출해 `csrfToken`을 새 값으로 교체한다.** 로그인 전 토큰을 그대로 주문 요청에 쓰지 않는다.

`GET {{baseUrl}}/api/v1/members/me`가 본인 정보를 반환하면 세션 인증까지 연결된 것이다. 로그인을 했는데 401이면 요청에 로그인 후 쿠키가 포함됐는지 먼저 확인한다.

## 4. 주문 만들기

`GET {{baseUrl}}/api/v1/sales/{{saleId}}`로 준비한 이벤트의 상태가 OPEN인지 확인한다. SCHEDULED이면 시작 시각까지 기다린다. 공개 응답에는 남은 수량이 없으며 OPEN이 주문 성공을 보장하지는 않는다.

이번 구매 시도용 UUID를 한 번 생성해 `orderRequestKey` 변수로 보관한다. macOS에서는 터미널의 `uuidgen` 결과를 복사할 수 있다. **같은 주문의 재시도 때는 변수를 바꾸지 않는다.** 매 요청마다 UUID를 생성하는 동적 변수를 헤더에 직접 넣지 않는다.

```http
POST {{baseUrl}}/api/v1/orders
Content-Type: application/json
X-CSRF-TOKEN: {{csrfToken}}
Idempotency-Key: {{orderRequestKey}}

{"saleId":{{saleId}},"quantity":1}
```

201의 `data.orderId`를 `orderId` 변수에 저장한다. `status`는 AWAITING_PAYMENT다. 수량·단가·총액을 보고 서버 계산 결과를 확인한다. PG 키나 결제 성공 응답은 오지 않는다.

409라면 응답 `code`를 읽는다. `SALE_OUTSIDE_PERIOD`, `SALE_STOPPED`, `PURCHASE_LIMIT_EXCEEDED`, `SALE_SOLD_OUT` 중 무엇인지에 따라 조건을 확인한다. 업무 거절도 해당 키에 고정되므로 조건을 바꾼 뒤 새로 시도할 때는 새 키가 필요하다.

## 5. 조회하고 같은 요청을 재전송하기

```http
GET {{baseUrl}}/api/v1/orders/{{orderId}}
```

본인 주문이면 200과 생성한 주문 정보가 나온다. 이어서 4번 요청을 **같은 키·같은 saleId·같은 quantity**로 다시 보낸다. 201 대신 200이고 주문 UUID가 같은지 확인한다. 새 주문이 생긴 것은 아니다.

이번에는 같은 키로 quantity를 바꿔 보내면 409 IDEMPOTENCY_CONFLICT가 나와야 한다. 현재 주문 수량을 바꾸는 기능이 아니기 때문이다. 처음 quantity가 1이었으면 2로 바꾸는 식으로 확인한다.

## 6. 실패 경로도 확인하기

| 해볼 요청 | 기대 결과 | 확인하는 규칙 |
|---|---|---|
| 새 클라이언트에서 쿠키 없이 내 정보 GET | 401 | 세션 인증 필요 |
| 로그인 쿠키는 유지하고 주문의 CSRF 헤더 제거 | 403 | 인증과 별개로 변경 요청 보호 |
| 주문 키를 UUID가 아닌 값으로 변경 | 400 | 입력 오류는 주문 생성 전 거절 |
| 본문에 price 필드 추가 | 400 | 클라이언트가 금액을 지정하지 않음 |
| 다른 사용자 세션으로 앞 주문 UUID 조회 | 404 | 주문 소유권 보호 |

마지막 사례는 별도 계정을 가입·로그인한 다른 쿠키 저장소로 확인한다. 첫 계정의 세션을 덮어쓰지 않게 클라이언트 환경을 분리한다.

## 여기서 멈추는 이유

현재는 주문에 만료 시각이 있어도 자동 정리되지 않는다. 5분 뒤 EXPIRED가 되거나 재고가 자동으로 돌아오는 것을 기대하면 안 된다. PG 자체 결제 API는 동작하지만, 이 주문에 대한 판매 앱의 사용자 결제 연동과 복구 실행기는 다음 단계다. 개발 DB 볼륨을 지우는 방법으로 정리하지 말고 격리된 [통합 테스트](operations.md)로 반복 검증한다.

다음에 만들 결제 성공·거절·응답 유실·만료 흐름은 [전체 구매 흐름](purchase-flow.md)과 [실패 사례](failure-cases.md)에서 확인한다.

주문을 만든 후 ADMIN으로 해당 주문의 시나리오를 설정하는 기능과 PG 자체를 따로 실험하는 방법은 [Mock PG 안내](mock-pg.md)를 따른다. 시나리오 설정만으로 주문이 결제되지는 않는다.
