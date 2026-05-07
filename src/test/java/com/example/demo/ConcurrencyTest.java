package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConcurrencyTest {

    @Autowired
    StudentRepository studentRepository;

    @Autowired
    CourseRepository courseRepository;

    @Autowired
    EnrollmentRepository enrollmentRepository;

    @Autowired
    EnrollmentService enrollmentService;

    @Test
    void 정원이_1명인_강좌에_100명이_동시에_신청해도_1명만_성공한다() throws InterruptedException {
        List<Student> students = studentRepository.saveAll(IntStream.rangeClosed(1, 100)
                .mapToObj(index -> new Student("학생" + index))
                .toList());
        Course course = courseRepository.save(
                new Course("동시성 테스트 강좌", "김교수", 3, DayOfWeek.MONDAY, 1, 3, 1)
        );

        ExecutorService executorService = Executors.newFixedThreadPool(100);
        CountDownLatch readyLatch = new CountDownLatch(100);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(100);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        for (Student student : students) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    enrollmentService.enroll(new EnrollmentRequest(student.getId(), course.getId()));
                    successCount.incrementAndGet();
                } catch (Exception e) { // TODO: 예외 구분 처리
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();
        executorService.shutdown();

        assertThat(successCount).hasValue(1);
        assertThat(failCount).hasValue(99);
        assertThat(enrollmentRepository.countByCourseId(course.getId())).isEqualTo(1);
    }

    @Test
    void 같은_학생이_동시에_여러_강좌를_신청해도_18학점을_초과하지_않는다() {}

    @Test
    void 같은_학생이_동시에_겹치는_시간표의_강좌를_신청해도_하나만_성공한다() {}

    @Test
    void 같은_학생이_동시에_같은_강좌를_중복_신청해도_하나만_성공한다() {}
}
