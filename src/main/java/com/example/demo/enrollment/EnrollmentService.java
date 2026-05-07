package com.example.demo.enrollment;

import com.example.demo.course.Course;
import com.example.demo.course.CourseRepository;
import com.example.demo.student.Student;
import com.example.demo.student.StudentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EnrollmentService {

    private final EnrollmentRepository enrollmentRepository;
    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;

    public EnrollmentService(EnrollmentRepository enrollmentRepository, StudentRepository studentRepository, CourseRepository courseRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
    }

    public EnrollmentResponse enroll(EnrollmentRequest request) {
        Student student = studentRepository.findById(request.studentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "학생을 찾을 수 없습니다"));
        Course course = courseRepository.findById(request.courseId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "강좌를 찾을 수 없습니다"));

        if (enrollmentRepository.countByCourseId(course.getId()) >= course.getCapacity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "강좌 정원을 초과했습니다");
        }

        Enrollment enrollment = enrollmentRepository.save(new Enrollment(student, course));
        return EnrollmentResponse.from(enrollment);
    }
}
