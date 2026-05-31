package com.example.demo.course;

import java.time.DayOfWeek;

public record CourseCreateRequest(
        String name,
        String professor,
        int capacity,
        int credit,
        DayOfWeek dayOfWeek,
        int startPeriod,
        int endPeriod
) {
}
