package com.predicta.mg.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Quartier de Tana : nom + centroïde pour la recherche et le recentrage de la carte. */
@Entity
@Table(name = "quartiers")
@Getter
@NoArgsConstructor
public class Quartier {

  @Id
  @Column(name = "quartier_id")
  private String quartierId;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String source;

  @Column(name = "centroid_lon", nullable = false)
  private double centroidLon;

  @Column(name = "centroid_lat", nullable = false)
  private double centroidLat;
}
