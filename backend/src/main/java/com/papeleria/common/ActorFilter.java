package com.papeleria.common;

import com.papeleria.config.AppProps;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ActorFilter extends OncePerRequestFilter {
  private final AppProps props;

  public ActorFilter(AppProps props) { this.props = props; }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    Actor actor = null;
    String login = clean(req.getHeader("Tailscale-User-Login"));
    if (login != null && isLoopback(req.getRemoteAddr())) {
      String name = clean(req.getHeader("Tailscale-User-Name"));
      actor = new Actor(login, name != null ? name : login);
    }
    if (actor == null) {
      String pc = props.localActorName();
      actor = new Actor("local@" + pc, "local@" + pc);
    }
    req.setAttribute(RequestContext.ACTOR_ATTR, actor);
    chain.doFilter(req, res);
  }

  private static boolean isLoopback(String addr) {
    try {
      return InetAddress.getByName(addr).isLoopbackAddress();
    } catch (Exception e) {
      return false;
    }
  }

  private static String clean(String s) {
    if (s == null) return null;
    s = s.strip();
    return s.isEmpty() ? null : (s.length() > 200 ? s.substring(0, 200) : s);
  }
}
