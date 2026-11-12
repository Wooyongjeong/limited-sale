# 한정 수량 제품 판매 서비스

회원가입·관리자 초기화와 Redis 세션 인증, 제품 등록·조회, 판매 이벤트 관리·조회를 구현했다. 주문·결제 API와 Mock PG 업무 동작은 이후 단계 범위다. [기획](docs/project-plan.md), [구조](docs/architecture.md), [스키마 설계](docs/stages/01-2-mysql-schema-and-boundaries.md)를 참고한다.

코드와 요청 흐름은 [개발자 위키](docs/wiki/README.md)에서 살펴본다.

## 세션 인증

`GET /api/v1/csrf` 응답의 `data.token`을 변경 요청의 `X-CSRF-TOKEN` 헤더에 넣고 세션 쿠키를 함께 보낸다. 회원가입과 `POST /api/v1/sessions` 로그인 전에도 토큰을 조회한다. 로그인 또는 `DELETE /api/v1/sessions/current` 성공 후 다음 변경 요청 전에 토큰을 다시 조회한다. `GET /api/v1/members/me`는 현재 회원의 이메일·닉네임·역할을 반환한다.

로그인 실패는 정규화 이메일과 직접 연결 IP별로 첫 실패부터 5분간 집계한다. 다섯 번째 실패부터 429이며 구간 만료 전 올바른 비밀번호도 차단된다. 제한 전에 성공하면 해당 조합의 기록을 지운다. 기본 유휴 세션 시간은 30분이며 `SPRING_SESSION_TIMEOUT`, 실패 구간은 `LIMITED_SALE_AUTH_FAILURE_WINDOW`, 실패 횟수는 양의 정수인 `LIMITED_SALE_AUTH_FAILURE_LIMIT`으로 조정할 수 있다. Redis 주소는 `REDIS_HOST`, `REDIS_PORT`로 설정한다. 주문의 미결제 만료 시간은 기본 5분이며 `LIMITED_SALE_ORDER_EXPIRATION`으로 양의 기간(예: `PT5M`)을 설정한다. 쿠키는 HttpOnly·SameSite=Lax이며 HTTPS 요청에는 Secure가 적용된다. Redis 장애 시 인증과 CSRF 처리는 503으로 실패하고 복구 뒤 재시도한다.

## 제품 등록과 조회

ADMIN 세션과 CSRF 토큰으로 `POST /api/v1/admin/products`에 `{"name":"제품명","description":"설명","basePrice":1000}`을 보낸다. 설명은 생략할 수 있다. `GET /api/v1/products?page=0&size=20`과 `GET /api/v1/products/{id}`는 공개 조회다. 목록은 ID 내림차순이며 size는 1~100이다. 제품명은 앞뒤 공백을 제거하고 설명은 원문을 보존한다. 같은 이름의 제품을 여러 번 등록할 수 있다. 상세 계약은 [1-5 문서](docs/stages/01-5-product-registration-and-query.md)를 참고한다.

## 판매 이벤트 관리와 조회

`POST /api/v1/admin/sales`는 productId·name·startsAt·endsAt·totalQuantity를 필수로 받는다. 시각은 오프셋이 있는 ISO 8601로 보내며 미래 시작만 가능하다. 가격과 구매 한도를 생략하면 제품 기준 가격과 1개를 사용한다. 생성·수정·삭제·중단·재개는 ADMIN 세션과 CSRF가 필요하다.

`GET /api/v1/sales?productId=1&status=SCHEDULED&page=0&size=20`과 `GET /api/v1/sales/{id}`는 공개 조회다. 수량은 총수량만 공개한다. PATCH는 조회한 version을 보내야 하며 충돌 시 409다. 시작 후에는 이름·설명만 수정할 수 있다. 중단·재개는 시작 전에도 가능하며 종료 후에는 거절한다. [전체 계약](docs/stages/01-6-sale-management-and-query.md)을 참고한다.

## 준비와 실행

Java 21, Docker Compose가 필요하다. Spring Boot 3.5.16, Gradle 8.14.3, MySQL 8.4.7, Redis 7.4를 사용한다. 시작 전 3306, 6379, 8080, 8081 포트 사용 여부를 확인하고 필요하면 `.env`의 포트를 바꾼다.

Apple Silicon macOS에서 Homebrew로 설치한 Java 21은 다음과 같이 현재 셸에서 선택하고 버전을 확인한다. 다른 환경에서는 설치한 Java 21의 경로로 `JAVA_HOME`을 지정한다. 이 설정은 전역 Java 기본값을 변경하지 않는다.

```sh
brew install openjdk@21                 # 설치되지 않은 경우
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version                          # 21 확인
```

```sh
cp .env.example .env
# .env의 세 비밀번호를 서로 다른 로컬 전용 값으로 변경한다.
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

Docker가 실행되지 않으면 통합 테스트는 실패한다. 테스트는 개발 DB를 초기화하지 않는다. `docker compose down`은 컨테이너를 중지하고 볼륨은 보존한다. Flyway는 스키마별 이력을 독립적으로 관리하며 적용된 마이그레이션 수정 대신 새 버전을 추가한다. 현재 Flyway는 MySQL 8.4 지원 경고를 출력하므로 실제 MySQL 검증 결과와 함께 확인해야 한다.

의존성: [Spring Boot 3.5 관리 버전](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html), [ArchUnit 사용 안내](https://www.archunit.org/userguide/html/000_Index.html), [Gradle Java 호환성](https://docs.gradle.org/current/userguide/compatibility.html).
