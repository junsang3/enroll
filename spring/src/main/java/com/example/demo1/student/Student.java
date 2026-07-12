package com.example.demo1.student;

import com.example.demo1.course.Course;
import com.example.demo1.enrollment.Enrollment;
import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @OneToMany(mappedBy = "student")
    private List<Enrollment> enrollments = new ArrayList<>();

    protected Student() {
    }

    public Student(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public List<Enrollment> getEnrollments() {
        return enrollments;
    }

    public int getTotalCredits() {
        int totalCredits = 0;
        for (Enrollment enrollment : enrollments) {
            totalCredits += enrollment.getCourse().getCredit();
        }
        return totalCredits;
    }

    public boolean hasConflict(Course course) {
        for (Enrollment enrollment : enrollments) {
            if (enrollment.getCourse().conflictsWith(course)) {
                return true;
            }
        }
        return false;
    }
}
