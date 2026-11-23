package com.papeleria.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Copia la BD a data/pre-migration/ antes de aplicar migraciones pendientes sobre una BD ya existente. */
@Configuration
public class FlywayConfig {
  private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

  @Bean
  public FlywayMigrationStrategy preMigrationBackup(AppProps props) {
    return flyway -> {
      var info = flyway.info();
      if (info.current() != null && info.pending().length > 0) {
        try {
          Path dir = Path.of(props.dbPath()).toAbsolutePath().getParent().resolve("pre-migration");
          Files.createDirectories(dir);
          Path target = dir.resolve("papeleria-pre-v" + info.current().getVersion() + "-"
              + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".db");
          try (Connection c = flyway.getConfiguration().getDataSource().getConnection();
               Statement s = c.createStatement()) {
            s.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
          }
          log.info("Copia previa a la migración: {}", target);
        } catch (Exception e) {
          throw new IllegalStateException("No se pudo crear la copia previa a la migración; se aborta", e);
        }
      }
      flyway.migrate();
    };
  }
}
