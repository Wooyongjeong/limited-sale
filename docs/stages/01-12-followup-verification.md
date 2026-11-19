# 1-12 1단계 후속 검증

상태: 증거 확인·보강·재측정 완료. 새 정책은 정하지 않았다. 사용자가 남은 항목의 대조·체크·보강·머지를 "1번부터 4번까지 진행해"라고 지시해 [1단계 정책](01-mysql-order.md)의 "정책 결정과 후속 검증 체크리스트"에 남은 8개 항목을 서브에이전트(Sonnet)가 검증했다.

## 범위

- 열린 8개 항목마다 이미 있는 테스트·문서·측정이 그 동작을 실제로 단언하는지 확인한다. 코드를 읽었다는 사실만으로 충족으로 세지 않는다. 점검 항목은 점검 결과를 기록한다.
- 근거가 없는 곳은 가장 작은 동작 수준 테스트나 측정으로 채운다.
- 점검에서 계약 불일치가 나오면 작고 기존 정책 안에 있는 것만 고치고 나머지는 후속 과제로 남긴다.

## 항목별 근거

| # | 항목 | 근거 | 보강 |
|---|---|---|---|
| 1 | 1-7 복구 임대 시간·배치 크기·PG 통신 오류·동시성 | `OrderPaymentIntegrationTest`의 `twoNodesClaimOnceAndRunDifferentOrdersInParallel`, `lockedCandidateDoesNotBlockAnotherDueOrder`, `staleTokenCannotChangePaymentOrTheNewLeaseInMysql`, `failedCommunicationDoesNotReleaseAnExpiredOrder`, `recoveryRetainsStockDuringOutageAndConvergesAfterPgReturns`, `recoveryCandidateQueryHasItsSupportingIndex`. `OrderRecoverySchedulerTest`(동시 용량·설정 검증). [1-7-4](01-7-4-order-recovery-and-leases.md), [1-7-5](01-7-5-order-payment-recovery-regression.md)의 11분 PG 중단·강제 종료 실험 | 회당 후보 상한(batch-size)을 실제 MySQL에서 검증하는 테스트가 없었다. `candidateBatchIsCappedAndTakesTheEarliestDueOrdersFirst` 추가 |
| 2 | 공개 인터페이스 시그니처·입출력 점검 | 점검 항목. 아래 "점검 결과" | `ArchitectureTest`에 api 패키지의 프레임워크·JPA·HTTP·Redis·Kafka 타입 의존 금지 규칙과 위반 fixture 추가. 계약 문서의 인터페이스 이름을 구현에 맞춤 |
| 3 | 제약·인덱스, 동시 요청·키 충돌 | `MySqlSchemaIntegrationTest.rejectsUniqueForeignKeyAndCheckViolations`, `differentMembersMayUseTheSameRequestKey`, `paymentPgKeyIsUniqueAcrossDifferentOrders`. `OrderIntegrationTest.sameKeyRacesReplayOrConflictAndAreScopedToMember`, `sameKeyConcurrentlyBelongsIndependentlyToDifferentMembers`, `sameKeyDifferentQuantitiesAndRejectedVersusCreatedRacePreserveWinner`, `otherUniqueConstraintFailuresAreNotTreatedAsRequestConflicts`. 1-7-5 같은 키 재시도 3회 | 없음 |
| 4 | CHECK·인덱스, 금액 곱셈과 범위 경계 | `MySqlSchemaIntegrationTest`(단가·금액 상한 초과, 합계 불일치 거절), `OrderIntegrationTest.maximumPriceAndQuantityKeepExactLongAmountAcrossOrderAndPayment`(10억원×100만개=10^15원이 주문·결제·응답에서 같음), `rejectsUnauthenticatedCsrfAndStrictInputWithoutRecordingKey`(수량 0·1,000,001), `SaleIntegrationTest.strictInputsRejectInvalidFieldsAndDatesWithoutSaving`·`ProductIntegrationTest`(가격 0·10억+1) | 없음 |
| 5 | 잠금 SQL·대기 후 시간 판정·경쟁 | `OrderIntegrationTest.locksBeforeReadingDatabaseTimeAndObservesConcurrentStop`, `SaleIntegrationTest.waitingForLockPastStartRejectsTermsChangeAndDeletion`, `OrderPaymentIntegrationTest.databaseTimeIsReadAfterPaymentRowLockWait`, `finalizationRollsBackWhenLeaseExpiresDuringSaleLockWait` | 없음 |
| 6 | 길이·형식·유니크, Argon2id 비용 | `ProductIntegrationTest`(이름 100/101, 설명 2,000/2,001), `MemberIntegrationTest.emailLengthBoundaryIsEnforcedAfterNormalization`·`simultaneousEmailAndNicknameAreSettledByDatabase`, `MemberInputTest`, `SaleTest.acceptsCodePointAndNumericUpperBoundsButRejectsInvalidFinalValues`. 앱의 실제 해시기가 19MiB·반복 2·병렬도 1을 쓰는 것은 `AuthenticationServiceTest.realDummyHashUsesConfiguredCostAndIsReusedOutsideTransaction`이 단언한다. 비용 측정은 [1-3 측정](01-3-argon2-measurement.md)(Apple M1) | 이 컴퓨터에서 설정 비용으로 재측정. 아래 "Argon2id 재측정" |
| 7 | enum 코드·메시지·필드 오류 경계 | 각 통합 테스트의 field/reason 검사(`invalidField` 등), 400·415·500 공통 코드 단언 | 성공·오류 코드 26개가 HTTP 응답에서 한 번도 단언되지 않았다(상태 코드만 확인). 테스트 지원 클래스 `ResponseEnvelope.assertCode`를 만들어 상태·본문 httpStatus·code·message를 열거형과 대조하고 기존 통합 테스트의 해당 호출에 적용. 열거형 전체 구조를 검사하는 `ResponseCodeContractTest` 추가 |
| 8 | 1-2 테이블·인덱스·길이·JPA 매핑 재점검 | `application.yml`의 `ddl-auto: validate`와 `--spring.jpa.hibernate.ddl-auto=validate`로 Flyway를 적용한 실제 MySQL에 앱을 띄우는 통합 테스트들, `MySqlSchemaIntegrationTest.jpaRoundTripsUuidEnumUtcAndMaximumAmount`·`pgJpaRoundTrips`, [1-2 문서](01-2-mysql-schema-and-boundaries.md)의 독립 검증 | 없음 |

## 점검 결과 (항목 2, 7)

점검한 대상은 `member`·`product`·`sale`·`order`·`payment`의 `api` 패키지 전체와 도메인별 응답 코드 열거형 7개다.

- 확인한 것. 모든 api 타입이 `java.time`·`java.util` 외에 아무것도 가져오지 않고 다른 도메인 타입을 참조하지 않는다. 공개 값은 record 또는 불변 목록(`List.copyOf`)이다. Spring Data `Page`, JPA, HTTP 타입은 없다.
- 불일치 1, 수정함. [1-1 계약](01-contracts.md)과 [구조 문서](../architecture.md)가 초안 이름(MemberRegistration, MemberAuthentication, MemberQuery, OrderCommand, OrderQuery, PaymentCommand, PaymentQuery, PaymentRecovery)을 공개 계약으로 적었지만 구현되지 않았다. 구현된 것은 member `MemberIdentity`, order `OrderLookup`, payment `PaymentGateway`·`PaymentOperations`·`PaymentPreparation`·`PaymentSimulation`이다. 다른 도메인이 호출하지 않는 유스케이스에는 api를 두지 않는다는 기존 원칙과 일치하므로 두 문서의 표를 구현에 맞췄다.
- 불일치 2, 후속 과제. 같은 코드 문자열 `SALE_STOPPED`가 이벤트 중단 성공(200, `SaleResponseCode`)과 중단된 이벤트에 대한 주문 거절(409, `OrderResponseCode`)에 함께 쓰인다. HTTP 상태와 경로로는 구분되지만 코드만 보는 클라이언트는 구분할 수 없다. 주문 거절 코드는 `order_request_results`에 보관되어 재전송 응답이 되므로 이름을 바꾸는 것은 정책 결정이다. `ResponseCodeContractTest`가 이 하나만 허용 목록으로 고정해 새로운 충돌을 막는다. 사용자 결정이 필요하다.
- 불일치 3, 후속 과제. `OrderResponseCode`와 `SimulationResponseCode`가 계약 문서가 말하는 `presentation.code` 패키지가 아니라 `order.presentation`에 있다. 파일 이동과 위키 링크 수정이 따르는 리팩터링이어서 이번에는 옮기지 않았다.
- 불일치 4, 후속 과제. [구조 문서](../architecture.md)의 "디렉터리 제안" 트리(`ProductView`, `OrderCommand`, `OrderResult` 등)와 호출 예시가 구현과 다르다. 제안 문서로 남겨 두었고 이번에 고치지 않았다.
- 관찰. `SaleDetails.status`, `PaymentDetails.status`는 문자열이다. 문서가 정한 값 집합과 어긋나는 구현은 찾지 못했다.

## Argon2id 재측정 (항목 6)

기존 [1-3 측정](01-3-argon2-measurement.md)의 벤치마크(`benchmarks/argon2`, 설정 `Argon2PasswordEncoder(16, 32, 1, 19456, 2)`, 앱의 `Argon2PasswordHasher`와 같은 값)를 이 컴퓨터에서 5번 실행했다. 실행마다 워밍업 30회 후 encode·matches 각 200회다. 다른 작업을 통제하지 않았고 실행은 순차였다.

환경은 Apple M4 Pro 14코어, 메모리 48GB, macOS 26.7, Azul Zulu 21.0.10(aarch64), 기본 힙이다. Spring Security Crypto는 Spring Boot 3.5.16 관리 버전이다.

| 연산 | 실행별 p50 (ms) | p50의 중앙값 | p50 범위 | p95 범위 | 최솟값~최댓값 (전체 1,000회) |
|---|---|---:|---|---|---|
| encode | 18.457, 18.117, 18.394, 18.383, 18.374 | 18.383 ms | 18.117~18.457 | 20.057~20.515 | 16.239~22.989 ms |
| matches | 18.420, 18.169, 18.379, 18.397, 18.400 | 18.397 ms | 18.169~18.420 | 19.938~20.754 | 16.174~24.737 ms |

1-3의 M1 측정(p50 약 90ms)보다 약 5배 빠르다. 하드웨어 차이이며 설정은 같다. 이 수치는 단일 스레드 단일 호출의 로컬 측정이다. 동시 로그인 처리량과 메모리 사용을 나타내지 않는다. 1-11 부하 실험은 로그인을 부하 구간 밖에서 했으므로 이 비용이 주문 수치에 섞이지 않는다.

## 검증 결과

- 변경한 테스트를 포함해 Java 21 `./gradlew clean build`를 실행했다. Maven Central에 연결되어 `--offline` 없이 실행했고 3분 49초에 `BUILD SUCCESSFUL`이었다. XML 결과는 판매 앱 단위 96개·통합 133개, Mock PG 단위 11개·통합 15개로 총 255개이며 실패·오류·건너뜀은 모두 0개다(이전 1-11은 94·132·11·15).
- 새 테스트가 실제로 잡는지 확인했다. `ResponseCodeContractTest`는 허용 목록을 비우면 `SALE_STOPPED` 충돌로 실패한다. `ArchitectureTest`의 위반 fixture는 `org.springframework` 타입을 가진 api 클래스를 거절한다.
- `python3 scripts/check-wiki-diagrams.py`, `git diff --check`를 실행했다.
- 위키는 사용자에게 보이는 사실이 바뀌지 않아 갱신하지 않았다.

## 남은 것

- `SALE_STOPPED` 코드 충돌의 처리 방향(이름 변경 여부)은 사용자 결정이 필요하다.
- 응답 코드 열거형 두 개를 `presentation.code`로 옮길지, 계약 문서를 현재 위치에 맞출지 정해야 한다.
- 구조 문서의 디렉터리 제안 트리와 호출 예시를 현재 구현에 맞춰 정리해야 한다.

## 체크리스트

- [x] 열린 8개 항목의 근거 확인
- [x] 근거가 없는 곳의 테스트·재측정 보강
- [x] 점검 결과와 후속 과제 기록
- [x] 1단계 체크리스트 갱신
- [x] 전체 build·위키 도식 검사·diff 검사
