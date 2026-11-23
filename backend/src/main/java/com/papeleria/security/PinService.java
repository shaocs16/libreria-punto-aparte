package com.papeleria.security;

import com.papeleria.audit.AuditService;
import com.papeleria.common.Actor;
import com.papeleria.common.BusinessException;
import com.papeleria.common.PinException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PinService {
  static final int MAX_FAILS = 5;
  static final Duration LOCK = Duration.ofMinutes(5);
  private static final Pattern FORMAT = Pattern.compile("\\d{4,8}");

  private final JdbcTemplate jdbc;
  private final AuditService audit;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

  public PinService(JdbcTemplate jdbc, AuditService audit) {
    this.jdbc = jdbc;
    this.audit = audit;
  }

  /** Cada escritura aquí se confirma sola (autocommit) para que los fallos persistan aunque la petición falle. */
  public synchronized void verifyOrThrow(String pin, Actor actor) {
    String hash = setting("pin_hash");
    if (hash.isEmpty()) {
      throw new PinException(HttpStatus.FORBIDDEN, "PIN_NOT_SET", "El PIN no está configurado", null);
    }
    Instant lockedUntil = lockedUntil();
    if (lockedUntil != null && lockedUntil.isAfter(Instant.now())) {
      throw locked(lockedUntil);
    }
    if (pin == null || pin.isBlank()) {
      throw new PinException(HttpStatus.FORBIDDEN, "PIN_REQUIRED", "Se requiere el PIN", null);
    }
    if (encoder.matches(pin, hash)) {
      if (!"0".equals(setting("pin_failed_count"))) put("pin_failed_count", "0");
      return;
    }
    int fails = Integer.parseInt(setting("pin_failed_count")) + 1;
    audit.record("PIN_FAILED", "security", null, null, Map.of("attempt", fails), actor);
    if (fails >= MAX_FAILS) {
      Instant until = Instant.now().plus(LOCK);
      put("pin_failed_count", "0");
      put("pin_locked_until", until.toString());
      audit.record("PIN_LOCKED", "security", null, null, Map.of("until", until.toString()), actor);
      throw locked(until);
    }
    put("pin_failed_count", String.valueOf(fails));
    throw new PinException(HttpStatus.FORBIDDEN, "PIN_INVALID", "PIN incorrecto", null);
  }

  /** Crea el PIN (primer arranque) o lo cambia verificando el actual. */
  public synchronized void setPin(String newPin, String currentPin, Actor actor) {
    if (newPin == null || !FORMAT.matcher(newPin).matches()) {
      throw BusinessException.validation("El PIN debe tener entre 4 y 8 dígitos");
    }
    boolean first = setting("pin_hash").isEmpty();
    if (!first) verifyOrThrow(currentPin, actor);
    put("pin_hash", encoder.encode(newPin));
    put("pin_failed_count", "0");
    put("pin_locked_until", "");
    audit.record(first ? "PIN_CREATED" : "PIN_CHANGED", "security", null, null, null, actor);
  }

  public boolean isSet() { return !setting("pin_hash").isEmpty(); }

  /** Solo desde CLI en el servidor. */
  public synchronized void reset() {
    put("pin_hash", "");
    put("pin_failed_count", "0");
    put("pin_locked_until", "");
    audit.record("PIN_RESET", "security", null, null, null, new Actor("cli", "cli"));
  }

  private PinException locked(Instant until) {
    long secs = Math.max(1, Duration.between(Instant.now(), until).toSeconds());
    return new PinException(HttpStatus.LOCKED, "PIN_LOCKED", "PIN bloqueado temporalmente", secs);
  }

  private Instant lockedUntil() {
    String v = setting("pin_locked_until");
    return v.isEmpty() ? null : Instant.parse(v);
  }

  private String setting(String key) {
    return jdbc.queryForObject("SELECT value FROM app_setting WHERE key = ?", String.class, key);
  }

  private void put(String key, String value) {
    jdbc.update("""
        UPDATE app_setting SET value = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE key = ?
        """, value, key);
  }
}
