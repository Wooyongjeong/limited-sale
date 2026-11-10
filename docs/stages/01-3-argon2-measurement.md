# 1-3 Argon2id 로컬 측정

상태: **초기 비용 확정**. 사용자는 측정 결과를 확인하고 메모리 19MiB, 반복 2, 병렬도 1을 초기 설정으로 채택했다. 이후 회원 API 구현과 1-3 정책이 확정되었다.

## 측정 조건

- 시각: 2026-11-10 21:02 KST, 단일 실행.
- macOS 26.5.2, Apple M1, 논리 CPU 8개, 메모리 8GiB. 다른 시스템 작업을 통제하지 않은 로컬 환경이다.
- Homebrew OpenJDK 21.0.13.0.3, OpenJDK 64-Bit Server VM, aarch64. 벤치마크 JVM의 명시 옵션은 `-Dfile.encoding=UTF-8`, `-Duser.country=KR`, `-Duser.language=ko`, `-Duser.variant`이며 힙 크기는 기본값이다.
- Spring Boot BOM 3.5.16이 관리하는 Spring Security Crypto 6.5.11과 spring-jcl 6.2.19. Bouncy Castle `bcprov-jdk18on` 1.80은 BOM에 관리 항목이 없어 벤치마크에 별도로 지정했다. 애플리케이션의 의존성을 변경하지 않았다.
- `Argon2PasswordEncoder(16, 32, 1, 19456, 2)`의 Argon2id, salt 16바이트, 해시 32바이트. 고정된 가상 입력만 사용하며 입력과 해시는 출력하지 않는다.

인코더 생성, 참조 해시 생성과 Argon2id 설정·정상 일치·불일치 확인은 측정 구간 밖이다. encode와 matches를 각각 30회 워밍업하고, 단일 스레드에서 두 연산을 번갈아 각각 200회 측정했다. matches는 미리 생성한 동일한 해시를 검증한다. 시간은 `System.nanoTime()`으로 각 호출만 측정하며 p50과 p95는 정렬 후 nearest-rank 방식이다. 성공 결과도 각 호출 뒤에 확인한다.

## 결과

| 연산 | 표본 수 | p50 | p95 | 최솟값 | 최댓값 |
|---|---:|---:|---:|---:|---:|
| encode | 200 | 90.225 ms | 93.838 ms | 88.415 ms | 151.396 ms |
| matches | 200 | 90.252 ms | 94.458 ms | 88.183 ms | 124.560 ms |

Gradle 실행 결과는 `BUILD SUCCESSFUL`, 실행 작업 1개, 최신 상태 작업 1개다. 정상 일치와 잘못된 입력의 불일치, 인코딩된 결과의 Argon2id 파라미터도 실행 중 확인했다.

이 수치는 단일 JVM의 로컬 마이크로벤치마크이며 JMH가 아니다. CPU 부하, GC, 메모리, JVM과 하드웨어에 따라 달라진다. 동시 로그인 처리량이나 운영 서버 응답시간을 나타내지 않으며 대상 환경에서 동시 부하와 메모리 사용을 따로 검토해야 한다.

## 재현

Java 21과 네트워크 접근이 필요하다. Homebrew Java 21이 없다면 `brew install openjdk@21`로 설치할 수 있다. 아래 명령은 해당 프로세스의 `JAVA_HOME`만 지정하며 전역 기본값을 바꾸지 않는다. 다른 시스템에서는 Java 21 경로로 바꾼다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew -p benchmarks/argon2 --no-daemon --console=plain run
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew -p benchmarks/argon2 --no-daemon --console=plain dependencies --configuration runtimeClasspath
git diff --check
```

첫 명령은 독립 프로젝트의 [벤치마크 코드](../../benchmarks/argon2/src/main/java/Argon2Benchmark.java)를 실행한다. 두 번째 명령으로 실제 해석된 의존성 버전을 확인한다. 앱 빌드와 DB는 사용하지 않는다.

## 주 에이전트 독립 검증

동일 재현 명령을 Homebrew Java 21로 다시 실행했다. 각 연산 30회 워밍업·200회 측정, Argon2id 파라미터 및 정상/불일치 검증을 통과했고 `BUILD SUCCESSFUL in 53s`로 종료했다.

| 연산 | p50 | p95 | 최솟값 | 최댓값 |
|---|---:|---:|---:|---:|
| encode | 101.740 ms | 137.034 ms | 88.549 ms | 293.701 ms |
| matches | 101.729 ms | 132.115 ms | 88.362 ms | 462.400 ms |

워커 실행보다 지연과 편차가 컸다. 로컬 실행 조건에 따른 변동을 확인했으며 운영 처리량으로 해석하지 않는다. 이 결과를 검토한 뒤 사용자가 기준값 채택을 선택했다.

## 사용자 결정

선택형 UI에서 사용자가 현재 기준값 채택을 선택했다. 초기 비용은 메모리 19MiB·반복 2·병렬도 1이다. 측정한 비용으로 시작하고 동시 로그인 부하와 메모리 사용은 인증 단계에서 별도로 검증한다. 로컬 단일 호출 측정으로 동시 처리 성능을 보장하지 않는다.
