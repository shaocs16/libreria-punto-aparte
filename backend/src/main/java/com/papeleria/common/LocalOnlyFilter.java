package com.papeleria.common;

import com.papeleria.config.AppProps;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalOnlyFilter extends OncePerRequestFilter {
  private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
  private final Set<String> hosts;

  public LocalOnlyFilter(AppProps props) {
    this.hosts = props.allowedHosts().stream()
        .map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String host = req.getHeader("Host");
    if (host == null || !hosts.contains(host.toLowerCase(Locale.ROOT))) {
      deny(res, "FORBIDDEN_HOST", "Host no permitido");
      return;
    }
    res.setHeader("Content-Security-Policy",
        "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'");
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("X-Frame-Options", "DENY");
    if (req.getRequestURI().startsWith("/api/")) {
      // saldos y movimientos nunca deben servirse desde la caché del navegador
      res.setHeader("Cache-Control", "no-store");
    }
    res.setHeader("Referrer-Policy", "no-referrer");

    if (!SAFE.contains(req.getMethod())) {
      String origin = req.getHeader("Origin");
      if (origin == null) origin = req.getHeader("Referer");
      if (origin != null && !sameHost(origin, host)) {
        deny(res, "FORBIDDEN_ORIGIN", "Origen no permitido");
        return;
      }
      if (!"papeleria-ui".equals(req.getHeader("X-Requested-With"))) {
        deny(res, "FORBIDDEN_ORIGIN", "Falta la cabecera X-Requested-With");
        return;
      }
      String ct = req.getContentType() == null ? null : req.getContentType().toLowerCase(Locale.ROOT);
      boolean hasBody = req.getContentLengthLong() > 0 || req.getHeader("Transfer-Encoding") != null;
      if (hasBody && (ct == null
          || !(ct.startsWith("application/json") || ct.startsWith("multipart/form-data")))) {
        res.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
        return;
      }
    }
    chain.doFilter(req, res);
  }

  private static boolean sameHost(String url, String host) {
    try {
      URI u = URI.create(url);
      if (u.getHost() == null) return false;
      String h = u.getPort() > 0 ? u.getHost() + ":" + u.getPort() : u.getHost();
      return h.equalsIgnoreCase(host);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static void deny(HttpServletResponse res, String code, String msg) throws IOException {
    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
    res.setContentType("application/problem+json");
    res.getWriter().write("{\"status\":403,\"title\":\"" + code + "\",\"code\":\"" + code
        + "\",\"detail\":\"" + msg + "\"}");
  }
}
