package com.predicta.mg.models;

import com.predicta.mg.services.traffic.geojson.GeoJsonFeatureCollection;

/**
 * Résultat live de {@code /traffic} : la FeatureCollection typée mergée + indicateur de complétude.
 *
 * @param featureCollection segments trafic agrégés sur toutes les tuiles exploitées
 * @param partial vrai si au moins une tuile a échoué et a été ignorée (best-effort)
 */
public record TrafficResult(GeoJsonFeatureCollection featureCollection, boolean partial) {}
