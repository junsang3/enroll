package com.example.demo1;

import com.example.demo1.course.Course;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;

import static org.assertj.core.api.Assertions.assertThat;

public class UnitTest {
    @Test
    void 과목_시간_충돌() {
        Course course = new Course(
                "", "", 10, 10, DayOfWeek.MONDAY, 3, 5
        );

        assertThat(course.conflictsWith(new Course(
                "", "", 10, 10, DayOfWeek.TUESDAY, 1, 3
        ))).isFalse();

        assertThat(course.conflictsWith(new Course(
                "", "", 10, 10, DayOfWeek.MONDAY, 1, 3
        ))).isTrue();

        assertThat(course.conflictsWith(new Course(
                "", "", 10, 10, DayOfWeek.MONDAY, 5, 7
        ))).isTrue();

        assertThat(course.conflictsWith(new Course(
                "", "", 10, 10, DayOfWeek.MONDAY, 6, 8
        ))).isFalse();
    }
}
