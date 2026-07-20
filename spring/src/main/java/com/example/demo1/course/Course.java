package com.example.demo1.course;

import jakarta.persistence.*;

import java.time.DayOfWeek;

@Entity
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String professor;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false)
    private int credit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DayOfWeek dayOfWeek;

    @Column(nullable = false)
    private int startPeriod;

    @Column(nullable = false)
    private int endPeriod;

    protected Course() {
    }

    public Course(String name, String professor,  int capacity, int credit, DayOfWeek dayOfWeek, int startPeriod, int endPeriod) {
        this.name = name;
        this.professor = professor;
        this.capacity = capacity;
        this.credit = credit;
        this.dayOfWeek = dayOfWeek;
        this.startPeriod = startPeriod;
        this.endPeriod = endPeriod;
    }

    public Long getId() {
        return id;
    }

    public int getCredit() {
        return credit;
    }

    public int getCapacity() {
        return capacity;
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public int getStartPeriod() {
        return startPeriod;
    }

    public int getEndPeriod() {
        return endPeriod;
    }

    public boolean conflictsWith(Course other) {
        return dayOfWeek == other.dayOfWeek && startPeriod <= other.endPeriod && endPeriod >= other.startPeriod;
    }
}
