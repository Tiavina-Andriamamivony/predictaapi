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

  /** Capacité des caches des tests : large, sauf pour le test d'éviction. */
  private static final int MAX_ENTRIES = 64;

  @Test
  void premier_get_charge_et_les_hits_frais_ne_rechargent_pas() {
    QuartierTrafficCache cache = new QuartierTrafficCache(3600, MAX_ENTRIES); // TTL 1 h
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
  void stale_servi_puis_refresh_en_fond() throws InterruptedException {
    QuartierTrafficCache cache =
        new QuartierTrafficCache(0, MAX_ENTRIES); // TTL nul -> toujours périmé
    AtomicInteger loads = new AtomicInteger();

    QuartierTrafficCache.Cached first =
        cache.get(
            "rel_2",
            () -> {
              loads.incrementAndGet();
              return RESULT_OK;
            });
    // TTL nul -> un hit dans la même milliseconde a un âge de 0 (frais, pas périmé) : on garantit
    // que l'âge dépasse 0 avant de demander le hit périmé.
    Thread.sleep(50);
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

    // Le snapshot rafraîchi devient visible : on attend (pas de course put/lecture) que le prochain
    // get serve RESULT_PARTIAL au lieu de l'ancien snapshot.
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              QuartierTrafficCache.Cached refreshed =
                  cache.get(
                      "rel_2",
                      () -> {
                        loads.incrementAndGet();
                        return RESULT_OK;
                      });
              assertThat(refreshed.result().partial()).isTrue();
            });
  }

  @Test
  void get_concurrent_ne_charge_qu_une_fois() throws Exception {
    QuartierTrafficCache cache = new QuartierTrafficCache(3600, MAX_ENTRIES);
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
    QuartierTrafficCache cache = new QuartierTrafficCache(3600, MAX_ENTRIES);
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

  /**
   * Sans borne, chaque quartier visité restait en mémoire à vie — c'était la fuite lente du
   * service. Au-delà de la capacité, le plus ancien cède la place et sera simplement rechargé.
   */
  @Test
  void le_cache_borne_evince_le_plus_ancien() throws InterruptedException {
    QuartierTrafficCache cache = new QuartierTrafficCache(3600, 2);
    AtomicInteger loads = new AtomicInteger();

    cache.get(
        "rel_1",
        () -> {
          loads.incrementAndGet();
          return RESULT_OK;
        });
    Thread.sleep(5); // garantit un horodatage strictement plus récent pour rel_2
    cache.get(
        "rel_2",
        () -> {
          loads.incrementAndGet();
          return RESULT_OK;
        });
    assertThat(cache.size()).isEqualTo(2);

    Thread.sleep(5);
    cache.get(
        "rel_3",
        () -> {
          loads.incrementAndGet();
          return RESULT_OK;
        });

    assertThat(cache.size()).isEqualTo(2); // borné, jamais 3
    assertThat(loads).hasValue(3);

    // rel_1 (le plus ancien chargé) a cédé sa place ; rel_2 toujours en cache -> aucun
    // rechargement.
    cache.get(
        "rel_2",
        () -> {
          loads.incrementAndGet();
          return RESULT_OK;
        });
    assertThat(loads).hasValue(3);

    // rel_1 évincé -> rechargé, et c'est rel_2 (désormais le plus ancien) qui cède sa place.
    cache.get(
        "rel_1",
        () -> {
          loads.incrementAndGet();
          return RESULT_OK;
        });
    assertThat(loads).hasValue(4);
    assertThat(cache.size()).isEqualTo(2);

    cache.get(
        "rel_2",
        () -> {
          loads.incrementAndGet();
          return RESULT_OK;
        });
    assertThat(loads).hasValue(5);
    assertThat(cache.size()).isEqualTo(2); // la capacité reste tenue à chaque insertion
  }

  @Test
  void une_capacite_invalide_est_refusee_au_demarrage() {
    assertThatThrownBy(() -> new QuartierTrafficCache(45, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scrape.quartier-cache-max-entries");
  }
}
