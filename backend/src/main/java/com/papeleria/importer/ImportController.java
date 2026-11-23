package com.papeleria.importer;

import com.papeleria.common.RequestContext;
import com.papeleria.export.ExportController;
import com.papeleria.security.PinService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {
  private final ImportService service;
  private final PinService pins;

  public ImportController(ImportService service, PinService pins) {
    this.service = service;
    this.pins = pins;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Map<String, Object>> upload(@RequestParam String kind, @RequestParam MultipartFile file)
      throws IOException {
    return ResponseEntity.status(HttpStatus.CREATED).body(service.upload(kind, file, RequestContext.actor()));
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable String id) { return service.preview(id); }

  @GetMapping("/{id}/errors.csv")
  public void errors(@PathVariable String id, HttpServletResponse res) throws IOException {
    service.kindOf(id);
    res.setContentType(ExportController.CSV);
    res.setHeader("Content-Disposition", "attachment; filename=\"errores-importacion.csv\"");
    service.writeErrorsCsv(id, res.getOutputStream());
  }

  /** Confirmar requiere PIN solo si son movimientos. */
  @PostMapping("/{id}/confirm")
  public Map<String, Object> confirm(@PathVariable String id, HttpServletRequest req) {
    if (service.kindOf(id) == ImportService.Kind.MOVEMENTS) {
      pins.verifyOrThrow(req.getHeader("X-Pin"), RequestContext.actor());
    }
    return service.confirm(id, RequestContext.actor());
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> discard(@PathVariable String id) {
    service.discard(id);
    return ResponseEntity.noContent().build();
  }

  /** Plantillas: /imports/templates/customers.csv, movements.xlsx, ... (solo cabecera). */
  @GetMapping("/templates/{file}")
  public void template(@PathVariable String file, HttpServletResponse res) throws IOException {
    int dot = file.lastIndexOf('.');
    String name = dot < 0 ? file : file.substring(0, dot);
    String fmt = dot < 0 ? "" : file.substring(dot + 1).toLowerCase();
    if (!fmt.equals("csv") && !fmt.equals("xlsx")) {
      throw com.papeleria.common.BusinessException.notFound("Plantilla");
    }
    var kind = ImportService.Kind.parse(name);
    try (var sink = ExportController.sink(fmt, "plantilla-" + name, res)) {
      sink.header(kind.columns());
      sink.finish();
    }
  }
}
