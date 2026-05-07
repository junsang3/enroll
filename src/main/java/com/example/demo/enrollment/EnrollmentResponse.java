package com.example.demo.enrollment;

import java.time.DayOfWeek;

public record EnrollmentResponse(
        Long id,
        Long studentId,
        String studentName,
        Long courseId,
        String courseName,
        String professor,
        int credit,
        DayOfWeek dayOfWeek,
        int startPeriod,
        int endPeriod
) {

    public static EnrollmentResponse from(Enrollment enrollment) {
        return new EnrollmentResponse(
                enrollment.getId(),
                enrollment.getStudent().getId(),
                enrollment.getStudent().getName(),
                enrollment.getCourse().getId(),
                enrollment.getCourse().getName(),
                enrollment.getCourse().getProfessor(),
                enrollment.getCourse().getCredit(),
                enrollment.getCourse().getDayOfWeek(),
                enrollment.getCourse().getStartPeriod(),
                enrollment.getCourse().getEndPeriod()
        );
    }
}
