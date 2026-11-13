package com.juzo.ai.ordermanager.advisor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AdvisorServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdvisorServiceApplication.class, args);
    }
}
