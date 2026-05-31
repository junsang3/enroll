package com.example.demo.enrollment;

import java.time.DayOfWeek;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    long countByCourseId(Long courseId);

    @Query("""
            select e
            from Enrollment e
            join fetch e.student
            join fetch e.course c
            where e.student.id = :studentId
            order by c.dayOfWeek, c.startPeriod, c.endPeriod, c.id
            """)
    List<Enrollment> findScheduleByStudentId(Long studentId);

    @Query("""
            select coalesce(sum(c.credit), 0)
            from Enrollment e
            join e.course c
            where e.student.id = :studentId
            """)
    long sumCreditByStudentId(Long studentId);

    @Query("""
            select count(e) > 0
            from Enrollment e
            join e.course c
            where e.student.id = :studentId
              and c.dayOfWeek = :dayOfWeek
              and c.startPeriod <= :endPeriod
              and :startPeriod <= c.endPeriod
            """)
    boolean existsOverlappingSchedule(Long studentId, DayOfWeek dayOfWeek, int startPeriod, int endPeriod);
}
