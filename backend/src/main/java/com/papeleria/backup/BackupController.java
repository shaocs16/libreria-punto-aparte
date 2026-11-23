package com.papeleria.backup;

import com.papeleria.audit.AuditService;
import com.papeleria.common.RequestContext;
import com.papeleria.security.RequiresPin;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/backup")
public class BackupController {
  private final BackupService backups;
  private final AuditService audit;

  public BackupController(BackupService backups, AuditService audit) {
    this.backups = backups;
    this.audit = audit;
  }

  @GetMapping
  public Map<String, Object> status() { return backups.status(); }

  /** «Descargar copia ahora»: genera la copia y la envía al navegador (se borra del servidor después). */
  @RequiresPin
  @PostMapping("/download")
  public void download(HttpServletResponse res) throws IOException {
    Path dir = Files.createTempDirectory("papeleria-copy");
    Path file = backups.createCopy(dir);
    audit.record("BACKUP_DOWNLOADED", "backup", file.getFileName(), null, null, RequestContext.actor());
    try {
      res.setContentType("application/vnd.sqlite3");
      res.setHeader("Content-Disposition", "attachment; filename=\"" + file.getFileName() + "\"");
      res.setContentLengthLong(Files.size(file));
      Files.copy(file, res.getOutputStream());
    } finally {
      Files.deleteIfExists(file);
      Files.deleteIfExists(dir);
    }
  }

  /** Ejecuta ahora la copia a la carpeta configurada. */
  @RequiresPin
  @PostMapping("/run")
  public Map<String, Object> run() {
    var f = backups.runConfigured(RequestContext.actor());
    return Map.of("file", f.toString());
  }
}
