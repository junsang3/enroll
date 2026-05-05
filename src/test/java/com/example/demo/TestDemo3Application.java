package com.example.demo;

import org.springframework.boot.SpringApplication;

public class TestDemo3Application {

    public static void main(String[] args) {
        SpringApplication.from(Demo3Application::main).with(TestcontainersConfiguration.class).run(args);
    }

}
