package com.papeleria.importer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface TabularParser {
  @FunctionalInterface
  interface RowConsumer { void accept(int rowNum, List<String> cells); }

  /** Lee el fichero en streaming. Las filas completamente vacías se omiten (pero cuentan en rowNum). */
  void parse(Path file, RowConsumer consumer) throws IOException;
}
