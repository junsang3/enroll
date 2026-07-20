package com.example.demo1.enrollment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.DayOfWeek;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    @Query("""
            select count(e)
              from Enrollment e
             where e.course.id = :courseId
            """)
    long countByCourseId(@Param("courseId") Long courseId);

    @Query("""
            select coalesce(sum(e.course.credit), 0)
              from Enrollment e
             where e.student.id = :studentId
            """)
    long sumCreditsByStudentId(@Param("studentId") Long studentId);

    @Query("""
            select case when count(e) > 0 then true else false end
              from Enrollment e
             where e.student.id = :studentId
               and e.course.dayOfWeek = :dayOfWeek
               and e.course.startPeriod <= :endPeriod
               and e.course.endPeriod >= :startPeriod
            """)
    boolean existsScheduleConflict(
            @Param("studentId") Long studentId,
            @Param("dayOfWeek") DayOfWeek dayOfWeek,
            @Param("startPeriod") int startPeriod,
            @Param("endPeriod") int endPeriod
    );
}
