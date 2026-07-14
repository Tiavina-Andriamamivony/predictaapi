package com.predicta.mg.services.traffic.geojson;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * FeatureCollection GeoJSON (RFC 7946) : le type de retour de {@code GET /traffic}. Conteneur typé
 * remplaçant l'ancien {@code ObjectNode} manipulé à la main.
 *
 * @param features segments trafic agrégés sur toutes les tuiles
 */
@JsonPropertyOrder({"type", "features"})
public record GeoJsonFeatureCollection(List<GeoJsonFeature> features) {

  public GeoJsonFeatureCollection {
    features = features == null ? List.of() : List.copyOf(features);
  }

  /** Collection vide (aucune tuile exploitable). */
  public static GeoJsonFeatureCollection empty() {
    return new GeoJsonFeatureCollection(List.of());
  }

  /** Champ {@code type} GeoJSON, constant. */
  public String getType() {
    return "FeatureCollection";
  }

  /**
   * Concatène plusieurs collections en une seule (aucune déduplication : chaque tuile couvre une
   * zone disjointe). Les entrées nulles sont ignorées (robustesse best-effort).
   */
  public static GeoJsonFeatureCollection concat(List<GeoJsonFeatureCollection> collections) {
    List<GeoJsonFeature> all = new ArrayList<>();
    for (GeoJsonFeatureCollection fc : collections) {
      if (fc != null) {
        all.addAll(fc.features());
      }
    }
    return new GeoJsonFeatureCollection(all);
  }
}
