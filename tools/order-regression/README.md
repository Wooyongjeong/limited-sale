# 주문·결제·복구 정합성 실험

이 도구는 전용 MySQL 8.4.7 두 개, Redis 7.4, Mock PG JVM과 판매 JVM 두 개를 만들고 소유한 자원만 정리한다. 기존 Compose 볼륨이나 서버를 사용하지 않는다. Python 3.9 이상과 Java 21, Docker가 필요하며 Python 외부 패키지는 사용하지 않는다. 먼저 현재 소스의 실행 JAR을 만든다.

```sh
./gradlew bootJar :mock-pg:bootJar
```

전체 실험은 오래 걸리므로 명시적으로 실행한다. 출력 디렉터리는 존재하지 않아야 한다. 1,000명 요청, 9회 실제 SIGKILL과 실제 11분 PG 중단이 포함된다. macOS에서는 `caffeinate`로 잠자기를 막는다. 잠자기 중에는 단조시계가 멈춰 측정이 틀어지며, 각 실행기는 벽시계와의 차이가 5초를 넘으면 실패한다.

```sh
caffeinate -ims python3 -B tools/order-regression/run_all.py --output /tmp/order-regression-RUN
```

개별 실행도 가능하다. `JAVA_HOME`은 Java 21을 가리켜야 한다.

```sh
python3 -B tools/order-regression/run_oversubscription.py --users 100 --output /tmp/oversub-RUN
python3 -B tools/order-regression/run_scenarios.py --users 1000 --output /tmp/scenarios-RUN
python3 -B tools/order-regression/run_forcekill.py --output /tmp/forcekill-RUN
python3 -B tools/order-regression/run_outage.py --output /tmp/outage-RUN
```

일반 시나리오는 사전 인증된 서로 다른 사용자와 시작 장벽을 사용한다. 재고 미만과 강제 종료는 지정된 더 작은 요청 집합을 사용한다. 거절 후 재사용은 먼저 재고를 점유하고 일부를 거절한 뒤, 기존 요청의 재생과 다른 사용자의 새 키를 함께 장벽에서 보낸다. 혼합 장애는 두 사용자 주문을 먼저 확보하고 나머지 사용자 요청과 그 두 재생을 장벽에서 보낸다. 강제 종료는 100명/재고 30 프로필에서 단일 대상 주문의 정확한 경계를 관찰한다.

출력에는 실행별 고정 manifest, 실제 전송 결과와 예외, DB 스냅샷, 수렴 관측 시각, 소유 자원 목록, JVM 로그와 요약을 보관한다. 쿠키·CSRF·비밀번호·API 키는 저장하지 않는다. 대량 원본은 Git에 추가하지 않는다. 클라이언트와 서버는 로컬 CPU·메모리를 공유하며 설정된 동시 출발 수는 서버 처리량이나 성능 보장이 아니다. 실패·누락·기한 초과는 0이 아닌 종료 코드로 보고한다.

빠른 검사와 개별 스냅샷 검사:

```sh
python3 -B -m unittest discover -s tools/order-regression -v
python3 -B tools/order-regression/verify.py /tmp/oversub-RUN/snapshot.json
python3 -B tools/order-regression/audit_outage.py /tmp/outage-RUN
```

검증기는 주어진 manifest와 수집 결과의 일관성을 검사한다. 실제 프로세스 종료, 사전 고정 명세, 단조시계 측정, 수집 시점과 환경의 신뢰성은 별도로 원본 증거와 대조해야 한다. 단일 스냅샷 검증기의 통과만으로 전체 실험을 완료한 것으로 판단하지 않는다.
