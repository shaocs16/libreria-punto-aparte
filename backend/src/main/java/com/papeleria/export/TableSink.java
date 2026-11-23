package com.papeleria.export;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

/** Destino tabular (CSV o XLSX). Los BigDecimal son importes; los String se sanean contra inyección de fórmulas. */
public interface TableSink extends AutoCloseable {
  void header(List<String> columns) throws IOException;

  void row(List<?> values) throws IOException;

  void finish() throws IOException;

  @Override
  default void close() {}

  static String sanitize(String s) {
    if (s == null || s.isEmpty()) return "";
    char c = s.charAt(0);
    return (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') ? "'" + s : s;
  }

  static String text(Object v) {
    if (v == null) return "";
    if (v instanceof BigDecimal b) return b.toPlainString().replace('.', ',');
    return sanitize(String.valueOf(v));
  }
}
