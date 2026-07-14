package com.predicta.mg.services;

import com.predicta.mg.models.Quartier;
import com.predicta.mg.repository.QuartierRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Recherche de quartiers pour recentrer la carte trafic. */
@Service
@RequiredArgsConstructor
public class QuartierService {

  private final QuartierRepository repository;

  /** Vue carte : nom + centroïde, suffit pour un fly-to. */
  public record QuartierView(String name, double lon, double lat) {
    static QuartierView of(Quartier q) {
      return new QuartierView(q.getName(), q.getCentroidLon(), q.getCentroidLat());
    }
  }

  /** Quartiers dont le nom contient {@code q} (insensible casse) ; {@code q} vide = tous. */
  public List<QuartierView> search(String q) {
    List<Quartier> found =
        q.isBlank()
            ? repository.findAll()
            : repository.findByNameContainingIgnoreCaseOrderByName(q);
    return found.stream().map(QuartierView::of).toList();
  }
}
