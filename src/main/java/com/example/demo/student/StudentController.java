package com.example.demo.student;

import com.example.demo.enrollment.EnrollmentRepository;
import com.example.demo.enrollment.EnrollmentResponse;
import com.example.demo.enrollment.EnrollmentService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/students")
public class StudentController {

    private final StudentRepository studentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final EnrollmentService enrollmentService;

    public StudentController(
            StudentRepository studentRepository,
            EnrollmentRepository enrollmentRepository,
            EnrollmentService enrollmentService
    ) {
        this.studentRepository = studentRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.enrollmentService = enrollmentService;
    }

    @GetMapping
    public List<StudentResponse> findStudents() {
        return studentRepository.findAll().stream()
                .map(StudentResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StudentResponse createStudent(@RequestBody StudentCreateRequest request) {
        Student student = studentRepository.save(new Student(request.name()));
        return StudentResponse.from(student);
    }

    @DeleteMapping("/{studentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStudent(@PathVariable Long studentId) {
        if (!studentRepository.existsById(studentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "학생을 찾을 수 없습니다");
        }
        if (enrollmentRepository.existsByStudentId(studentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "수강신청 내역이 있는 학생은 삭제할 수 없습니다");
        }

        studentRepository.deleteById(studentId);
    }

    @GetMapping("/{studentId}/schedule")
    public List<EnrollmentResponse> findSchedule(@PathVariable Long studentId) {
        return enrollmentService.findSchedule(studentId);
    }
}
