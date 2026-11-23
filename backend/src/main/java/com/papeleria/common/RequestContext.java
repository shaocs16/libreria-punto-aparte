package com.papeleria.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class RequestContext {
  public static final String ACTOR_ATTR = "papeleria.actor";

  private RequestContext() {}

  public static HttpServletRequest request() {
    return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
  }

  public static Actor actor() {
    return (Actor) request().getAttribute(ACTOR_ATTR);
  }

  public static String host() {
    return request().getHeader("Host");
  }
}
