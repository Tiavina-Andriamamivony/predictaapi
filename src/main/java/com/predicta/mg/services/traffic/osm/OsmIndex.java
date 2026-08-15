package com.predicta.mg.services.traffic.osm;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cache mémoire de l'index OSM ({@link OsmSnapshot}). Chargé paresseusement au 1er appel de {@link
 * #snapshotOrNull()}, en tâche de fond, une seule fois à la fois (single-flight). Le snapshot est
 * publié via {@code volatile} et lu lock-free.
 *
 * <p>Contrat best-effort : le tout 1er appel attend au plus {@code ready-timeout-ms} le premier
 * chargement, puis rend {@code null} (segments non enrichis) plutôt que de bloquer {@code
 * /traffic}. TTL expiré → on sert le snapshot périmé et on relance un refresh en fond.
 *
 * <p>Note Lambda : le cache vit par conteneur. Un cold start repart de zéro (1ers appels non
 * enrichis) — accepté, compensé par le TTL long et le single-flight.
 */
@Component
@Slf4j
public class OsmIndex {

  private final OverpassClient client;
  private final boolean enabled;
  private final long ttlMs;
  private final long readyTimeoutMs;

  private volatile OsmSnapshot snapshot;
  private final AtomicBoolean loading = new AtomicBoolean(false);
  private final ExecutorService loader =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "osm-index-loader");
            t.setDaemon(true);
            return t;
          });

  public OsmIndex(
      OverpassClient client,
      @Value("${scrape.osm.enabled:true}") boolean enabled,
      @Value("${scrape.osm.ttl-minutes:1440}") long ttlMinutes,
      @Value("${scrape.osm.ready-timeout-ms:300}") long readyTimeoutMs) {
    this.client = client;
    this.enabled = enabled;
    this.ttlMs = TimeUnit.MINUTES.toMillis(ttlMinutes);
    this.readyTimeoutMs = readyTimeoutMs;
  }

  /**
   * Géométrie du quartier OSM demandé, ou {@code null} si l'index n'est pas prêt ou le quartier
   * absent du snapshot. Ne lève jamais — l'appelant décide du comportement de repli.
   */
  public org.locationtech.jts.geom.Geometry quartierGeometryOrNull(String quartierId) {
    OsmSnapshot snap = snapshotOrNull();
    if (snap == null) {
      return null;
    }
    QuartierPolygon quartier = snap.quartierById().get(quartierId);
    return quartier == null ? null : quartier.geometry();
  }

  /**
   * Rend le snapshot présent (instantané si déjà chargé, ou fraîchement chargé sur le 1er appel si
   * ça tient dans {@code ready-timeout-ms}), sinon {@code null}. Ne lève jamais.
   */
  public OsmSnapshot snapshotOrNull() {
    if (!enabled) {
      return null;
    }
    OsmSnapshot current = snapshot;
    if (current == null) {
      return awaitFirstLoad();
    }
    if (System.currentTimeMillis() - current.loadedAtMs() > ttlMs) {
      kickLoad(); // périmé : refresh en fond, on sert le stale ce coup-ci
    }
    return current;
  }

  private OsmSnapshot awaitFirstLoad() {
    CountDownLatch latch = kickLoad();
    if (latch != null) {
      try {
        latch.await(readyTimeoutMs, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return snapshot; // peut encore être null si le load n'a pas fini à temps
  }

  /**
   * Démarre un chargement en fond si aucun n'est en cours (single-flight). Rend un latch libéré à
   * la fin du load (succès ou échec), ou {@code null} si un load tourne déjà.
   */
  private CountDownLatch kickLoad() {
    if (!loading.compareAndSet(false, true)) {
      return null;
    }
    CountDownLatch latch = new CountDownLatch(1);
    loader.submit(
        () -> {
          try {
            snapshot = client.load();
          } catch (Throwable t) {
            log.warn(
                "Chargement index OSM échoué (best-effort, /traffic non impacté) : {}",
                t.getMessage());
          } finally {
            loading.set(false);
            latch.countDown();
          }
        });
    return latch;
  }
}
