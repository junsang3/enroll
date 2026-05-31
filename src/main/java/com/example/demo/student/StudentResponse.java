package com.example.demo.student;

public record StudentResponse(Long id, String name) {

    public static StudentResponse from(Student student) {
        return new StudentResponse(student.getId(), student.getName());
    }
}
