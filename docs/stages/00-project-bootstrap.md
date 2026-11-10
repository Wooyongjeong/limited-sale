# 0단계: 프로젝트 뼈대

작성일: 2026-11-10

[전체 기획](../project-plan.md) · [구조 설계](../architecture.md)

## 상태

뼈대 구현과 빌드 검증 완료. 실제 MySQL 연결 검증 대기. 이 기록은 0단계 검증 당시 결과이며 Docker의 현재 실행 상태를 뜻하지 않는다.

## 확정한 범위와 구성

- 기본 패키지는 `com.wooyongjeong.limitedsale`이다.
- Java 21, Spring Boot 3.5.16, Gradle Wrapper 8.14.3을 사용한다.
- MySQL 8.4.7을 로컬 Docker Compose로 실행한다.
- Spring Data JPA, Flyway와 Actuator health를 준비한다.
- `product`, `sale`, `order`, `common` 패키지를 준비한다. 도메인 간 통신은 공개 인터페이스로 제한한다.
- 업무 API·테이블, Redis·Kafka, 전체 모니터링은 이 단계에 포함하지 않는다.

## 구현과 검증 결과

| 작업 | 상태 | 근거 |
|---|---|---|
| Spring·Gradle 프로젝트, 프로파일, Wrapper | 구현 완료 | Java 21 `./gradlew clean build` 통과 |
| 최소 컨텍스트·HTTP health 테스트 | 검증 완료 | H2 기반 test 프로파일에서 통과 |
| Compose MySQL·환경변수 예제·실행 안내 | 구현 완료 | Compose 설정 검증 통과 |
| 실제 MySQL·Flyway 초기화·local health | 검증 대기 | 검증 당시 Docker 데몬 미실행 |

## 실행 체크리스트

- [x] 사용자 요청 범위와 기술 구성 정리
- [x] 사용자 구현 지시에 따라 Orca `gpt-6.1-terra / medium` 워커가 뼈대 구현
- [x] 주 에이전트가 변경 사항과 Java 21 빌드·H2 health·Compose 설정 검증 확인
- [x] 프로젝트 뼈대·문서 분리 커밋 및 PR #1 머지
- [ ] 실제 MySQL 연결·Flyway 초기화·local 프로파일 health 확인 및 근거 기록
- [ ] 모든 검증 결과를 보고하고 0단계 완료 상태 갱신

이미 완료한 항목의 근거는 위 검증 결과 표와 [PR #1](https://github.com/Wooyongjeong/limited-sale/pull/1)이다. 검증 대기 항목에는 정책 합의와 별도로 실행·검증 결과를 남긴다.

## 남은 작업과 완료 기준

Docker를 실행한 환경에서 MySQL을 기동하고 local 프로파일 애플리케이션이 DB에 연결되는지 확인한다. Flyway 초기화와 HTTP health까지 확인한 후 완료로 기록한다. H2 테스트를 MySQL 검증으로 대신하지 않는다. 실행 명령과 환경변수 준비는 [README](../../README.md)를 따른다.

## 미정 사항

0단계의 기술 구성에는 현재 추가 결정 사항이 없다. 업무 정책은 1단계에서 확정한다.
