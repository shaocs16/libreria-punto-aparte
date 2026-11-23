package com.papeleria.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProps(String dbPath, List<String> allowedHosts, String localActorName) {}
