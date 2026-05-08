# MySQL REPEATABLE READ 스냅샷 결정 시점 이슈

## 배경

`ConcurrencyTest.정원이_1명인_강좌에_100명이_동시에_신청해도_1명만_성공한다`는 정원이 1명인 강좌에 100명이 동시에 신청할 때 정확히 1명만 성공해야 한다는 과제 요구사항을 검증한다.

현재 서비스는 강좌 row에 비관적 쓰기 락을 걸고, 수강신청 수를 조회한 뒤 정원을 넘지 않으면 `Enrollment`를 저장한다.

```java
Student student = studentRepository.findById(request.studentId()).orElseThrow(...);
Course course = courseRepository.findByIdForUpdate(request.courseId()).orElseThrow(...);

if (enrollmentRepository.countByCourseId(course.getId()) >= course.getCapacity()) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "강좌 정원을 초과했습니다");
}

enrollmentRepository.save(new Enrollment(student, course));
```

`findByIdForUpdate`는 다음 JPQL과 `PESSIMISTIC_WRITE`를 사용하므로 MySQL에서는 `SELECT ... FOR UPDATE`가 실행된다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select c from Course c where c.id = :id")
Optional<Course> findByIdForUpdate(Long id);
```

## 실패 증상

테스트 실행 결과 `successCount`가 기대값 1이 아니라 10으로 관측됐다.

```text
Expecting AtomicInteger(10) to have value:
  1
but did not.
```

## 원인

MySQL InnoDB 기본 격리 수준은 `REPEATABLE READ`다. 이 격리 수준에서 일반 `SELECT`는 non-locking consistent read이며, 트랜잭션의 read view는 트랜잭션 시작 시점이 아니라 첫 consistent read가 실행되는 시점에 생성된다.

100개 요청이 모두 동시에 실행되더라도 실제로 동시에 DB 트랜잭션을 잡는 수는 HikariCP 기본 커넥션 풀 크기인 10개에 가깝다. 따라서 처음 커넥션을 확보한 10개 요청이 아직 수강신청 insert가 반영되지 않은 시점의 스냅샷을 만들고, 이 요청들이 모두 성공하는 형태로 실패한다.

현재 코드에서는 `studentRepository.findById(...)`가 첫 일반 `SELECT`다. 이때 각 트랜잭션은 아직 수강신청 insert가 반영되지 않은 스냅샷을 만든다.

이후 `courseRepository.findByIdForUpdate(...)`는 locking read라서 강좌 row에 대한 락을 순서대로 획득한다. 하지만 락을 획득한 뒤 실행되는 `enrollmentRepository.countByCourseId(...)`는 다시 일반 `SELECT count(...)`이므로, 최신 committed 데이터가 아니라 이미 만들어진 트랜잭션 스냅샷을 기준으로 조회한다.

결과적으로 여러 트랜잭션이 강좌 락을 순서대로 통과하면서도 각자의 오래된 스냅샷에서는 `enrollment count = 0`으로 보게 된다. 그래서 정원이 1명인 강좌에도 여러 insert가 성공한다.

핵심은 비관적 락 자체가 없는 문제가 아니다. `FOR UPDATE`로 강좌 row는 잠그고 있지만, 정원 검증에 사용하는 `count` 쿼리가 MySQL `REPEATABLE READ`의 오래된 consistent read snapshot을 보고 있다는 점이 문제다.

## 해결 선택지

### 1. 수강신청 트랜잭션을 `READ COMMITTED`로 실행

`READ COMMITTED`에서는 일반 조회가 statement마다 새 read view를 만들기 때문에, 강좌 락을 기다린 뒤 실행되는 `countByCourseId`가 앞선 트랜잭션의 insert 결과를 볼 수 있다.

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public EnrollmentResponse enroll(EnrollmentRequest request) {
    ...
}
```

장점은 변경 범위가 작고 현재 구조를 유지할 수 있다는 점이다. 단점은 트랜잭션 격리 수준이 DB 기본값과 달라지므로 이 서비스 메서드의 동시성 가정을 문서화하고 테스트로 고정해야 한다.

### 2. 강좌 `FOR UPDATE`를 첫 DB 조회로 이동

`studentRepository.findById(...)`보다 `courseRepository.findByIdForUpdate(...)`를 먼저 호출하면, 첫 일반 consistent read가 강좌 락 획득 이후에 발생하도록 만들 수 있다.

```java
Course course = courseRepository.findByIdForUpdate(request.courseId()).orElseThrow(...);
Student student = studentRepository.findById(request.studentId()).orElseThrow(...);
```

장점은 격리 수준을 유지할 수 있다는 점이다. 단점은 코드 순서에 동시성 안전성이 의존하므로 향후 일반 조회가 앞에 추가되면 같은 문제가 재발할 수 있다.

### 3. 강좌 row에 신청 인원 컬럼을 두고 같은 row에서 검증과 증가 처리

`Course`에 `enrolledCount` 또는 `remainingSeats`를 두고, 강좌 row를 `FOR UPDATE`로 잠근 상태에서 정원 검사와 카운트 증가를 처리한다.

```text
select course for update
if enrolled_count >= capacity: reject
enrolled_count += 1
insert enrollment
```

장점은 정원 검증이 잠근 row 하나의 current state에 모이므로 가장 명확하다. 단점은 취소 처리에서 카운트 감소를 반드시 같은 트랜잭션 규칙으로 관리해야 하고, 기존 enrollment row 수와 denormalized count 사이의 정합성 관리가 필요하다.

## 현재 판단

현재는 가장 작은 변경으로 문제를 해결하기 위해 강좌 `FOR UPDATE`를 학생 조회보다 먼저 실행하도록 순서를 바꿨다. 이렇게 하면 MySQL `REPEATABLE READ`에서 첫 일반 consistent read가 강좌 락 획득 이후에 발생하므로, 정원 확인용 `countByCourseId`가 오래된 스냅샷을 보는 문제를 피할 수 있다.

다만 이 해결은 현재 실패한 정원 경쟁 테스트에 대한 단기 대응이다. 장기적으로 같은 학생이 동시에 여러 강좌를 신청하는 경우까지 안전하게 처리하려면 학생 row도 락 대상에 포함해야 할 가능성이 높다. 18학점 제한, 시간표 충돌, 중복 신청은 모두 학생의 신청 목록을 기준으로 검증되므로, 학생 단위 락과 강좌 단위 락을 함께 사용하고 항상 같은 순서로 획득해야 데드락 위험을 줄이면서 정합성을 유지할 수 있다.
