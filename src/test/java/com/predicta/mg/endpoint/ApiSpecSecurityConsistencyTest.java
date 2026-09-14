package com.predicta.mg.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.yaml.snakeyaml.Yaml;

/**
 * Garde-fou de cohérence entre {@code doc/api.yml} et {@link ApiKeyConfigurer}.
 *
 * <p>L'écart entre les deux est ce qui a rendu possible la régression corrigée ici : la spec a
 * annoncé pendant un temps que {@code /traffic/zone} et {@code /traffic/quartier} étaient publics,
 * alors que le code les destinait à être protégés — et une spec fausse devient vite la vérité pour
 * le client qui s'y fie. Ce test échoue dès que l'un des deux bouge sans l'autre.
 */
class ApiSpecSecurityConsistencyTest {

  private static final Path SPEC = Path.of("doc", "api.yml");

  private static final PathPatternParser PARSER = new PathPatternParser();

  /** Les patterns du code, évalués avec le moteur de Spring MVC (celui de l'intercepteur). */
  private static final PathPattern[] PROTECTED =
      Stream.of(ApiKeyConfigurer.PROTECTED_PATHS).map(PARSER::parse).toArray(PathPattern[]::new);

  @Test
  void chaque_chemin_est_documente_avec_la_meme_regle_que_dans_le_code() throws Exception {
    Map<String, Object> spec = chargerLaSpec();
    Map<String, Object> paths = sousMap(spec, "paths");
    assertThat(paths).isNotEmpty();

    for (Map.Entry<String, Object> path : paths.entrySet()) {
      String chemin = path.getKey();
      boolean protegeParLeCode = couvertParUnPattern(chemin);
      for (Map.Entry<String, Object> operation : sousMap(path.getValue()).entrySet()) {
        boolean annonceProtege = sousMapOuVide(operation.getValue()).containsKey("security");
        assertThat(annonceProtege)
            .as(
                "doc/api.yml : %s %s — le code %s la clé API, la spec dit l'inverse",
                operation.getKey().toUpperCase(),
                chemin,
                protegeParLeCode ? "exige" : "n'exige pas")
            .isEqualTo(protegeParLeCode);
      }
    }
  }

  @Test
  void le_passthrough_mvt_reste_documente_comme_public() throws Exception {
    Map<String, Object> spec = chargerLaSpec();
    Map<String, Object> tile = sousMap(sousMap(spec, "paths"), "/traffic/tile/{z}/{x}/{y}.mvt");

    assertThat(couvertParUnPattern("/traffic/tile/13/5177/4534.mvt")).isFalse();
    assertThat(sousMap(tile, "get")).doesNotContainKey("security");
  }

  private static boolean couvertParUnPattern(String chemin) {
    PathContainer container = PathContainer.parsePath(chemin);
    for (PathPattern pattern : PROTECTED) {
      if (pattern.matches(container)) {
        return true;
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> chargerLaSpec() throws Exception {
    assertThat(Files.exists(SPEC))
        .as("doc/api.yml doit être lisible depuis le répertoire du projet")
        .isTrue();
    try (InputStream in = Files.newInputStream(SPEC)) {
      return (Map<String, Object>) new Yaml().load(in);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> sousMap(Map<String, Object> source, String cle) {
    Object value = source.get(cle);
    assertThat(value).as("clé « %s » attendue dans doc/api.yml", cle).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> sousMap(Object source) {
    assertThat(source).isInstanceOf(Map.class);
    return (Map<String, Object>) source;
  }

  private static Map<String, Object> sousMapOuVide(Object source) {
    return source instanceof Map ? sousMap(source) : Map.of();
  }

  /** Rappel : les clés de méthode HTTP de la spec sont minuscules. */
  @Test
  void les_methodes_documentees_sont_des_methodes_http_valides() throws Exception {
    Map<String, Object> paths = sousMap(chargerLaSpec(), "paths");
    for (Map.Entry<String, Object> path : paths.entrySet()) {
      for (String methode : sousMap(path.getValue()).keySet()) {
        assertThat(List.of("get", "put", "post", "delete", "patch")).contains(methode);
      }
    }
  }
}
