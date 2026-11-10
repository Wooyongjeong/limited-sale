# 한정 수량 제품 판매 서비스

1-2 단계의 MySQL 스키마, 영속 매핑과 패키지 경계를 구현하고 검증했다. 회원·주문·결제 API와 Mock PG 업무 동작은 이후 단계에서 만든다. [기획](docs/project-plan.md), [구조](docs/architecture.md), [스키마 설계](docs/stages/01-2-mysql-schema-and-boundaries.md)를 참고한다.

## 준비와 실행

Java 21, Docker Compose가 필요하다. Spring Boot 3.5.16, Gradle 8.14.3, MySQL 8.4.7을 사용한다. macOS에서 Java 21을 선택하려면 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`을 실행한다. 시작 전 3306, 8080, 8081 포트 사용 여부를 확인하고 필요하면 `.env`의 포트를 바꾼다.

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

두 앱의 실행 셸에도 `set -a; source .env; set +a`가 필요하다. 판매 앱 포트는 `SERVER_PORT`로 바꿀 수 있다. 준비 스크립트는 기존 데이터를 삭제하지 않으며 기존 볼륨에도 실행해야 한다. 기존 계정의 비밀번호를 변경하지 않으므로 최초 준비 후 `.env` 자격 증명을 바꿀 때는 DB 계정을 별도로 관리해야 한다. Compose의 최초 초기화 환경변수도 기존 볼륨의 자격 증명을 변경하지 않는다. `.env`는 Git에 포함하지 않는다.

```sh
./gradlew test                       # 빠른 구조·단위 테스트
./gradlew integrationTest            # 격리된 Testcontainers MySQL 검증
./gradlew clean build                # 두 테스트 모두 포함, Docker 필수
```

Docker가 실행되지 않으면 통합 테스트는 실패한다. 테스트는 개발 DB를 초기화하지 않는다. `docker compose down`은 컨테이너를 중지하고 볼륨은 보존한다. Flyway는 스키마별 이력을 독립적으로 관리하며 적용된 마이그레이션 수정 대신 새 버전을 추가한다. 현재 Flyway는 MySQL 8.4 지원 경고를 출력하므로 실제 MySQL 검증 결과와 함께 확인해야 한다.

의존성: [Spring Boot 3.5 관리 버전](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html), [ArchUnit 사용 안내](https://www.archunit.org/userguide/html/000_Index.html), [Gradle Java 호환성](https://docs.gradle.org/current/userguide/compatibility.html).
