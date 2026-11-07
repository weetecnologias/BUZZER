package com.buzzer;

import com.buzzer.support.EmbeddedDatabase;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;

import java.io.File;

/**
 * Local development launcher: runs the real backend against an embedded PostgreSQL whose data persists in
 * ./.local-data/postgres. No Docker or PostgreSQL install required.
 *
 *   cd backend
 *   mvn spring-boot:test-run
 *
 * Uses the "dev" profile (dev seed users, OTP shown in API responses, mock payments).
 */
public class LocalDevApplication {
    public static void main(String[] args) {
        File dir = new File(".local-data/postgres");
        dir.mkdirs();
        EmbeddedPostgres pg = EmbeddedDatabase.get(dir);
        System.setProperty("spring.datasource.url", EmbeddedDatabase.jdbcUrl(pg));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        SpringApplication app = new SpringApplication(BuzzerApplication.class);
        app.setAdditionalProfiles("dev");
        app.run(args);
    }
}
