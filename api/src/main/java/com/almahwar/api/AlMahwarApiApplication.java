package com.almahwar.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * REST API of the Al Mahwar Store Management System, for mobile clients.
 * <p>
 * Request path: HTTP → security (JWT) → controller → service → repository (explicit SQL) → SQL Server. Mobile clients
 * never reach SQL Server; every rule (authentication, permissions, validation, transactions) is enforced here.
 * The frozen desktop application keeps its own direct connection and is not changed by this project.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AlMahwarApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlMahwarApiApplication.class, args);
    }
}
