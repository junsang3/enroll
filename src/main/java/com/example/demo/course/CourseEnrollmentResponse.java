package com.example.demo.course;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.DayOfWeek;

public record CourseEnrollmentResponse(
        Long id,
        String name,
        String professor,
        int capacity,
        long enrolledCount,
        @JsonProperty("isEnrolled")
        boolean isEnrolled,
        int credit,
        DayOfWeek dayOfWeek,
        int startPeriod,
        int endPeriod
) {

    public static CourseEnrollmentResponse from(Course course, long enrolledCount, boolean isEnrolled) {
        return new CourseEnrollmentResponse(
                course.getId(),
                course.getName(),
                course.getProfessor(),
                course.getCapacity(),
                enrolledCount,
                isEnrolled,
                course.getCredit(),
                course.getDayOfWeek(),
                course.getStartPeriod(),
                course.getEndPeriod()
        );
    }
}
