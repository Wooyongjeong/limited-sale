# 한정 수량 제품 판매 서비스

현재는 0단계 프로젝트 뼈대다. 업무 API와 테이블, Redis, Kafka 및 전체 모니터링은 아직 없다. [기획](docs/project-plan.md)과 [구조](docs/architecture.md)를 참고한다.

## 준비와 실행

Java 21 JDK와 Docker Compose가 필요하다. Spring Boot 3.5.16, Gradle Wrapper 8.14.3, MySQL 8.4.7을 사용한다. Gradle 실행에도 Java 21을 사용한다. 예를 들어 macOS에서 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`로 현재 셸에만 설정할 수 있다.

```sh
cp .env.example .env
# .env의 비밀번호를 로컬 전용 값으로 변경
set -a; source .env; set +a
docker compose up -d
# MySQL health가 healthy가 될 때까지 확인
docker compose ps
./gradlew bootRun
```

별도 터미널에서 `curl -f http://localhost:8080/actuator/health`로 확인한다. DB가 정상 연결되면 `UP`이 반환된다. 기본 프로파일은 `local`이며 위 환경변수를 사용한다. `.env`는 Compose에서 자동으로 읽지만 애플리케이션에는 자동으로 주입되지 않으므로 실행 셸에 내보내야 한다. 포트 충돌 시 `.env`의 `MYSQL_PORT`를 변경한다. MySQL 최초 생성 후 자격 증명이나 DB 이름을 바꾸어도 기존 볼륨에는 반영되지 않는다.

```sh
./gradlew clean build
docker compose config --quiet
docker compose down
```

테스트는 별도 `test` 프로파일과 메모리 H2를 사용하므로 Docker가 필요 없다. H2 테스트는 MySQL 호환성이나 실제 마이그레이션 검증을 대신하지 않는다. `docker compose down`은 데이터를 보존하며, 볼륨을 삭제하려면 데이터가 지워지는 `docker compose down -v`를 명시적으로 실행한다. Flyway가 스키마 변경을 관리하며 업무 스키마가 정해지기 전에는 마이그레이션을 추가하지 않는다.

버전 호환성: [Spring Boot 3.5 시스템 요구사항](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Gradle Java 호환성](https://docs.gradle.org/current/userguide/compatibility.html). Gradle 8.x는 Java 25에서 실행할 수 없으므로 기본 JDK가 25인 환경에서는 위와 같이 Java 21을 선택한다.
