package com.triplan.triplan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;  // ★ 추가

@SpringBootApplication
@EnableScheduling  // ★ 추가
public class TriplanApplication {
	public static void main(String[] args) {
		SpringApplication.run(TriplanApplication.class, args);
	}
}