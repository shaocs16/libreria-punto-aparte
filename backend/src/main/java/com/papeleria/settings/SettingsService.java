package com.papeleria.settings;

import com.papeleria.audit.AuditService;
import com.papeleria.common.Actor;
import com.papeleria.common.BusinessException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {
  static final Set<String> WRITABLE = Set.of("business_name", "ticket_footer", "low_balance_cents",
      "inactive_days", "backup_enabled", "backup_dir");
  private static final Set<String> READ_ONLY_VISIBLE = Set.of("last_backup_at");

  private final JdbcTemplate jdbc;
  private final AuditService audit;

  public SettingsService(JdbcTemplate jdbc, AuditService audit) {
    this.jdbc = jdbc;
    this.audit = audit;
  }

  public Map<String, String> all() {
    var out = new LinkedHashMap<String, String>();
    jdbc.query("SELECT key, value FROM app_setting ORDER BY key", rs -> {
      String k = rs.getString(1);
      if (WRITABLE.contains(k) || READ_ONLY_VISIBLE.contains(k)) out.put(k, rs.getString(2));
    });
    return out;
  }

  public String get(String key) {
    return jdbc.queryForObject("SELECT value FROM app_setting WHERE key = ?", String.class, key);
  }

  public int getInt(String key) { return Integer.parseInt(get(key)); }

  public boolean getBool(String key) { return Boolean.parseBoolean(get(key)); }

  public void set(String key, String value) {
    jdbc.update("""
        UPDATE app_setting SET value = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE key = ?
        """, value, key);
  }

  @Transactional
  public Map<String, String> update(Map<String, String> in, Actor actor) {
    if (in == null || in.isEmpty()) throw BusinessException.validation("No hay ajustes que cambiar");
    var before = all();
    var merged = new LinkedHashMap<>(before);
    for (var e : in.entrySet()) {
      if (!WRITABLE.contains(e.getKey())) {
        throw BusinessException.validation("Ajuste desconocido o no editable: " + e.getKey());
      }
      merged.put(e.getKey(), validate(e.getKey(), e.getValue()));
    }
    if (Boolean.parseBoolean(merged.get("backup_enabled")) && merged.get("backup_dir").isBlank()) {
      throw BusinessException.validation("Indica la carpeta de destino para activar las copias");
    }
    String dir = merged.get("backup_dir");
    if (!dir.isBlank() && in.containsKey("backup_dir")) {
      try {
        Path p = Path.of(dir);
        Files.createDirectories(p);
        if (!Files.isWritable(p)) throw BusinessException.validation("La carpeta de copias no es escribible");
      } catch (InvalidPathException | java.io.IOException e) {
        throw BusinessException.validation("Carpeta de copias no válida");
      }
    }
    for (var e : in.entrySet()) set(e.getKey(), merged.get(e.getKey()));
    var after = all();
    audit.record("SETTINGS_UPDATED", "app_setting", null, before, after, actor);
    return after;
  }

  private static String validate(String key, String value) {
    if (value == null) throw BusinessException.validation(key + " no puede ser nulo");
    String v = value.strip();
    switch (key) {
      case "business_name" -> {
        if (v.isEmpty() || v.length() > 100) throw BusinessException.validation("business_name: 1-100 caracteres");
      }
      case "ticket_footer" -> {
        if (v.length() > 300) throw BusinessException.validation("ticket_footer: máximo 300 caracteres");
      }
      case "low_balance_cents", "inactive_days" -> {
        try {
          int n = Integer.parseInt(v);
          if (n < 0 || n > 1_000_000_000) throw new NumberFormatException();
          if (key.equals("inactive_days") && n == 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
          throw BusinessException.validation(key + " debe ser un entero positivo");
        }
      }
      case "backup_enabled" -> {
        if (!v.equals("true") && !v.equals("false")) throw BusinessException.validation("backup_enabled: true/false");
      }
      case "backup_dir" -> {
        if (v.length() > 500) throw BusinessException.validation("backup_dir demasiado largo");
      }
      default -> throw BusinessException.validation("Ajuste desconocido: " + key);
    }
    return v;
  }
}
