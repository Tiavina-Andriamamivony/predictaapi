package com.predicta.mg.services.traffic;

import com.predicta.mg.models.TileCoordinate;
import com.predicta.mg.models.TileFetcher;
import com.predicta.mg.models.TileGridSource;
import com.predicta.mg.models.TrafficResult;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeatureCollection;
import com.predicta.mg.services.traffic.osm.OsmEnricher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestration live de /traffic : grille -> fetch -> convert (par tuile) -> merge GeoJSON.
 * Best-effort : une tuile en échec est ignorée (log warn) et marque le résultat partiel. Aucune
 * persistence.
 *
 * <p>Le fetch est parallélisé : la grille couvrant tout Tana fait plusieurs dizaines de tuiles, et
 * un fetch séquentiel (~0,65 s/tuile pleine) dépasserait le plafond 30 s de l'API Gateway derrière
 * la Lambda. Un pool de taille {@code fetch-parallelism} ramène la latence à quelques secondes.
 */
@Service
@Slf4j
public class TrafficService {

  private final TileGridSource tileGridSource;
  private final TileFetcher tileFetcher;
  private final MvtToGeoJsonConverter converter;
  private final OsmEnricher osmEnricher;
  private final int parallelism;

  @Autowired
  public TrafficService(
      TileGridSource tileGridSource,
      TileFetcher tileFetcher,
      MvtToGeoJsonConverter converter,
      OsmEnricher osmEnricher,
      @Value("${scrape.fetch-parallelism:16}") int parallelism) {
    this.tileGridSource = tileGridSource;
    this.tileFetcher = tileFetcher;
    this.converter = converter;
    this.osmEnricher = osmEnricher;
    this.parallelism = Math.max(1, parallelism);
  }

  /** Constructeur de test : parallélisme par défaut, enrichissement OSM no-op (identité). */
  TrafficService(
      TileGridSource tileGridSource, TileFetcher tileFetcher, MvtToGeoJsonConverter converter) {
    this(tileGridSource, tileFetcher, converter, OsmEnricher.noop(), 16);
  }

  public TrafficResult liveGeoJson() {
    List<TileCoordinate> tiles = tileGridSource.tiles();
    List<GeoJsonFeatureCollection> collections = new ArrayList<>();
    var partial = false;

    var threads = Math.clamp(tiles.size(), 1, parallelism);
    var pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<GeoJsonFeatureCollection>> futures = new ArrayList<>();
      for (TileCoordinate coord : tiles) {
        futures.add(pool.submit(fetchAndConvert(coord)));
      }
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
    } finally {
      pool.shutdownNow();
    }

    GeoJsonFeatureCollection merged = GeoJsonFeatureCollection.concat(collections);
    merged = osmEnricher.enrich(merged); // best-effort : quartierId + name comblé, ou no-op
    log.info(
        "/traffic live : {} tuiles, {} features, partial={}",
        tiles.size(),
        merged.features().size(),
        partial);
    return new TrafficResult(merged, partial);
  }

  private Callable<GeoJsonFeatureCollection> fetchAndConvert(TileCoordinate coord) {
    return () -> converter.convert(coord, tileFetcher.fetch(coord));
  }
}
