# 외부 HTTP 계약 DTO 분리

## 확정 범위와 보존 기준

사용자가 정책과 구현 시작을 승인했다. 회원·세션·제품·판매·주문의 외부 요청과 응답을 도메인별 presentation/request와 presentation/response의 최상위 record로 분리하고, 내부 api·domain 계약은 HTTP 경계에서 변환한다. 필드별 오류는 HTTP 공통 응답으로 변환한다. 공통 ApiResponse와 내부 계약은 유지한다.

JSON 필드·타입·null·페이지·UUID·UTC 표현, 응답 코드, 인증·CSRF·멱등성과 엄격한 JSON 검증을 유지한다. 판매 PATCH의 생략과 명시적 null, 비밀번호 문자열 마스킹을 보존한다. 스키마와 결제 구현은 변경하지 않는다.

## 실행 체크리스트

- [x] 정책 확정 및 사용자 구현 시작 지시
- [x] 워커 DTO 분리 및 계약 회귀 검증
- [x] 주 에이전트 독립 코드 리뷰와 실행 검증
- [x] 주 에이전트 위키 갱신 및 검증

워커는 DTO 변환과 엄격한 요청 처리, 자격 증명 마스킹을 구현했다. 실제 HTTP 계약 검사는 기존 통합 검사와 회원 응답·오류 상세 보강으로 확인했다. 주 에이전트의 리뷰·독립 검증과 위키 갱신·검증도 완료했다.

## 리뷰 보완과 최종 검증

주 에이전트는 MemberResponse가 도메인 enum을 필드로 노출하는 부분을 지적했다. 같은 워커가 문자열 역할로 변환하고, HTTP 요청 타입 배치·중첩 DTO·응답 제네릭과 구성요소의 내부 타입 노출을 검사하는 HttpContractStructureTest를 추가했다. 위반 사례가 실제로 감지되는 것도 확인했다.

- 주 에이전트가 Java 21 `./gradlew clean build`를 독립 실행해 단위 40개·통합 63개, 총 103개가 통과했다. 실패·오류·건너뜀은 0개이며 로그는 `/tmp/limited-sale-http-dto-primary-build.log`다.
- [HTTP 계약 위키](../wiki/http-contracts.md)를 추가하고 목차·구조·회원·인증·제품·판매·주문·영속·운영 페이지를 최종 코드와 맞췄다. 모든 위키 로컬 링크를 확인하고 Mermaid 도식 21개를 렌더링했다. 새 변환 흐름은 이미지로도 확인했다. 결과는 `/tmp/limited-sale-wiki-render/http-dto/`다.
- `git diff --check`를 통과했다. 이 리팩터링은 기존 JSON·권한·검증·스키마를 유지한다. 구조 검사는 와일드카드 반환값의 실제 런타임 타입까지 입증하지 않으므로 HTTP 회귀 검사와 매핑 리뷰를 함께 사용한다.
- 작업 브랜치는 `refactor/http-contract-dto-separation`이다. 이번 지시는 리팩터링 구현 시작이며 이 브랜치의 커밋·푸시·PR·머지는 아직 수행하지 않았다.
