package com.papeleria.importer;

import com.papeleria.common.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/** XLSX con la API de eventos (SAX): memoria constante, XXE desactivado, macros rechazadas. Solo la primera hoja. */
public class XlsxParser implements TabularParser {

  @Override
  public void parse(Path file, RowConsumer consumer) throws IOException {
    try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
      if (!pkg.getPartsByName(Pattern.compile("/xl/vbaProject\\.bin")).isEmpty()) {
        throw BusinessException.validation("Los ficheros con macros no están permitidos");
      }
      var reader = new XSSFReader(pkg);
      SharedStrings sst = new ReadOnlySharedStringsTable(pkg);
      StylesTable styles = reader.getStylesTable();
      var sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
      if (!sheets.hasNext()) throw BusinessException.validation("El libro no tiene hojas");
      try (InputStream sheet = sheets.next()) {
        var xml = XMLHelper.newXMLReader();
        xml.setContentHandler(new XSSFSheetXMLHandler(styles, null, sst, new Collector(consumer),
            new DataFormatter(Locale.ROOT), false));
        xml.parse(new InputSource(sheet));
      }
    } catch (InvalidFormatException e) {
      throw BusinessException.validation("El fichero no es un XLSX válido");
    } catch (OpenXML4JException | SAXException | ParserConfigurationException e) {
      throw BusinessException.validation("No se pudo leer el XLSX: " + e.getMessage());
    }
  }

  private static final class Collector implements XSSFSheetXMLHandler.SheetContentsHandler {
    private final RowConsumer consumer;
    private List<String> cells;

    Collector(RowConsumer consumer) { this.consumer = consumer; }

    @Override public void startRow(int rowNum) { cells = new ArrayList<>(); }

    @Override
    public void cell(String ref, String value, XSSFComment comment) {
      if (ref == null) return;
      int col = new CellReference(ref).getCol();
      while (cells.size() <= col) cells.add("");
      cells.set(col, value == null ? "" : value.strip());
    }

    @Override
    public void endRow(int rowNum) {
      if (cells.stream().allMatch(String::isBlank)) return;
      consumer.accept(rowNum + 1, cells);
    }
  }
}
