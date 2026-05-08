package com.example.demo.enrollment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    long countByCourseId(Long courseId);

    @Query("""
            select coalesce(sum(c.credit), 0)
            from Enrollment e
            join e.course c
            where e.student.id = :studentId
            """)
    long sumCreditByStudentId(Long studentId);
}
