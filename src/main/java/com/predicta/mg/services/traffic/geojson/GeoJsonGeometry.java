package com.predicta.mg.services.traffic.geojson;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Géométrie GeoJSON (RFC 7946) limitée aux types produits par la source trafic. Le layer "speeds"
 * de la source ne contient que des lignes : un seul segment routier -> {@link Type#LineString}, une
 * feature multi-segments -> {@link Type#MultiLineString}.
 *
 * <p>Une coordonnée est un couple {@code [lon, lat]} en WGS84. Sérialisé en GeoJSON via les noms de
 * propriété {@code type} / {@code coordinates}, l'imbrication des coordonnées variant selon le
 * type.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"type", "coordinates"})
public sealed interface GeoJsonGeometry
    permits GeoJsonGeometry.LineString, GeoJsonGeometry.MultiLineString {

  /** Discriminant GeoJSON exposé tel quel dans le champ {@code type}. */
  enum Type {
    LineString,
    MultiLineString
  }

  Type getType();

  /** Ligne simple : liste de positions {@code [[lon,lat], ...]}. */
  record LineString(List<double[]> coordinates) implements GeoJsonGeometry {
    @Override
    public Type getType() {
      return Type.LineString;
    }
  }

  /** Multi-ligne : liste de lignes, chacune liste de positions. */
  record MultiLineString(List<List<double[]>> coordinates) implements GeoJsonGeometry {
    @Override
    public Type getType() {
      return Type.MultiLineString;
    }
  }
}
