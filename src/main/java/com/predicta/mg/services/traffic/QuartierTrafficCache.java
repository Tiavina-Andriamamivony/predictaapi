package com.predicta.mg.services.traffic;

import com.predicta.mg.models.TrafficResult;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cache mémoire des réponses {@code /traffic/quartier}, par quartier. TTL court ({@code
 * scrape.quartier-cache-ttl-seconds}, 45 s par défaut) : après le 1er appel, un quartier consulté
 * en boucle (carte qui poll toutes les 15-30 s) coûte <b>0 fetch externe</b> et ~0 ms.
 *
 * <p>Stale-while-revalidate (pattern {@code OsmIndex}) : TTL expiré -> on sert le snapshot
 * précédent (borné à ~2×TTL) et on relance un refresh en fond ; l'utilisateur voit toujours un état
 * complet et daté, jamais un trou. Miss -> chargement synchrone avec single-flight (une seule
 * charge même si plusieurs requêtes arrivent ensemble). Les échecs ne sont pas cachés (404 inclus).
 */
@Component
@Slf4j
public class QuartierTrafficCache {

  private record Entry(TrafficResult result, long loadedAtMs) {}

  /** Résultat d'un {@code get} : la donnée + son âge + si elle est servie périmée. */
  public record Cached(TrafficResult result, long ageMs, boolean staleServed) {}

  private final long ttlMs;
  private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, CompletableFuture<Entry>> inflight =
      new ConcurrentHashMap<>();
  private final Set<String> refreshing = ConcurrentHashMap.newKeySet();
  private final ExecutorService refresher =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "quartier-cache-refresher");
            t.setDaemon(true);
            return t;
          });

  public QuartierTrafficCache(@Value("${scrape.quartier-cache-ttl-seconds:45}") long ttlSeconds) {
    this.ttlMs = ttlSeconds * 1000;
  }

  /**
   * Rend le résultat du quartier : hit frais, hit périmé (refresh en fond), ou miss (charge
   * synchrone single-flight). Ne lève que si le chargement initial échoue (ex. 404 quartier).
   */
  public Cached get(String quartierId, Supplier<TrafficResult> loader) {
    long now = System.currentTimeMillis();
    Entry entry = cache.get(quartierId);
    if (entry != null) {
      long age = now - entry.loadedAtMs();
      if (age > ttlMs) {
        refresh(quartierId, loader);
        return new Cached(entry.result(), age, true);
      }
      return new Cached(entry.result(), age, false);
    }
    Entry fresh = loadSync(quartierId, loader);
    return new Cached(fresh.result(), 0, false);
  }

  private void refresh(String quartierId, Supplier<TrafficResult> loader) {
    if (!refreshing.add(quartierId)) {
      return;
    }
    refresher.submit(
        () -> {
          try {
            cache.put(quartierId, new Entry(loader.get(), System.currentTimeMillis()));
          } catch (Throwable t) {
            log.warn(
                "Refresh cache quartier {} échoué (stale servi ce coup-ci) : {}",
                quartierId,
                t.getMessage());
          } finally {
            refreshing.remove(quartierId);
          }
        });
  }

  private Entry loadSync(String quartierId, Supplier<TrafficResult> loader) {
    CompletableFuture<Entry> future =
        inflight.computeIfAbsent(
            quartierId,
            id ->
                CompletableFuture.supplyAsync(
                    () -> new Entry(loader.get(), System.currentTimeMillis()), refresher));
    try {
      Entry entry = future.get();
      cache.put(quartierId, entry);
      return entry;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Chargement quartier interrompu : " + quartierId, e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Chargement quartier échoué : " + quartierId, e.getCause());
    } finally {
      inflight.remove(quartierId, future);
    }
  }
}
