package com.example.demo.course;

import java.time.DayOfWeek;

public record CourseResponse(
        Long id,
        String name,
        String professor,
        int capacity,
        int credit,
        DayOfWeek dayOfWeek,
        int startPeriod,
        int endPeriod
) {

    public static CourseResponse from(Course course) {
        return new CourseResponse(
                course.getId(),
                course.getName(),
                course.getProfessor(),
                course.getCapacity(),
                course.getCredit(),
                course.getDayOfWeek(),
                course.getStartPeriod(),
                course.getEndPeriod()
        );
    }
}
