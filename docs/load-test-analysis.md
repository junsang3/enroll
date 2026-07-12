# Spring Boot + MySQL 수강신청 부하 테스트 분석

## 테스트 배경

수강신청이나 티켓팅 시스템은 동시에 많은 사용자가 같은 자원을 요청한다. 이때 가장 중요한 요구사항은 초과 신청을 막는 것이다. 이번 테스트에서는 Spring Boot, JPA, MySQL 환경에서 비관적 락을 사용한 수강신청 API가 어느 정도 처리량과 지연시간을 보이는지 확인했다.

테스트 대상 API는 다음 요청을 처리한다.

```http
POST /enrollments
Content-Type: application/json

{
  "studentId": 1,
  "courseId": 1
}
```

응답은 성공 시 `201 Created`, 정원 초과나 시간표 충돌 등 비즈니스 조건에 걸리면 `409 Conflict`를 반환한다. k6 체크 조건은 `201` 또는 `409`면 성공으로 보았다.

## 테스트 환경

| 항목 | 값 |
|---|---|
| Application | Spring Boot + JPA |
| Database | MySQL |
| Lock strategy | `PESSIMISTIC_WRITE` |
| Load tool | k6 |
| Monitoring | Prometheus + mysqld-exporter |
| VU | 500 |
| Duration | 30s |
| Hikari pool size | 10, 20, 30 비교 |

k6 스크립트는 매 요청마다 학생과 과목을 랜덤하게 선택한다.

```js
const studentId = Math.floor(Math.random() * 10000) + 1;
const courseId = Math.floor(Math.random() * 500) + 1;
```

초기 데이터는 학생 10,000명과 과목 500개다. 과목 시간이 동일하게 구성되어 있어 한 학생은 사실상 한 과목만 성공적으로 신청할 수 있다. 따라서 테스트 후반으로 갈수록 `409 Conflict` 비율이 높아진다.

## 애플리케이션 동시성 구조

수강신청 트랜잭션은 학생과 과목을 모두 비관적 락으로 조회한다.

```java
@Transactional
public Enrollment enroll(Long studentId, Long courseId) {
    Student student = studentRepository.findForUpdateById(studentId)
            .orElseThrow(...);
    Course course = courseRepository.findForUpdateById(courseId)
            .orElseThrow(...);

    if (course.isFull()) {
        throw new ResponseStatusException(HttpStatus.CONFLICT);
    }
    if (student.getTotalCredits() + course.getCredit() > MAX_CREDITS) {
        throw new ResponseStatusException(HttpStatus.CONFLICT);
    }
    if (student.hasConflict(course)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    return enrollmentRepository.saveAndFlush(new Enrollment(student, course));
}
```

이 구조는 정합성 측면에서는 단순하고 안전하다. 대신 요청마다 `Student`, `Course` row lock을 잡고, 트랜잭션 안에서 컬렉션을 순회하기 때문에 부하가 커지면 커넥션 풀 대기와 row lock 경합이 발생한다.

## Hikari Pool Size별 결과

### k6 결과 요약

| Hikari pool | RPS | Total reqs | Created | Conflict | Avg latency | p95 latency | 201 p95 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 2,323/s | 70,059 | 9,993 | 60,066 | 212ms | 378ms | 476ms |
| 20 | 2,774/s | 83,585 | 9,997 | 73,588 | 177ms | 339ms | 448ms |
| 30 | 2,379/s | 71,726 | 9,990 | 61,736 | 201ms | 405ms | 477ms |

pool size 20이 가장 좋은 결과를 보였다. pool size 10은 커넥션이 부족했고, pool size 30은 warm 상태에서는 cold run보다 크게 개선되었지만 pool size 20보다 처리량이 낮고 CPU와 row lock 경합 변동이 더 컸다.

### Prometheus 메트릭 비교

| 메트릭 | Pool 10 | Pool 20 | Pool 30 |
|---|---:|---:|---:|
| Hikari active peak | 10/10 | 20/20 | 30/30 |
| Hikari pending peak | 188 | 180 | 164 |
| `/enrollments` peak | 2,365/s | 3,140/s | 2,647/s |
| `201` peak | 380/s | 631/s | 614/s |
| `409` peak | 2,087/s | 3,105/s | 2,596/s |
| MySQL select peak | 13,546 qps | 14,122 qps | 11,073 qps |
| MySQL insert peak | 590 qps | 356 qps | 311 qps |
| row lock wait 증가 | 841 | 3,162 | 2,426 |
| process CPU peak | 47% | 68% | 61% |
| system CPU peak | 70% | 96% | 95% |

pool size 20에서는 처리량이 가장 높았지만 system CPU가 90% 이상까지 올라갔다. pool size 30 warm run은 cold run처럼 무너지지는 않았지만, pool size 20보다 전체 처리량이 낮고 system CPU가 비슷하게 높았다. 커넥션을 더 늘려도 성공 처리량이 크게 늘지 않았고, DB와 락 경합 비용이 같이 증가했다.

## Cold Start와 Warm-up 영향

같은 pool size 20에서도 cold start와 warm 상태의 차이가 컸다.

### k6 결과

| 상태 | RPS | Total reqs | Created | Conflict | Avg latency | p95 latency | 201 p95 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Cold | 984/s | 29,825 | 9,501 | 20,324 | 472ms | 993ms | 1.17s |
| Warm 2nd | 2,361/s | 71,165 | 9,995 | 61,170 | 202ms | 400ms | 488ms |
| Warm 3rd | 2,730/s | 82,295 | 9,996 | 72,299 | 171ms | 322ms | 350ms |

warm-up 이후 처리량은 약 2.7배 증가했고, p95 latency는 1초 수준에서 300ms 초반으로 내려갔다.

### JVM Warm-up 메트릭

| 메트릭 | Cold | Warm 2nd | Warm 3rd |
|---|---:|---:|---:|
| App uptime | 215s | 552s | 1,093s |
| JIT compilation 5m 증가 | 176,149ms | 57,199ms | 7,528ms |
| Class load 5m 증가 | 21,541 | 5 | 4 |
| Student lock query avg | 2.15ms | 0.88ms | 0.79ms |
| Course lock query avg | 2.87ms | 1.08ms | 0.97ms |
| saveAndFlush avg | 3.87ms | 1.25ms | 0.81ms |

cold run에서는 부하 테스트 중에도 JIT 컴파일과 클래스 로딩이 활발했다. warm run이 반복될수록 JIT 활동이 줄고 repository 호출 시간이 안정화되었다.

즉 성능 차이는 Hikari connection 생성이나 MySQL buffer pool warm-up 때문이 아니라 JVM warm-up 영향이 컸다.

## MySQL Buffer Pool은 병목이었나?

결론부터 말하면 이번 테스트에서는 MySQL buffer pool size가 병목이 아니었다.

peak 시점 기준 주요 메트릭은 다음과 같다.

| 메트릭 | 값 |
|---|---:|
| `innodb_buffer_pool_size` | 128 MB |
| buffer pool data | 25.6 MB |
| dirty data | 10.7 MB |
| logical reads/sec | 109,543/s |
| physical reads/sec | 0/s |
| buffer pool miss ratio | 0% |
| data read bytes/sec | 0 B/s |

buffer pool 크기는 128MB로 작지만 실제 사용 데이터는 약 25.6MB였고, physical read가 발생하지 않았다. 따라서 디스크 read 병목은 아니었다.

다른 MySQL 리소스도 병목으로 보이지 않았다.

| 항목 | 메트릭 | 판단 |
|---|---:|---|
| Redo wait | 0/s | 병목 아님 |
| Pending fsync | 0 | 병목 아님 |
| Disk temp table ratio | 0% | 병목 아님 |
| Opened tables/sec | 0/s | table cache 병목 아님 |
| MySQL max connections | 151 | 여유 있음 |
| Threads connected | 21 | 여유 있음 |
| Threads running | 9 | 과도하지 않음 |

MySQL 자체의 buffer pool, temp table, table cache, max connections보다 애플리케이션 트랜잭션 구조와 Hikari pool 포화가 더 중요한 병목이었다.

## 실제 병목

메트릭을 종합하면 병목은 다음 순서로 볼 수 있다.

### 1. Hikari Pool 포화

pool size 20의 warm 3번째 run에서도 Hikari는 계속 꽉 찼다.

```text
hikaricp_connections_active peak = 20/20
hikaricp_connections_pending peak = 178
hikaricp_connections_timeout = 0
```

timeout은 없지만 순간적으로 많은 요청이 커넥션을 기다렸다. 이 대기 시간이 p95 latency에 반영된다.

### 2. 트랜잭션 내부 쿼리 수

warm 3번째 run 기준 MySQL peak는 다음과 같았다.

```text
select peak   = 14,614 qps
insert peak   = 595 qps
commit peak   = 595 qps
rollback peak = 2,867 qps
```

전체 요청에 비해 select가 많다. 이는 `Student`, `Course` 조회와 lazy 컬렉션 순회가 트랜잭션 안에서 발생하기 때문이다.

### 3. Row Lock 경합

row lock은 치명적인 수준은 아니지만 지속적으로 발생했다.

```text
row lock waits 증가 = 1,890
row lock time 증가  = 6.2s
row lock avg        = 5ms
```

비관적 락을 사용하는 구조에서는 피할 수 없는 비용이지만, 트랜잭션이 짧아질수록 영향은 줄어든다.

## 2,700 RPS는 어느 정도인가?

warm 상태의 pool size 20 기준 대표값은 다음과 같다.

```text
전체 요청 처리량: 약 2,700 req/s
성공 생성 처리량: 약 330 req/s
전체 p95: 약 320ms
201 p95: 약 350ms
```

주의할 점은 2,700 RPS 전체가 성공 신청 처리량은 아니라는 것이다. 실제 insert/commit이 발생한 성공 처리량은 약 330 TPS다. 나머지는 대부분 비즈니스 조건에 의해 `409 Conflict`로 거절된 요청이다.

단일 Spring Boot + JPA + MySQL + pessimistic lock 구조에서 성공 트랜잭션 300 TPS 이상, p95 350ms 수준이면 준수한 편이다. 다만 Hikari pending이 계속 발생하므로 여유가 많은 상태는 아니다.

## 개선 방향

pool size를 더 키우는 것만으로는 충분하지 않았다. pool 30 warm run은 cold run보다 좋아졌지만 pool 20을 넘지는 못했다. 다음 개선은 MySQL 설정 튜닝보다 애플리케이션 쿼리와 락 범위를 줄이는 쪽이 맞다.

### 1. 컬렉션 순회 제거

현재 로직은 트랜잭션 안에서 JPA lazy 컬렉션을 순회한다.

```java
course.isFull();
student.getTotalCredits();
student.hasConflict(course);
```

이를 `count`, `sum`, `exists` 쿼리로 바꾸면 요청당 select 수를 줄일 수 있다.

예시:

```java
boolean existsConflict(Long studentId, DayOfWeek dayOfWeek, int startPeriod, int endPeriod);
int sumCreditsByStudentId(Long studentId);
long countByCourseId(Long courseId);
```

### 2. 정원 처리 원자화

정원 확인을 컬렉션 크기로 하지 말고, `Course`에 신청 인원 컬럼을 두고 atomic update로 처리할 수 있다.

```sql
update course
set enrolled_count = enrolled_count + 1
where id = ?
  and enrolled_count < capacity
```

이 방식은 정원 체크와 증가를 하나의 SQL로 묶을 수 있어 락 보유 시간을 줄일 수 있다.

### 3. Warm-up 후 본 측정

JVM warm-up 영향이 매우 컸기 때문에 공정한 성능 비교를 위해서는 본 테스트 전 warm-up을 분리해야 한다.

권장 방식:

1. 애플리케이션 시작
2. 1분 warm-up 부하
3. 데이터 초기화
4. 본 부하 테스트 실행
5. Prometheus에서 동일 시간 window로 비교

## 결론

이번 테스트에서 가장 좋은 결과는 `maximumPoolSize=20`, warm 상태에서 나왔다.

```text
RPS: 약 2.7k/s
Created: 약 10k / 30s
Created TPS: 약 330/s
전체 p95: 약 320ms
201 p95: 약 350ms
```

MySQL buffer pool, disk I/O, redo log, table cache, max connections는 병목이 아니었다. 핵심 병목은 Hikari pool 포화, 트랜잭션 내부 select 증폭, 비관적 락으로 인한 row lock 경합이었다.

따라서 다음 최적화는 pool size를 더 키우는 것이 아니라, 트랜잭션 안에서 수행되는 쿼리 수와 락 보유 시간을 줄이는 방향으로 진행하는 것이 맞다.
