package com.example.demo1.enrollment;

import com.example.demo1.course.Course;
import com.example.demo1.student.Student;
import jakarta.persistence.*;

@Entity
@Table(
        indexes = {
                @Index(
                        name = "idx_enrollment_course_id",
                        columnList = "course_id"
                )
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_enrollment_student_course",
                        columnNames = {"student_id", "course_id"}
                )
        }
)
public class Enrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Course course;

    protected Enrollment() {
    }

    public Enrollment(Student student, Course course) {
        this.student = student;
        this.course = course;
    }

    public Long getId() {
        return id;
    }

    public Student getStudent() {
        return student;
    }

    public Course getCourse() {
        return course;
    }
}
