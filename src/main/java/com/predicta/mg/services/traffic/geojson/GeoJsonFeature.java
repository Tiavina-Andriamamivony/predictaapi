package com.predicta.mg.services.traffic.geojson;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Feature GeoJSON (RFC 7946) : un type constant "Feature", des propriétés typées et une géométrie.
 *
 * @param properties propriétés du segment trafic (layer "speeds")
 * @param geometry géométrie ligne reprojetée en WGS84
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"type", "properties", "geometry"})
public record GeoJsonFeature(SpeedFeatureProperties properties, GeoJsonGeometry geometry) {

  /** Champ {@code type} GeoJSON, constant. */
  public String getType() {
    return "Feature";
  }
}
