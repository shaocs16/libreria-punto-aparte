package com.papeleria.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Convierte fechas de la UI (Europe/Madrid) al formato de texto UTC con milisegundos guardado en BD. */
public final class TimeRange {
  public static final ZoneId ZONE = ZoneId.of("Europe/Madrid");
  private static final DateTimeFormatter F =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private TimeRange() {}

  public static String format(Instant i) { return F.format(i); }

  /** Inicio inclusivo: "yyyy-MM-dd" (00:00 Madrid) o instante ISO. */
  public static String from(String s) { return parse(s, false); }

  /** Fin exclusivo: "yyyy-MM-dd" incluye todo ese día; un instante ISO se usa tal cual. */
  public static String to(String s) { return parse(s, true); }

  private static String parse(String s, boolean end) {
    if (s == null || s.isBlank()) return null;
    try {
      String t = s.strip();
      if (t.length() == 10) {
        LocalDate d = LocalDate.parse(t);
        return F.format((end ? d.plusDays(1) : d).atStartOfDay(ZONE).toInstant());
      }
      return F.format(Instant.parse(t));
    } catch (DateTimeParseException e) {
      throw BusinessException.validation("Fecha no válida: " + s);
    }
  }
}
