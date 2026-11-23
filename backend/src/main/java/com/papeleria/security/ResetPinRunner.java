package com.papeleria.security;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** Papeleria.exe --reset-pin: restablece el PIN desde el propio servidor y termina. */
@Component
@ConditionalOnProperty("reset-pin")
public class ResetPinRunner implements CommandLineRunner {
  private final PinService pins;
  private final ConfigurableApplicationContext ctx;

  public ResetPinRunner(PinService pins, ConfigurableApplicationContext ctx) {
    this.pins = pins;
    this.ctx = ctx;
  }

  @Override
  public void run(String... args) {
    pins.reset();
    System.out.println("PIN restablecido. Crea uno nuevo al abrir la aplicación.");
    System.exit(SpringApplication.exit(ctx));
  }
}
