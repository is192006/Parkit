package com.parkit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the ParkIt layered modular monolith.
 * See docs/ARCHITECTURE.md for the layer and package responsibilities.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ParkItApplication {

    public static void main(String[] args) {
        SpringApplication.run(ParkItApplication.class, args);
    }
}
