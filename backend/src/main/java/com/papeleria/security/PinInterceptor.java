package com.papeleria.security;

import com.papeleria.common.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PinInterceptor implements HandlerInterceptor {
  private final PinService pins;

  public PinInterceptor(PinService pins) { this.pins = pins; }

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
    if (handler instanceof HandlerMethod hm && hm.hasMethodAnnotation(RequiresPin.class)) {
      pins.verifyOrThrow(req.getHeader("X-Pin"), RequestContext.actor());
    }
    return true;
  }
}
