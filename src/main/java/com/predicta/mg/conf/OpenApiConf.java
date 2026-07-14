package com.predicta.mg.conf;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConf {

  @Bean
  public OpenAPI predictaOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Predicta API")
                .description(
                    "API de prédiction trafic — récupère des tuiles MVT de circulation,"
                        + " les fusionne et les convertit en GeoJSON.")
                .version("1.0.0")
                .contact(new Contact().name("Équipe Predicta").email("team@predicta.com"))
                .license(new License().name("Propriétaire").url("https://predicta.com/license")));
  }
}
