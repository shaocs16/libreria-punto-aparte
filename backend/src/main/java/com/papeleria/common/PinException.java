package com.papeleria.common;

import org.springframework.http.HttpStatus;

public class PinException extends BusinessException {
  private final Long retryAfterSeconds;

  public PinException(HttpStatus status, String code, String message, Long retryAfterSeconds) {
    super(status, code, message);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public Long retryAfterSeconds() { return retryAfterSeconds; }
}
