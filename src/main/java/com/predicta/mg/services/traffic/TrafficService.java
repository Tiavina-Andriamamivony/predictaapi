package com.predicta.mg.services.traffic;

import com.predicta.mg.conf.ScrapeProps;
import com.predicta.mg.models.QuartierView;
import com.predicta.mg.models.TileCoordinate;
import com.predicta.mg.models.TileFetcher;
import com.predicta.mg.models.TileGridSource;
import com.predicta.mg.models.TileGridSourceCentered;
import com.predicta.mg.models.TrafficResult;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeatureCollection;
import com.predicta.mg.services.traffic.osm.OsmEnricher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestration live de /traffic : grille &rarr; fetch &rarr; convert (par tuile) &rarr; merge
 * GeoJSON &rarr; enrichissement OSM. Aucune persistence.
 *
 * <p>Best-effort : une tuile en échec est ignorée (log warn) et marque le résultat partiel. Le
 * fetch est parallélisé car la grille couvrant Tana fait plusieurs dizaines de tuiles ; en
 * séquentiel (~0,65 s/tuile pleine) on dépasserait le plafond 30 s de l'API Gateway.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrafficService {

  private final TileGridSource tileGridSource;
  private final TileFetcher tileFetcher;
  private final MvtToGeoJsonConverter converter;
  private final OsmEnricher osmEnricher;
  private final ScrapeProps props;

  /** Résultat interne du fetch parallèle : les collections récoltées + si au moins une a échoué. */
  private record FetchOutcome(List<GeoJsonFeatureCollection> collections, boolean partial) {}

  /** Trafic live de toute la zone couverte par la grille par défaut (toute Tana). */
  public TrafficResult liveGeoJson() {
    return liveGeoJson(tileGridSource.tiles());
  }

  /**
   * Trafic live d'une zone centrée sur un quartier : même pipeline que {@link #liveGeoJson()}, mais
   * sur une grille disque recentrée sur le quartier et de rayon réduit ({@code zone-radius}). Bien
   * plus léger que la couverture complète, pour un recentrage carte.
   */
  public TrafficResult liveGeoJsonAround(QuartierView quartier) {
    // Grille recentrée sur le quartier : le rayon de couverture (radius) prend le zone-radius
    // réduit.
    TileGridSource zone =
        new TileGridSourceCentered(
            new ScrapeProps(
                quartier.lon(),
                quartier.lat(),
                props.zoom(),
                props.zoneRadius(),
                props.zoneRadius(),
                props.fetchParallelism()));
    return liveGeoJson(zone.tiles());
  }

  /** Cœur commun : fetch parallèle des tuiles données, merge, enrichissement, log. */
  private TrafficResult liveGeoJson(List<TileCoordinate> tiles) {
    FetchOutcome outcome = fetchAllTiles(tiles);
    GeoJsonFeatureCollection merged = merge(outcome.collections());
    log.info(
        "/traffic live : {} tuiles, {} features, partial={}",
        tiles.size(),
        merged.features().size(),
        outcome.partial());
    return new TrafficResult(merged, outcome.partial());
  }

  /** Fetch + conversion de toutes les tuiles en parallèle ; possède le cycle de vie du pool. */
  private FetchOutcome fetchAllTiles(List<TileCoordinate> tiles) {
    int threads = Math.clamp(tiles.size(), 1, props.fetchParallelism());
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<GeoJsonFeatureCollection>> futures = new ArrayList<>();
      for (TileCoordinate coord : tiles) {
        futures.add(pool.submit(fetchAndConvert(coord)));
      }
      return collect(futures, tiles);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Récolte les résultats du pool ; ignore les tuiles en échec (best-effort) et note le partiel.
   */
  private FetchOutcome collect(
      List<Future<GeoJsonFeatureCollection>> futures, List<TileCoordinate> tiles) {
    List<GeoJsonFeatureCollection> collections = new ArrayList<>();
    boolean partial = false;
    for (int i = 0; i < tiles.size(); i++) {
      try {
        collections.add(futures.get(i).get());
      } catch (ExecutionException e) {
        partial = true;
        log.warn("Tuile ignorée (best-effort) {} : {}", tiles.get(i), e.getCause().getMessage());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        partial = true;
        log.warn("Fetch interrompu pour {} : {}", tiles.get(i), e.getMessage());
      }
    }
    return new FetchOutcome(collections, partial);
  }

  /** Une tuile : fetch du .pbf puis conversion en GeoJSON, avec sa propre coordonnée de tuile. */
  private Callable<GeoJsonFeatureCollection> fetchAndConvert(TileCoordinate coord) {
    return () -> converter.convert(coord, tileFetcher.fetch(coord));
  }

  /**
   * Concatène les collections puis pose quartierId + noms via OSM (best-effort, no-op possible).
   */
  private GeoJsonFeatureCollection merge(List<GeoJsonFeatureCollection> collections) {
    return osmEnricher.enrich(GeoJsonFeatureCollection.concat(collections));
  }
}
