package com.predicta.mg.models;

import com.predicta.mg.services.traffic.geojson.GeoJsonFeatureCollection;

/**
 * Résultat live des endpoints trafic : la FeatureCollection typée mergée + les indicateurs de
 * complétude, de mode dégradé et de fraîcheur.
 *
 * @param featureCollection segments trafic agrégés sur toutes les tuiles exploitées
 * @param partial vrai si au moins une tuile a échoué et a été ignorée (best-effort)
 * @param fallback vrai si la réponse ne respecte pas la portée demandée (ex. disque centroïde non
 *     filtré servi faute de géométrie pour un quartier)
 * @param ageMs âge de la donnée en millisecondes quand elle vient du cache (0 = chargement live)
 */
public record TrafficResult(
    GeoJsonFeatureCollection featureCollection, boolean partial, boolean fallback, long ageMs) {

  public TrafficResult {
    featureCollection =
        featureCollection == null ? GeoJsonFeatureCollection.empty() : featureCollection;
  }

  /** Résultat sans cache ni repli : les endpoints /traffic et /traffic/zone. */
  public TrafficResult(GeoJsonFeatureCollection featureCollection, boolean partial) {
    this(featureCollection, partial, false, 0);
  }

  /** Résultat sans cache, avec signalement du repli. */
  public TrafficResult(
      GeoJsonFeatureCollection featureCollection, boolean partial, boolean fallback) {
    this(featureCollection, partial, fallback, 0);
  }
}
