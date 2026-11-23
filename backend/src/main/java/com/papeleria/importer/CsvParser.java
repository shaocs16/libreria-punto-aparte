package com.papeleria.importer;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import com.papeleria.common.BusinessException;
import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** CSV con separador ';' o ',' (por frecuencia en la cabecera); UTF-8 estricto y, si falla, Windows-1252. */
public class CsvParser implements TabularParser {
  static final Charset WIN1252 = Charset.forName("windows-1252");

  static Charset detectCharset(Path f) throws IOException {
    var dec = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    try (var in = new BufferedInputStream(Files.newInputStream(f)); Reader r = new InputStreamReader(in, dec)) {
      char[] buf = new char[8192];
      while (r.read(buf) >= 0) { /* solo validar */ }
      return StandardCharsets.UTF_8;
    } catch (CharacterCodingException e) {
      return WIN1252;
    }
  }

  private static BufferedReader open(Path f, Charset cs) throws IOException {
    var r = new BufferedReader(new InputStreamReader(Files.newInputStream(f), cs));
    r.mark(1);
    if (r.read() != '\uFEFF') r.reset();
    return r;
  }

  static char detectSeparator(Path f, Charset cs) throws IOException {
    try (var r = open(f, cs)) {
      String line = r.readLine();
      if (line == null) return ';';
      long semis = line.chars().filter(c -> c == ';').count();
      long commas = line.chars().filter(c -> c == ',').count();
      return semis >= commas ? ';' : ',';
    }
  }

  @Override
  public void parse(Path file, RowConsumer consumer) throws IOException {
    Charset cs = detectCharset(file);
    char sep = detectSeparator(file, cs);
    try (var reader = new CSVReaderBuilder(open(file, cs))
        .withCSVParser(new CSVParserBuilder().withSeparator(sep).build()).build()) {
      String[] line;
      int n = 0;
      while ((line = reader.readNext()) != null) {
        n++;
        if (Arrays.stream(line).allMatch(s -> s == null || s.isBlank())) continue;
        consumer.accept(n, Arrays.stream(line).map(s -> s == null ? "" : s.strip()).toList());
      }
    } catch (CsvValidationException e) {
      throw BusinessException.validation("CSV mal formado: " + e.getMessage());
    }
  }
}
