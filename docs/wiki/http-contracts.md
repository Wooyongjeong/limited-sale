# HTTP 요청·응답과 내부 계약

[위키 시작](README.md) · [구조와 경계](architecture.md)

외부 HTTP 요청과 응답은 각 도메인의 `presentation/request`, `presentation/response`에 별도 파일로 둔다. `SessionController.Login`처럼 컨트롤러 안에 선언했던 record는 `LoginRequest`로 옮겼다. CSRF 응답은 `CsrfTokenResponse`다. 컨트롤러는 내부 서비스나 도메인 값을 받은 뒤 응답 DTO로 변환한다.

파일을 나눈 이유는 HTTP 필드와 내부 모델이 서로 다른 이유로 바뀌기 때문이다. 내부 조회 값에 필드가 추가돼도 응답 DTO에 명시하지 않으면 외부 JSON에는 추가되지 않는다. DTO 분리는 접근 권한 검사를 대신하지 않으며, 주문 소유권과 인증·CSRF 검사는 기존 위치에서 수행한다.

```mermaid
flowchart LR
    J[HTTP JSON] --> R[presentation.request의 Request]
    R --> C[컨트롤러]
    C --> A[application 또는 내부 api]
    A --> D[domain과 저장소]
    A --> V[내부 결과 값]
    V --> M[컨트롤러와 Response.from 변환]
    M --> S[presentation.response의 Response]
    S --> W[ApiResponse와 HTTP JSON]
```

## 파일에서 확인할 경계

| 흐름 | 외부 요청 | 내부 결과 | 외부 응답 |
|---|---|---|---|
| 회원 가입 | `MemberRegistrationRequest` | `Member` | `MemberResponse` |
| 로그인 | `LoginRequest` | 인증 기록과 `Member` | `MemberResponse` |
| CSRF 조회 | 본문 없음 | Spring `CsrfToken` | `CsrfTokenResponse` |
| 제품 등록 | `ProductRegistrationRequest` | `ProductDetails` | `ProductDetailsResponse` |
| 제품 목록 | page·size 쿼리 | `ProductPage` | `ProductPageResponse`와 `ProductListItemResponse` |
| 판매 생성·수정 | `SaleRequest` | `SaleDetails` | `SaleDetailsResponse` |
| 판매 목록 | 페이지·필터 쿼리 | `SalePage` | `SalePageResponse`와 `SaleListItemResponse` |
| 주문 생성 | `OrderRequest`와 요청 키 헤더 | `OrderView` | `OrderViewResponse` |
| 필드 검증 오류 | 해당 요청 | 내부 오류 상세 | 공통 `FieldErrorResponse` 목록 |

`api` 패키지는 도메인 간 Java 호출 계약이다. 예를 들어 `ProductQuery`가 반환하는 `ProductDetails`는 HTTP DTO가 아니다. [ProductDetailsResponse.from](../../src/main/java/com/wooyongjeong/limitedsale/product/presentation/response/ProductDetailsResponse.java)이 필요한 필드를 옮긴다. 페이지 응답도 내부 항목을 각 응답 DTO로 변환한다.

[MemberResponse](../../src/main/java/com/wooyongjeong/limitedsale/member/presentation/response/MemberResponse.java)의 role은 문자열이다. `MemberRole`의 `name()`을 경계에서 변환하므로 응답 DTO의 필드 타입이 도메인 enum에 의존하지 않는다. [FieldErrorResponse](../../src/main/java/com/wooyongjeong/limitedsale/common/web/FieldErrorResponse.java)는 여러 도메인이 같은 HTTP 구조인 field·reason을 사용하기 때문에 `common.web`에 둔다. 공통 응답 틀인 `ApiResponse<T>`도 이 위치를 유지한다.

## JSON 검증과 민감 값 처리

파일 이동 후에도 기존 HTTP 필드명·타입·null·상태 코드와 페이지·UUID·UTC 표현은 같다. 요청을 단순한 기본 Jackson record 바인딩으로 바꾸지는 않았다. [MemberJson](../../src/main/java/com/wooyongjeong/limitedsale/member/presentation/request/MemberJson.java)과 제품·판매·주문 요청의 역직렬화 코드가 기존 중복 필드·추가 JSON·타입 검사 동작을 유지한다.

[SaleRequest](../../src/main/java/com/wooyongjeong/limitedsale/sale/presentation/request/SaleRequest.java)와 [OrderRequest](../../src/main/java/com/wooyongjeong/limitedsale/order/presentation/request/OrderRequest.java)는 JSON 트리를 담고, 컨트롤러의 도메인별 파서가 필드를 검사한다. 판매 생성과 PATCH는 같은 본문 타입을 쓰되 허용 필드와 필수 여부를 각각 검사한다. 특히 PATCH는 생략한 값과 명시적 null의 의미를 유지한다. DTO 파일이 분리되었다고 모든 필드가 Java record 구성요소로 선언된 것은 아니다.

[LoginRequest](../../src/main/java/com/wooyongjeong/limitedsale/member/presentation/request/LoginRequest.java)와 [MemberRegistrationRequest](../../src/main/java/com/wooyongjeong/limitedsale/member/presentation/request/MemberRegistrationRequest.java)는 `toString()`에서 자격 증명을 가린다. 새 요청 필드를 추가할 때도 문자열 출력에 비밀번호를 포함시키지 않는다.

[HttpContractStructureTest](../../src/test/java/com/wooyongjeong/limitedsale/architecture/HttpContractStructureTest.java)는 컨트롤러의 RequestBody 타입·중첩 요청/응답과 반환 제네릭·응답 record 구성요소의 내부 타입 노출을 검사한다. 잘못된 선언 사례도 검사해 규칙이 위반을 찾는지 확인한다. `ApiResponse<?>`처럼 실제 값의 타입을 서명이 표현하지 않는 경우는 구조 검사만으로 입증할 수 없으므로 실제 HTTP 계약 테스트와 컨트롤러 매핑 리뷰도 필요하다. 실제 JSON 동작 검증은 각 도메인의 통합 테스트가 담당한다.
