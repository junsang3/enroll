package com.example.demo1;

import com.example.demo1.course.Course;
import com.example.demo1.course.CourseRepository;
import com.example.demo1.enrollment.EnrollmentRepository;
import com.example.demo1.enrollment.EnrollmentService;
import com.example.demo1.student.Student;
import com.example.demo1.student.StudentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.DayOfWeek;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

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
    void cleanup() {
        enrollmentRepository.deleteAll();
        studentRepository.deleteAll();
        courseRepository.deleteAll();
    }

    @Test
    void 정원이_1명인_강좌에_100명이_동시에_신청하면_정확히_1명만_성공한다() throws InterruptedException {
        List<Student> students = studentRepository.saveAll(IntStream.range(0, 100)
                .mapToObj(index -> new Student("학생" + index))
                .toList());
        Course course = courseRepository.save(new Course(
                "강좌1", "김교수", 1, 3, DayOfWeek.MONDAY, 1, 3
        ));

        runConcurrently(students.size(), index -> {
            Student student = students.get(index);
            enrollmentService.enroll(student.getId(), course.getId());
        });

        Long enrolledCount = enrollmentRepository.count();
        assertThat(enrolledCount).isEqualTo(1);
    }

    @Test
    void 같은_학생이_동시에_강좌들을_신청해도_18학점을_초과하지_않는다() throws InterruptedException {
        Student student = studentRepository.save(new Student("김학생"));
        List<Course> courses = courseRepository.saveAll(List.of(
                new Course("강좌1", "김교수", 3, 6, DayOfWeek.MONDAY, 1, 6),
                new Course("강좌2", "김교수", 3, 6, DayOfWeek.TUESDAY, 1, 6),
                new Course("강좌3", "김교수", 3, 6, DayOfWeek.WEDNESDAY, 1, 6),
                new Course("강좌4", "김교수", 3, 6, DayOfWeek.THURSDAY, 1, 6),
                new Course("강좌5", "김교수", 3, 6, DayOfWeek.FRIDAY, 1, 6)
        ));

        runConcurrently(courses.size(), index -> {
            Course course = courses.get(index);
            enrollmentService.enroll(student.getId(), course.getId());
        });

        Long enrolledCount = enrollmentRepository.count();
        assertThat(enrolledCount).isEqualTo(3);
    }

    @Test
    void 같은_학생이_시간이_겹치는_강좌들을_동시에_신청해도_하나만_성공한다() throws InterruptedException {
        Student student = studentRepository.save(new Student("김학생"));
        List<Course> courses = courseRepository.saveAll(List.of(
                new Course("강좌1", "김교수", 9, 5, DayOfWeek.MONDAY, 1, 5),
                new Course("강좌2", "김교수", 9, 5, DayOfWeek.MONDAY, 2, 6),
                new Course("강좌3", "김교수", 9, 5, DayOfWeek.MONDAY, 3, 7),
                new Course("강좌4", "김교수", 9, 5, DayOfWeek.MONDAY, 4, 8),
                new Course("강좌5", "김교수", 9, 5, DayOfWeek.MONDAY, 5, 9)
        ));

        runConcurrently(courses.size(), index -> {
            Course course = courses.get(index);
            enrollmentService.enroll(student.getId(), course.getId());
        });

        Long enrolledCount = enrollmentRepository.count();
        assertThat(enrolledCount).isEqualTo(1);
    }

    @Test
    void 같은_학생이_같은_강좌를_동시에_여러_번_신청해도_한_번만_신청된다() throws InterruptedException {
        Student student = studentRepository.save(new Student("김학생"));
        Course course = courseRepository.save(new Course("강좌1", "김교수", 9, 3, DayOfWeek.MONDAY, 1, 3));

        runConcurrently(100, index -> {
            enrollmentService.enroll(student.getId(), course.getId());
        });

        Long enrolledCount = enrollmentRepository.count();
        assertThat(enrolledCount).isEqualTo(1);
    }

    private void runConcurrently(int threadCount, IntConsumer task) throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    task.accept(index);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();
        executorService.shutdown();
    }
}
