package com.predicta.mg.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Application cliente : une clé API par app pour authentifier les appels. */
@Entity
@Table(name = "applications")
@Getter
@NoArgsConstructor
public class Application {

  @Id private UUID id;

  @Column(nullable = false)
  private String name;

  @Column(name = "api_key", nullable = false, unique = true)
  private String apiKey;
}
