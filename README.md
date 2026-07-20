# Spring 부하테스트

수강신청과 티켓팅은 많은 사용자가 같은 자원을 동시에 요청한다. 이런 시스템에서는 처리량뿐 아니라 정원을 초과하지 않는 정합성이 중요하다. 이번 테스트에서는 Spring Boot, JPA, MySQL로 구현한 수강신청 API에 비관적 락을 적용하고, 성능을 제한하는 지점을 k6와 Prometheus 메트릭으로 확인했다.

테스트 과정에서 확인한 핵심은 세 가지다.

1. JVM을 예열하지 않은 결과는 애플리케이션의 정상 성능을 대표하지 않았다.
2. JPA 양방향 컬렉션 전체 조회를 집계 쿼리로 바꾸자 처리량이 두 배 이상 증가했다.
3. HikariCP Pool을 늘리면 대기 위치만 Hikari에서 MySQL row lock으로 이동했다.

## 부하테스트

테스트 환경은 다음과 같다.

| 항목 | 설정 |
|---|---|
| 애플리케이션 | Spring Boot 4.0.6, Java 25 |
| ORM | Spring Data JPA, Hibernate |
| 데이터베이스 | MySQL 9, InnoDB |
| 부하 도구 | k6 |
| 모니터링 | Prometheus, Grafana, Micrometer, mysqld-exporter |
| 학생 수 | 10,000명 |
| Hot Course 정원 | 10,000명 |
| 기본 HikariCP Pool | 10 |

테스트 대상은 수강신청 API다.

```http
POST /enrollments
Content-Type: application/json

{
  "studentId": 1,
  "courseId": 1
}
```

트랜잭션은 Student와 Course를 `PESSIMISTIC_WRITE`로 조회한 뒤 정원, 총 학점, 시간표 충돌을 검사하고 Enrollment를 저장한다.

```text
Student 잠금
→ Course 잠금
→ 정원 COUNT
→ 총 학점 SUM
→ 시간표 충돌 확인
→ Enrollment INSERT 및 COMMIT
```

모든 요청을 하나의 `courseId=1`에 집중시켰다. 이는 서로 다른 Course 행을 병렬 처리하는 일반적인 수강신청 부하가 아니라, 하나의 인기 과목에 요청이 몰리는 Hot Course 최악 조건이다.

```js
export const options = {
  vus: 100,
  duration: '30s',
};

const studentId = (__ITER * 100 + __VU) % 10000 + 1;
const courseId = 1;
```

학생 ID는 10,000개 범위에서 순환한다. 이번 측정은 최대 8,296건에서 끝났고 `409 Conflict`가 0건이었으므로 실제 측정 구간에서는 중복이 발생하지 않았다. 따라서 이 문서의 RPS는 빠른 409가 섞인 전체 요청량이 아니라 실제 INSERT와 COMMIT이 발생한 성공 트랜잭션 처리량이다.

이 테스트는 VU가 응답을 받은 뒤 다음 요청을 보내는 closed model이다. 응답시간이 증가하면 요청 생성률도 낮아지므로, 결과는 현재 구조의 상대 비교에는 유효하지만 최대 지속 가능 TPS를 확정하는 값은 아니다.

## JVM Cold Start

동일한 애플리케이션 프로세스에서 Enrollment 데이터만 초기화하며 세 번 연속 실행했다. 첫 실행은 Cold, 이후 실행은 Warm으로 구분했다.

| 상태 | 성공 요청 | Created TPS | 평균 | 중앙값 | p90 | p95 | 최대 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Cold | 5,934 | 195.12 | 507.64ms | 456.50ms | 606.67ms | 757.32ms | 1.68초 |
| Warm 1회차 | 7,825 | 257.28 | 384.41ms | 388.18ms | 421.46ms | 435.03ms | 771.89ms |
| Warm 2회차 | 8,296 | **272.48** | **363.21ms** | **364.01ms** | **419.53ms** | **425.04ms** | 802.67ms |

Cold 대비 Warm 2회차는 처리량이 39.6% 증가했고 평균 응답시간은 28.5%, p95는 43.9% 감소했다.

Prometheus 메트릭으로 JVM과 요청 시간을 분해하면 원인이 더 명확하다.

| 지표 | Cold | Warm 1회차 | Warm 2회차 |
|---|---:|---:|---:|
| JIT compilation 증가 | 31,350ms | 9,224ms | 257ms |
| 신규 class loading | 1,396개 | 5개 | 1개 |
| 애플리케이션 CPU 최대 | 17.22% | 7.97% | 4.40% |
| Hikari 커넥션 획득 평균 | 448.75ms | 342.95ms | 323.65ms |
| 커넥션 사용 평균 | 50.00ms | 38.11ms | 36.04ms |
| Course 락 쿼리 평균 | 44.26ms | 34.04ms | 32.23ms |
| GC pause 합계 | 88ms | 69ms | 50ms |

Cold에서는 부하가 진행되는 동안에도 JIT compilation과 class loading이 집중됐다. 이때 트랜잭션의 커넥션 점유 시간과 Course row-lock 시간이 함께 길어졌고, 커넥션 반환이 늦어져 Hikari 대기도 증가했다.

반면 MySQL 지표는 세 실행에서 거의 같았다.

| MySQL 지표 | Cold | Warm 1회차 | Warm 2회차 |
|---|---:|---:|---:|
| Buffer Pool physical read | 19회 | 0회 | 0회 |
| 요청당 logical read | 58.81회 | 62.35회 | 63.58회 |
| 요청당 fsync | 1.06회 | 1.05회 | 1.05회 |
| Redo log wait | 0 | 0 | 0 |
| Hikari timeout | 0 | 0 | 0 |

Warm에서 logical read가 감소하지 않았고 Cold에서도 physical read는 19회뿐이었다. 요청당 SQL 작업량도 약 `SELECT 5회 + INSERT 1회 + COMMIT 1회`로 동일했다. 따라서 성능 차이의 주원인은 MySQL Buffer Pool warm-up이나 커넥션 생성이 아니라 JVM JIT와 클래스 초기화였다.

두 번의 독립적인 실행 세트에서 Warm 2회차는 `263.19~272.48 TPS`, 평균 `363.21~363.89ms`, p95 `420.25~425.04ms`로 재현됐다. 이후 비교에서는 Cold 결과가 아니라 Warm 상태를 기준으로 사용했다.

## JPA 양방향 연관관계

최초 구현은 엔티티의 양방향 컬렉션을 순회해 검증했다.

```java
course.isFull();
student.getTotalCredits();
student.hasConflict(course);
```

`course.isFull()`이 `enrollments.size()`를 호출하면 초기화되지 않은 LAZY 컬렉션 전체를 조회한다. Enrollment 생성자에서 양쪽 컬렉션에 자신을 추가하는 코드도 저장 시점에 컬렉션을 다시 초기화할 수 있었다.

```java
student.getEnrollments().add(this);
course.getEnrollments().add(this);
```

Hot Course의 Enrollment가 증가할수록 한 요청이 읽고 객체로 만드는 행 수도 증가한다. 이번 30초 Warm 테스트는 3,584건을 저장하는 동안 요청당 logical read가 5,399.85회, 처리량이 113.95 TPS였다. 이전 60초 테스트에서는 Enrollment가 5,123건까지 쌓이면서 요청당 logical read가 약 7,717회로 증가하고 처리량은 82.64 TPS까지 낮아졌다. 저장 건수가 1.43배 증가할 때 요청당 logical read도 1.43배 증가했으므로 컬렉션 크기에 따라 조회 비용이 선형으로 커지는 패턴과 일치한다.

검증 로직을 필요한 값만 조회하는 집계 쿼리로 변경했다.

```sql
SELECT COUNT(*)
FROM enrollment
WHERE course_id = ?;

SELECT COALESCE(SUM(c.credit), 0)
FROM enrollment e
JOIN course c ON c.id = e.course_id
WHERE e.student_id = ?;

SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END
FROM enrollment e
JOIN course c ON c.id = e.course_id
WHERE e.student_id = ?
  AND c.day_of_week = ?
  AND c.start_period <= ?
  AND c.end_period >= ?;
```

Enrollment에는 다음 인덱스를 사용하고 Student와 Course의 역방향 `@OneToMany` 컬렉션을 제거했다.

```text
idx_enrollment_course_id(course_id)
uk_enrollment_student_course(student_id, course_id)
```

동일한 `100 VU / 30초 / Pool 10 / 전 요청 201` 조건에서 충분히 예열된 실행끼리 비교한 결과는 다음과 같다.

| 지표 | 양방향 컬렉션 Warm | 집계 쿼리 Warm 2회차 | 변화 |
|---|---:|---:|---:|
| 성공 요청 | 3,584 | **8,296** | 4,712건 증가 |
| Created TPS | 113.95 | **272.48** | **2.39배** |
| 평균 | 854.13ms | **363.21ms** | **57.5% 감소** |
| p95 | 1.43초 | **425.04ms** | **70.3% 감소** |
| Hikari 획득 평균 | 763.51ms | **323.65ms** | **57.6% 감소** |
| 커넥션 사용 평균 | 86.67ms | **36.04ms** | **58.4% 감소** |
| Course 락 쿼리 평균 | 77.65ms | **32.23ms** | **58.5% 감소** |
| InnoDB row-lock 평균 | 77.29ms | **31.90ms** | **58.7% 감소** |
| 요청당 SELECT | 약 4.00회 | 약 5.00회 | 1회 증가 |
| 요청당 logical read | 5,399.85회 | **63.58회** | **98.8% 감소** |
| GC 횟수 | 74회 | **14회** | 81.1% 감소 |
| GC pause 합계 | 274ms | **50ms** | 81.8% 감소 |
| JIT compilation 증가 | 291ms | 257ms | 비슷함 |
| 신규 class loading | 0개 | 1개 | 비슷함 |

JIT compilation과 class loading이 거의 같은 수준이므로 JVM Cold Start가 결과 차이를 만든 것이 아니다. 집계 쿼리 적용 후 처리량은 2.39배 증가했고 평균 응답시간은 57.5%, p95는 70.3% 감소했다.

SELECT 수는 요청당 약 4회에서 5회로 오히려 증가했다. 하지만 최적화 전 SELECT 하나는 수천 건의 Enrollment를 읽어 엔티티로 만들었고, 최적화 후 쿼리는 인덱스로 COUNT, SUM, 존재 여부만 계산했다. 양방향 Warm 실행에서는 MySQL이 총 109.6MB, 요청당 30.6KB를 애플리케이션으로 전송했고 JVM은 총 9.02GB, 요청당 약 2.52MB를 할당했다. 성능은 쿼리 개수보다 각 쿼리가 읽고 전송하고 객체화하는 데이터양에 더 크게 좌우됐다.

컬렉션 전체 로딩을 제거하면서 트랜잭션과 커넥션 점유 시간이 짧아졌고, 그 결과 같은 Course 행을 기다리는 row lock과 Hikari 대기도 함께 감소했다. 정원, 학점, 시간표 및 중복 신청에 대한 기존 동시성 테스트도 모두 통과했다.

## HikariCP Pool

쿼리 최적화 후 Pool 10, 20, 30을 같은 Hot Course 조건에서 비교했다. Pool 20과 Pool 30은 애플리케이션을 한 번 예열한 Warm 1회차다.

| Pool | Warm 단계 | 성공 요청 | Created TPS | 평균 | p95 |
|---:|---|---:|---:|---:|---:|
| 10 | 1회차 | 7,825 | 257.28 | 384.41ms | **435.03ms** |
| 10 | 2회차 | 8,296 | **272.48** | **363.21ms** | **425.04ms** |
| 20 | 1회차 | 7,779 | 255.73 | 387.23ms | 441.49ms |
| 30 | 1회차 | 7,877 | **259.38** | **381.86ms** | 440.81ms |

같은 Warm 1회차끼리 비교하면 Pool 10~30의 처리량 차이는 최대 1.4%, p95 차이는 최대 1.5%다. 측정 편차 범위이므로 Pool을 늘려도 성능이 개선됐다고 볼 수 없다.

대신 Prometheus 메트릭에서는 대기 위치가 바뀌었다.

| 지표 | Pool 10 Warm 1 | Pool 20 Warm 1 | Pool 30 Warm 1 |
|---|---:|---:|---:|
| Hikari 획득 평균 | 342.95ms | 305.61ms | **255.26ms** |
| 커넥션 사용 평균 | **38.11ms** | 77.31ms | 114.46ms |
| Course row-lock 평균 대기 | **33.68ms** | 72.85ms | 110.06ms |
| Hikari pending 최대 | 90 | 79 | 70 |
| MySQL row-lock 동시 대기 최대 | 9 | 19 | 29 |
| MySQL 연결 수 | 11 | 21 | 31 |
| Hikari timeout | 0 | 0 | 0 |

Pool이 커질수록 Hikari에서 기다리는 요청은 줄지만, 더 많은 트랜잭션이 MySQL에 진입해 동일한 Course 행을 기다린다.

```text
Pool 10: Hikari 대기 343ms + DB 사용 38ms = 381ms
Pool 20: Hikari 대기 306ms + DB 사용 77ms = 383ms
Pool 30: Hikari 대기 255ms + DB 사용 114ms = 370ms
```

하나의 Course row lock은 동시에 한 트랜잭션만 보유할 수 있다. Pool을 30으로 늘려도 직렬 실행되는 임계 구역의 처리 속도는 바뀌지 않고, MySQL 연결과 row-lock 대기만 증가했다. Hikari pending은 독립적인 병목이라기보다 Course 락 앞에 형성된 대기열이었다.

따라서 단일 Hot Course에서는 Pool 10이 같은 처리량을 가장 적은 DB 자원으로 제공했다. 다만 실제 운영처럼 여러 과목을 동시에 처리하면 서로 다른 Course 행에서 병렬성이 생기므로, 최종 Pool 크기는 인기 과목 분포를 반영한 다중 과목 테스트로 다시 결정해야 한다.

## 그 외 메트릭들

성능 테스트에서 값이 존재하는 모든 메트릭이 병목은 아니다. 처리량이 포화될 때 자원이 한계에 도달했는지, 대기나 오류가 증가했는지, 설정을 바꿨을 때 결과도 변했는지를 함께 봐야 한다.

최신 Pool 10 Warm 2회차의 비병목 메트릭은 다음과 같다.

| 영역 | 측정값 | 판단 |
|---|---:|---|
| 애플리케이션 CPU | 최대 4.40% | 연산 포화 아님 |
| 시스템 CPU | 최대 6.85% | 시스템 자원 여유 |
| JVM GC | pause 합계 50ms, 최대 4ms | 긴 stop-the-world 없음 |
| Buffer Pool | 128MB 중 데이터 약 25.8MB | 용량 여유 |
| Physical read | 0회 | 디스크 읽기 병목 아님 |
| Logical read | 요청당 63.58회 | 최적화 후 안정적 |
| InnoDB data written | 약 99.3MB, 평균 약 3.3MB/s | 쓰기 대역폭 여유 |
| Redo log fsync | 8,672회, 요청당 약 1.05회 | 정상 COMMIT 비용 |
| Redo log wait | 0 | redo 공간 대기 없음 |
| Pending I/O | read/write/fsync 모두 0 | 저장장치 적체 없음 |
| MySQL 연결 | 최대 11 / 151 | 연결 한도 여유 |
| Disk temporary table | 0 | 디스크 임시 작업 없음 |
| Table cache | 4,000 중 170개, 신규 open 0 | 캐시 고갈 아님 |
| 네트워크 | 수신 43KB/s, 송신 46KB/s | 대역폭 병목 아님 |
| Spring Actuator scrape | 16.8~48.7ms, `up=1` | 모니터링 정상 |
| MySQL Exporter scrape | 79.5~191.6ms, `up=1` | exporter 정상 |

특히 Buffer Pool physical read가 0이라는 점이 중요하다. 양방향 컬렉션 구현의 높은 logical read는 디스크에서 데이터를 읽었다는 뜻이 아니라, 메모리에 있는 InnoDB 페이지를 과도하게 반복 탐색했다는 뜻이다. Buffer Pool을 늘리는 것으로는 이 문제를 해결할 수 없고 쿼리가 읽는 범위를 줄여야 했다.

CPU와 GC도 최종 처리 한계를 설명하지 못했다. Pool 10 Warm 2회차는 CPU가 10%에도 도달하지 않았고 GC pause 합계도 50ms에 불과했지만 Hikari active는 `10/10`, MySQL row-lock 동시 대기는 최대 9개였다. 실행 스레드는 CPU 계산이 아니라 Course 락을 기다리고 있었다.

## 결론

이 테스트에서 최적화 전의 첫 번째 병목은 JPA 양방향 컬렉션 전체 로딩이었다. Warm 실행끼리 비교했을 때 이를 COUNT, SUM, 충돌 확인 쿼리와 인덱스로 교체해 처리량을 `113.95 → 272.48 TPS`로 2.39배 높이고 요청당 logical read를 `5,399.85 → 63.58회`로 98.8% 줄였다. 평균 응답시간은 57.5%, p95는 70.3% 감소했다.

쿼리 최적화 후 남은 한계는 단일 Course 행의 비관적 락 직렬화다. HikariCP Pool을 10에서 30으로 늘려도 처리량은 약 `255~259 TPS`로 같았고, Hikari 대기가 줄어든 만큼 MySQL row-lock 대기가 증가했다. CPU, GC, Buffer Pool, 디스크, 네트워크는 현재 Hot Course 처리량을 제한하지 않았다.


>
```
docker --context default compose up --build  
docker --context mac-docker compose up --build  
k6 run k6.js
```
