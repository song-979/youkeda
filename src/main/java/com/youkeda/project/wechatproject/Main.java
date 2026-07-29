package com.youkeda.project.wechatproject;

import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = { SpringAiRetryAutoConfiguration.class })
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
