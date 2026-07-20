package com.example.demo1;

import com.example.demo1.course.Course;
import com.example.demo1.course.CourseRepository;
import com.example.demo1.student.Student;
import com.example.demo1.student.StudentRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

@Configuration
public class DataInitializer {

//    @Bean
    CommandLineRunner initData(StudentRepository studentRepository, CourseRepository courseRepository) {
        return args -> {
            if (studentRepository.count() == 0) {
                studentRepository.saveAll(IntStream.rangeClosed(1, 10000)
                        .mapToObj(index -> new Student("학생" + index))
                        .toList());
            }

            if (courseRepository.count() == 0) {
                courseRepository.saveAll(IntStream.rangeClosed(1, 500)
                        .mapToObj(index -> new Course(
                                "강좌" + index,
                                "교수" + index,
                                100,
                                3,
                                DayOfWeek.MONDAY,
                                1,
                                3))
                        .toList());
            }
        };
    }

    @Bean
    CommandLineRunner initData2(StudentRepository studentRepository, CourseRepository courseRepository) {
        return args -> {
            if (studentRepository.count() > 0) return;

            studentRepository.saveAll(IntStream.rangeClosed(1, 10000)
                    .mapToObj(index -> new Student("학생" + index))
                    .toList());
            courseRepository.save(new Course(
                    "강좌1", "교수1", 10000, 3, DayOfWeek.MONDAY, 1, 1
            ));
        };
    }
}
