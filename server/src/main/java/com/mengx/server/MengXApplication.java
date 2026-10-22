package com.mengx.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MengXApplication {

    public static void main(String[] args) {
        SpringApplication.run(MengXApplication.class, args);
    }
}
