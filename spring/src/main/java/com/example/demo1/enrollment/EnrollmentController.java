package com.example.demo1.enrollment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/enrollments")
public class EnrollmentController {

    private final EnrollmentService enrollmentService;

    public EnrollmentController(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentResponse enroll(@Valid @RequestBody EnrollmentRequest request) {
        Enrollment enrollment = enrollmentService.enroll(
                request.studentId(),
                request.courseId()
        );

        return EnrollmentResponse.from(enrollment);
    }

    public record EnrollmentRequest(
            @NotNull Long studentId,
            @NotNull Long courseId
    ) {
    }

    public record EnrollmentResponse(
            Long enrollmentId,
            Long studentId,
            Long courseId
    ) {
        static EnrollmentResponse from(Enrollment enrollment) {
            return new EnrollmentResponse(
                    enrollment.getId(),
                    enrollment.getStudent().getId(),
                    enrollment.getCourse().getId()
            );
        }
    }
}
