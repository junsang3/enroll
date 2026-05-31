package com.example.demo.student;

import com.example.demo.enrollment.EnrollmentResponse;
import com.example.demo.enrollment.EnrollmentService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/students")
public class StudentController {

    private final StudentRepository studentRepository;
    private final EnrollmentService enrollmentService;

    public StudentController(StudentRepository studentRepository, EnrollmentService enrollmentService) {
        this.studentRepository = studentRepository;
        this.enrollmentService = enrollmentService;
    }

    @GetMapping
    public List<StudentResponse> findStudents() {
        return studentRepository.findAll().stream()
                .map(StudentResponse::from)
                .toList();
    }

    @GetMapping("/{studentId}/schedule")
    public List<EnrollmentResponse> findSchedule(@PathVariable Long studentId) {
        return enrollmentService.findSchedule(studentId);
    }
}
