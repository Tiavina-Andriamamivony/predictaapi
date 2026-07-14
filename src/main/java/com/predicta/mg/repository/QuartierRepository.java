package com.predicta.mg.repository;

import com.predicta.mg.models.Quartier;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuartierRepository extends JpaRepository<Quartier, String> {

  /** Recherche par sous-chaîne insensible à la casse, triée par nom. */
  List<Quartier> findByNameContainingIgnoreCaseOrderByName(String name);
}
