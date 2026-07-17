package com.youkeda.project.wechatproject;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WechatprojectApplication {
    public static void main(String[] args) {
        SpringApplication.run(WechatprojectApplication.class, args);
    }
}
