package com.predicta.mg.conf;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(ScrapeProps.class)
public class RestTemplateConf {
  // Timeouts pour le passthrough live /traffic : une tuile source qui ne répond pas
  // doit échouer vite (best-effort), pas bloquer toute la requête.
  @Bean
  public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder
        .setConnectTimeout(Duration.ofSeconds(3))
        .setReadTimeout(Duration.ofSeconds(5))
        .build();
  }
}
