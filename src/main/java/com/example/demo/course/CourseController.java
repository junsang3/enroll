package com.example.demo.course;

import com.example.demo.enrollment.EnrollmentRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/courses")
public class CourseController {

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;

    public CourseController(CourseRepository courseRepository, EnrollmentRepository enrollmentRepository) {
        this.courseRepository = courseRepository;
        this.enrollmentRepository = enrollmentRepository;
    }

    @GetMapping
    public List<CourseResponse> findCourses() {
        return courseRepository.findAll().stream()
                .map(CourseResponse::from)
                .toList();
    }

    @GetMapping("/enrollment-status")
    public List<CourseEnrollmentResponse> findCoursesWithEnrollmentStatus(@RequestParam(required = false) Long studentId) {
        return courseRepository.findAll().stream()
                .map(course -> CourseEnrollmentResponse.from(
                        course,
                        enrollmentRepository.countByCourseId(course.getId()),
                        studentId != null && enrollmentRepository.existsByStudentIdAndCourseId(studentId, course.getId())
                ))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CourseResponse createCourse(@RequestBody CourseCreateRequest request) {
        Course course = courseRepository.save(new Course(
                request.name(),
                request.professor(),
                request.capacity(),
                request.credit(),
                request.dayOfWeek(),
                request.startPeriod(),
                request.endPeriod()
        ));
        return CourseResponse.from(course);
    }

    @DeleteMapping("/{courseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCourse(@PathVariable Long courseId) {
        if (!courseRepository.existsById(courseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "강좌를 찾을 수 없습니다");
        }
        if (enrollmentRepository.existsByCourseId(courseId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "수강신청 내역이 있는 강좌는 삭제할 수 없습니다");
        }

        courseRepository.deleteById(courseId);
    }
}
