package com.melody.minirag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MiniRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniRagApplication.class, args);
    }
}
