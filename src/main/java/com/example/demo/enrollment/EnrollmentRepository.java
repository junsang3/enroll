package com.example.demo.enrollment;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    long countByCourseId(Long courseId);
}
