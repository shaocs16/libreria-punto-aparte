package com.papeleria.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ProblemHandler {
  private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<ProblemDetail> business(BusinessException e) {
    var p = problem(e.status(), e.code(), e.getMessage());
    if (e instanceof PinException pe && pe.retryAfterSeconds() != null) {
      p.setProperty("retryAfterSeconds", pe.retryAfterSeconds());
    }
    return ResponseEntity.status(e.status()).body(p);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
    var msg = e.getBindingResult().getFieldErrors().stream()
        .map(f -> f.getField() + ": " + f.getDefaultMessage())
        .reduce((a, b) -> a + "; " + b).orElse("Solicitud no válida");
    return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", msg));
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
      IllegalArgumentException.class})
  ResponseEntity<ProblemDetail> badRequest(Exception e) {
    return ResponseEntity.badRequest()
        .body(problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", e.getMessage()));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException e) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(problem(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "El fichero supera 50 MB"));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException e) {
    return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
        "Valor no válido para el parámetro " + e.getName()));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ProblemDetail> dataAccess(DataAccessException e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t.getMessage() != null && t.getMessage().contains("UNIQUE constraint failed")) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(HttpStatus.CONFLICT, "DUPLICATE",
            "Valor duplicado (código o documento ya existe)"));
      }
    }
    return other(e);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> other(Exception e) {
    log.error("Error no controlado", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Error interno"));
  }

  private static ProblemDetail problem(HttpStatus s, String code, String detail) {
    var p = ProblemDetail.forStatusAndDetail(s, detail);
    p.setTitle(code);
    p.setProperty("code", code);
    return p;
  }
}
