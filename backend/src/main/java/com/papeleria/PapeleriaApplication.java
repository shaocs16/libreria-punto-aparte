package com.papeleria;

import java.util.Arrays;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PapeleriaApplication {
  public static void main(String[] args) {
    boolean resetPin = Arrays.asList(args).contains("--reset-pin");
    new SpringApplicationBuilder(PapeleriaApplication.class)
        .web(resetPin ? WebApplicationType.NONE : WebApplicationType.SERVLET)
        .run(args);
  }
}
