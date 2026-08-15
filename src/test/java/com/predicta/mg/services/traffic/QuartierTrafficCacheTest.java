package com.predicta.mg.services.traffic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.predicta.mg.models.TrafficResult;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeatureCollection;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QuartierTrafficCacheTest {

  private static final TrafficResult RESULT_OK =
      new TrafficResult(GeoJsonFeatureCollection.empty(), false);
  private static final TrafficResult RESULT_PARTIAL =
      new TrafficResult(GeoJsonFeatureCollection.empty(), true);

  @Test
  void premier_get_charge_et_les_hits_frais_ne_rechargent_pas() {
    QuartierTrafficCache cache = new QuartierTrafficCache(3600); // TTL 1 h
    AtomicInteger loads = new AtomicInteger();

    QuartierTrafficCache.Cached first =
        cache.get(
            "rel_1",
            () -> {
              loads.incrementAndGet();
              return RESULT_OK;
            });
    QuartierTrafficCache.Cached second =
        cache.get(
            "rel_1",
            () -> {
              loads.incrementAndGet();
              return RESULT_PARTIAL; // ne doit JAMAIS être appelé
            });

    assertThat(loads).hasValue(1);
    assertThat(first.result().partial()).isFalse();
    assertThat(second.result().partial()).isFalse(); // même snapshot, pas de reload
    assertThat(second.staleServed()).isFalse();
    assertThat(second.ageMs()).isGreaterThanOrEqualTo(0);
  }

  @Test
  void stale_servi_puis_refresh_en_fond() {
    QuartierTrafficCache cache = new QuartierTrafficCache(0); // TTL nul -> toujours périmé
    AtomicInteger loads = new AtomicInteger();

    QuartierTrafficCache.Cached first =
        cache.get(
            "rel_2",
            () -> {
              loads.incrementAndGet();
              return RESULT_OK;
            });
    QuartierTrafficCache.Cached stale =
        cache.get(
            "rel_2",
            () -> {
              loads.incrementAndGet();
              return RESULT_PARTIAL;
            });

    // Sert l'ancien snapshot immédiatement, marqué stale, pendant que le refresh tourne en fond.
    assertThat(stale.staleServed()).isTrue();
    assertThat(stale.result().partial()).isFalse();
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(loads).hasValue(2)); // refresh en fond exécuté

    // Le snapshot rafraîchi est maintenant servi.
    QuartierTrafficCache.Cached refreshed =
        cache.get(
            "rel_2",
            () -> {
              loads.incrementAndGet();
              return RESULT_OK;
            });
    assertThat(refreshed.result().partial()).isTrue();
  }

  @Test
  void get_concurrent_ne_charge_qu_une_fois() throws Exception {
    QuartierTrafficCache cache = new QuartierTrafficCache(3600);
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch release = new CountDownLatch(1);
    java.util.function.Supplier<TrafficResult> loader =
        () -> {
          loads.incrementAndGet();
          try {
            release.await(); // bloque : si le 2e get chargeait aussi, loads passerait à 2
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return RESULT_OK;
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> f1 = pool.submit(() -> cache.get("rel_3", loader));
      Future<?> f2 = pool.submit(() -> cache.get("rel_3", loader));

      await()
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(() -> assertThat(loads).hasValue(1)); // single-flight
      release.countDown();
      f1.get();
      f2.get();
    } finally {
      pool.shutdownNow();
    }
    assertThat(loads).hasValue(1);
  }

  @Test
  void un_echec_du_chargement_n_est_pas_cache() {
    QuartierTrafficCache cache = new QuartierTrafficCache(3600);
    AtomicInteger loads = new AtomicInteger();

    assertThatThrownBy(
            () ->
                cache.get(
                    "rel_4",
                    () -> {
                      loads.incrementAndGet();
                      throw new IllegalStateException("404 simulé");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(loads).hasValue(1);

    // L'échec n'a rien caché : l'appel suivant re-tente.
    assertThat(
            cache
                .get(
                    "rel_4",
                    () -> {
                      loads.incrementAndGet();
                      return RESULT_OK;
                    })
                .result()
                .partial())
        .isFalse();
    assertThat(loads).hasValue(2);
  }
}
