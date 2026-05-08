package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import com.example.demo.course.Course;
import com.example.demo.course.CourseRepository;
import com.example.demo.enrollment.EnrollmentRepository;
import com.example.demo.enrollment.EnrollmentRequest;
import com.example.demo.enrollment.EnrollmentService;
import com.example.demo.student.Student;
import com.example.demo.student.StudentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

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

    @AfterEach
    void cleanUp() {
        enrollmentRepository.deleteAllInBatch();
        studentRepository.deleteAllInBatch();
        courseRepository.deleteAllInBatch();
    }

    @Test
    void 정원이_1명인_강좌에_100명이_동시에_신청해도_1명만_성공한다() throws InterruptedException {
        List<Student> students = studentRepository.saveAll(IntStream.rangeClosed(1, 100)
                .mapToObj(index -> new Student("학생" + index))
                .toList());
        Course course = courseRepository.save(new Course(
                "동시성 테스트 강좌", "김교수", 1, 3, DayOfWeek.MONDAY, 1, 3
        ));

        ExecutorService executorService = Executors.newFixedThreadPool(100);
        CountDownLatch readyLatch = new CountDownLatch(100);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(100);

        for (Student student : students) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    enrollmentService.enroll(new EnrollmentRequest(student.getId(), course.getId()));
                } catch (Exception ignored) {
                    System.out.println(ignored);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();
        executorService.shutdown();

        long enrolledCount = enrollmentRepository.countByCourseId(course.getId());
        assertThat(enrolledCount).isEqualTo(1);
    }

    @Test
    void 같은_학생이_동시에_여러_강좌를_신청해도_18학점을_초과하지_않는다() throws InterruptedException {
        Student student = studentRepository.save(new Student("동시 신청 학생"));
        List<Course> courses = courseRepository.saveAll(IntStream.rangeClosed(1, 100)
                .mapToObj(index -> new Course(
                        "학점 제한 테스트 강좌" + index, "김교수", 100, 3, DayOfWeek.MONDAY, index, index
                ))
                .toList());

        ExecutorService executorService = Executors.newFixedThreadPool(100);
        CountDownLatch readyLatch = new CountDownLatch(100);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(100);

        for (Course course : courses) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    enrollmentService.enroll(new EnrollmentRequest(student.getId(), course.getId()));
                } catch (Exception ignored) {
                    System.out.println(ignored);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();
        executorService.shutdown();

        long studentCredit = enrollmentRepository.sumCreditByStudentId(student.getId());
        assertThat(studentCredit).isEqualTo(18);
    }

    @Test
    @Disabled
    void 같은_학생이_동시에_겹치는_시간표의_강좌를_신청해도_하나만_성공한다() {}

    @Test
    void 같은_학생이_동시에_같은_강좌를_중복_신청해도_하나만_성공한다() throws InterruptedException {
        Student student = studentRepository.save(new Student("중복 신청 학생"));
        Course course = courseRepository.save(new Course(
                "중복 신청 테스트 강좌", "김교수", 100, 3, DayOfWeek.MONDAY, 1, 3
        ));

        ExecutorService executorService = Executors.newFixedThreadPool(100);
        CountDownLatch readyLatch = new CountDownLatch(100);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(100);

        for (int index = 0; index < 100; index++) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    enrollmentService.enroll(new EnrollmentRequest(student.getId(), course.getId()));
                } catch (Exception ignored) {
                    System.out.println(ignored);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();
        executorService.shutdown();

        long enrolledCount = enrollmentRepository.countByCourseId(course.getId());
        assertThat(enrolledCount).isEqualTo(1);
    }
}
