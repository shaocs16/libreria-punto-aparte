package com.papeleria.config;

import com.papeleria.security.PinInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final PinInterceptor pin;

  public WebConfig(PinInterceptor pin) { this.pin = pin; }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(pin).addPathPatterns("/api/**");
  }
}
