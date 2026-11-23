package com.papeleria.export;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/** XLSX en streaming (memoria constante). */
public class XlsxSink implements TableSink {
  private final OutputStream out;
  private final SXSSFWorkbook wb = new SXSSFWorkbook(100);
  private final Sheet sheet = wb.createSheet("Datos");
  private final CellStyle money;
  private final CellStyle bold;
  private int rowNum = 0;

  public XlsxSink(OutputStream out) {
    this.out = out;
    this.money = wb.createCellStyle();
    money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
    Font f = wb.createFont();
    f.setBold(true);
    this.bold = wb.createCellStyle();
    bold.setFont(f);
  }

  @Override
  public void header(List<String> columns) {
    Row r = sheet.createRow(rowNum++);
    for (int i = 0; i < columns.size(); i++) {
      Cell c = r.createCell(i);
      c.setCellValue(columns.get(i));
      c.setCellStyle(bold);
    }
  }

  @Override
  public void row(List<?> values) {
    Row r = sheet.createRow(rowNum++);
    for (int i = 0; i < values.size(); i++) {
      Object v = values.get(i);
      if (v == null) continue;
      Cell c = r.createCell(i);
      if (v instanceof BigDecimal b) {
        c.setCellValue(b.doubleValue());
        c.setCellStyle(money);
      } else if (v instanceof Number n) {
        c.setCellValue(n.doubleValue());
      } else {
        c.setCellValue(TableSink.sanitize(String.valueOf(v)));
      }
    }
  }

  @Override
  public void finish() throws IOException {
    try {
      wb.write(out);
    } finally {
      wb.dispose();
      wb.close();
    }
    out.flush();
  }
}
