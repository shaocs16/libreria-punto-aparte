package com.papeleria.backup;

import com.papeleria.audit.AuditService;
import com.papeleria.common.Actor;
import com.papeleria.settings.SettingsService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class BackupService {
  private static final Logger log = LoggerFactory.getLogger(BackupService.class);
  private static final Actor SYSTEM = new Actor("system", "system");
  private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
  private static final Pattern FILE = Pattern.compile("papeleria-(\\d{8})-(\\d{4})\\.db");

  private final JdbcTemplate jdbc;
  private final SettingsService settings;
  private final AuditService audit;
  private final AtomicReference<String> lastError = new AtomicReference<>();

  public BackupService(JdbcTemplate jdbc, SettingsService settings, AuditService audit) {
    this.jdbc = jdbc;
    this.settings = settings;
    this.audit = audit;
  }

  public boolean configured() {
    return settings.getBool("backup_enabled") && !settings.get("backup_dir").isBlank();
  }

  public Map<String, Object> status() {
    var m = new java.util.LinkedHashMap<String, Object>();
    m.put("configured", configured());
    m.put("lastBackupAt", settings.get("last_backup_at"));
    if (lastError.get() != null) m.put("lastError", lastError.get());
    if (!configured()) m.put("warning", "Sin copia de seguridad configurada");
    return m;
  }

  /** Copia coherente con la app en marcha (VACUUM INTO) y verificada con integrity_check. */
  public synchronized Path createCopy(Path dir) throws IOException {
    Files.createDirectories(dir);
    Path target = dir.resolve("papeleria-" + NAME.format(LocalDateTime.now()) + ".db").toAbsolutePath();
    Files.deleteIfExists(target);
    jdbc.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
    try {
      verify(target);
    } catch (RuntimeException | IOException e) {
      Files.deleteIfExists(target);
      throw e;
    }
    return target;
  }

  static void verify(Path file) throws IOException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
         Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA integrity_check")) {
      if (!rs.next() || !"ok".equals(rs.getString(1))) throw new IOException("La copia no supera integrity_check");
    } catch (SQLException e) {
      throw new IOException("No se pudo verificar la copia", e);
    }
  }

  @Scheduled(cron = "${app.backup-cron:0 0 22 * * *}")
  public void scheduled() {
    if (configured()) runConfigured(SYSTEM);
  }

  public Path runConfigured(Actor actor) {
    try {
      Path dir = Path.of(settings.get("backup_dir"));
      Path f = createCopy(dir);
      rotate(dir);
      settings.set("last_backup_at", java.time.Instant.now().toString());
      lastError.set(null);
      audit.record("BACKUP_CREATED", "backup", f.getFileName(), null, null, actor);
      return f;
    } catch (Exception e) {
      log.error("Backup fallido", e);
      lastError.set(e.getMessage());
      audit.record("BACKUP_FAILED", "backup", null, null, Map.of("error", String.valueOf(e.getMessage())), actor);
      throw new IllegalStateException("Backup fallido: " + e.getMessage(), e);
    }
  }

  /** Conserva 7 diarias, 4 semanales y 12 mensuales (la copia más reciente de cada periodo). */
  void rotate(Path dir) throws IOException {
    List<Path> files;
    try (Stream<Path> s = Files.list(dir)) {
      files = s.filter(p -> FILE.matcher(p.getFileName().toString()).matches())
          .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString())).toList();
    }
    Set<String> days = new HashSet<>(), weeks = new HashSet<>(), months = new HashSet<>();
    Set<Path> keep = new HashSet<>();
    for (Path p : files) {
      Matcher m = FILE.matcher(p.getFileName().toString());
      m.matches();
      LocalDate d = LocalDate.parse(m.group(1), DateTimeFormatter.BASIC_ISO_DATE);
      String day = d.toString();
      String week = d.get(IsoFields.WEEK_BASED_YEAR) + "-" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
      String month = d.getYear() + "-" + d.getMonthValue();
      if (days.size() < 7 && days.add(day)) keep.add(p);
      if (weeks.size() < 4 && weeks.add(week)) keep.add(p);
      if (months.size() < 12 && months.add(month)) keep.add(p);
    }
    for (Path p : files) if (!keep.contains(p)) Files.deleteIfExists(p);
  }
}
