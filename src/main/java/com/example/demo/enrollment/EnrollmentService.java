package com.example.demo.enrollment;

import com.example.demo.course.Course;
import com.example.demo.course.CourseRepository;
import com.example.demo.student.Student;
import com.example.demo.student.StudentRepository;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    @Transactional
    public EnrollmentResponse enroll(EnrollmentRequest request) {
        Student student = studentRepository.findByIdForUpdate(request.studentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "학생을 찾을 수 없습니다"));
        Course course = courseRepository.findByIdForUpdate(request.courseId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "강좌를 찾을 수 없습니다"));

        long enrolledCount = enrollmentRepository.countByCourseId(course.getId());
        if (enrolledCount >= course.getCapacity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "강좌 정원을 초과했습니다");
        }

        long studentCredit = enrollmentRepository.sumCreditByStudentId(student.getId());
        if (studentCredit + course.getCredit() > 18) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "학기 최대 18학점을 초과했습니다");
        }

        if (enrollmentRepository.existsOverlappingSchedule(
                student.getId(), course.getDayOfWeek(), course.getStartPeriod(), course.getEndPeriod()
        )) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 시간대에 진행되는 강좌는 동시에 신청할 수 없습니다");
        }

        try {
            Enrollment enrollment = enrollmentRepository.saveAndFlush(new Enrollment(student, course));
            return EnrollmentResponse.from(enrollment);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 신청한 강좌입니다");
        }
    }

    @Transactional
    public void cancel(Long studentId, Long courseId) {
        Enrollment enrollment = enrollmentRepository.findByStudentIdAndCourseId(studentId, courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "수강신청 내역을 찾을 수 없습니다"));

        enrollmentRepository.delete(enrollment);
    }

    @Transactional(readOnly = true)
    public List<EnrollmentResponse> findSchedule(Long studentId) {
        if (!studentRepository.existsById(studentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "학생을 찾을 수 없습니다");
        }

        return enrollmentRepository.findScheduleByStudentId(studentId).stream()
                .map(EnrollmentResponse::from)
                .toList();
    }
}
