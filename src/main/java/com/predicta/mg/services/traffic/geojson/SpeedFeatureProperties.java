package com.predicta.mg.services.traffic.geojson;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Propriétés d'un segment trafic du layer "speeds" de la source trafic.
 *
 * <p>{@code rate} est conservé car il n'est PAS dérivable d'une baseline OSM : la source le calcule
 * contre sa propre vitesse libre. {@code name} = nom de la route, exposé pour la recherche par
 * route (le client filtre la FeatureCollection dessus). Les autres tags MVT (weight, duration,
 * datasource, is_small, is_startpoint) restent écartés : artefacts de routage sans usage côté
 * carte.
 *
 * @param name nom de la route (null si la source ne le fournit pas pour ce segment)
 * @param quartierId id du quartier OSM contenant le segment ({@code rel_<osmId>}), null si non
 *     résolu (index OSM absent, segment hors polygones, enrichissement best-effort en échec)
 * @param speed vitesse observée en km/h
 * @param rate ratio vitesse observée / vitesse libre (signal de congestion)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"name", "quartierId", "speed", "rate"})
public record SpeedFeatureProperties(String name, String quartierId, Integer speed, Double rate) {}
