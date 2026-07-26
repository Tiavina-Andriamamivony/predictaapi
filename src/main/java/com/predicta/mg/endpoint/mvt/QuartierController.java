package com.predicta.mg.endpoint.mvt;

import com.predicta.mg.models.QuartierView;
import com.predicta.mg.services.QuartierService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Recherche de quartiers de Tana pour recentrer la carte trafic. */
@RestController
@RequiredArgsConstructor
public class QuartierController {

  private final QuartierService quartierService;

  @GetMapping("/quartiers")
  @Operation(summary = "Recherche de quartiers (nom + centroïde pour recentrer la carte)")
  public List<QuartierView> search(@RequestParam(defaultValue = "") String q) {
    return quartierService.search(q);
  }
}
