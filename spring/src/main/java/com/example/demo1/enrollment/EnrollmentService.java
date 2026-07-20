package com.example.demo1.enrollment;

import com.example.demo1.course.Course;
import com.example.demo1.course.CourseRepository;
import com.example.demo1.student.Student;
import com.example.demo1.student.StudentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EnrollmentService {

    private static final int MAX_CREDITS = 18;

    private final EnrollmentRepository enrollmentRepository;
    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;

    public EnrollmentService(EnrollmentRepository enrollmentRepository, StudentRepository studentRepository, CourseRepository courseRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
    }

    @Transactional
    public Enrollment enroll(Long studentId, Long courseId) {
        Student student = studentRepository.findForUpdateById(studentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "학생을 찾을 수 없습니다"));
        Course course = courseRepository.findForUpdateById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "강좌를 찾을 수 없습니다"));

        if (enrollmentRepository.countByCourseId(courseId) >= course.getCapacity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "강좌 정원을 초과했습니다");
        }
        if (enrollmentRepository.sumCreditsByStudentId(studentId) + course.getCredit() > MAX_CREDITS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "학기 최대 18학점을 초과했습니다");
        }
        if (enrollmentRepository.existsScheduleConflict(
                studentId,
                course.getDayOfWeek(),
                course.getStartPeriod(),
                course.getEndPeriod()
        )) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "강좌 시간이 겹칩니다");
        }

        try {
            Enrollment enrollment = enrollmentRepository.saveAndFlush(new Enrollment(student, course));
            return enrollment;
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 신청한 강좌입니다", e);
        }
    }
}
