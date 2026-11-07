package com.buzzer.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Real PostgreSQL (not H2) started from bundled binaries, so tests and local development need neither
 * Docker nor a system PostgreSQL install. One instance per JVM.
 */
public final class EmbeddedDatabase {
    private static EmbeddedPostgres instance;

    private EmbeddedDatabase() {}

    public static synchronized EmbeddedPostgres get(File dataDirectory) {
        if (instance == null) {
            try {
                EmbeddedPostgres.Builder b = EmbeddedPostgres.builder();
                if (dataDirectory != null) {
                    b.setDataDirectory(dataDirectory).setCleanDataDirectory(false).setPort(54329);
                }
                instance = b.start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        instance.close();
                    } catch (IOException ignored) {
                    }
                }));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
            }
        }
        return instance;
    }

    public static String jdbcUrl(EmbeddedPostgres pg) {
        return pg.getJdbcUrl("postgres", "postgres");
    }
}
