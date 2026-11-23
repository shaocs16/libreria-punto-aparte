package com.papeleria.export;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** CSV con separador ';' y UTF-8 con BOM (se abre bien en Excel). */
public class CsvSink implements TableSink {
  private final Writer w;

  public CsvSink(OutputStream out) throws IOException {
    this.w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
    w.write('\uFEFF');
  }

  @Override
  public void header(List<String> columns) throws IOException { write(columns.stream().map(String.class::cast).toList()); }

  @Override
  public void row(List<?> values) throws IOException { write(values.stream().map(TableSink::text).toList()); }

  private void write(List<String> cells) throws IOException {
    boolean first = true;
    for (String c : cells) {
      if (!first) w.write(';');
      first = false;
      w.write(quote(c));
    }
    w.write("\r\n");
  }

  private static String quote(String s) {
    if (s.indexOf(';') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
      return '"' + s.replace("\"", "\"\"") + '"';
    }
    return s;
  }

  @Override
  public void finish() throws IOException { w.flush(); }
}
