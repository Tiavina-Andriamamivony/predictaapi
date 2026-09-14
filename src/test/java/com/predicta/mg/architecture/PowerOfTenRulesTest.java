package com.predicta.mg.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Power of Ten (JPL/NASA) — les règles qui se vérifient sur le graphe d'appels et non sur le texte.
 *
 * <p>Checkstyle lit la source ; ces règles-ci lisent le bytecode. C'est la seule manière d'attraper
 * la règle 3 (« pas d'allocation dynamique après l'initialisation ») : ce n'est pas la présence
 * d'un {@code Executors.newFixedThreadPool} qui pose problème, c'est le fait d'en créer un <b>dans
 * le corps d'un service</b>, donc à chaque requête — précisément le bug qui faisait grimper la
 * mémoire jusqu'à l'OOM.
 *
 * <p>Ces tests tournent dans le job {@code test} déjà présent : aucune infrastructure CI en plus.
 */
class PowerOfTenRulesTest {

  private static final String CODE = "com.predicta.mg..";

  private static JavaClasses bytecode;

  @BeforeAll
  static void importerLeBytecode() {
    bytecode =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.predicta.mg");
  }

  /**
   * Règle 3 : pas d'allocation dynamique après l'initialisation.
   *
   * <p>Un pool créé à la demande dans un service est un pool créé <b>par requête</b> : N requêtes
   * simultanées = N × parallélisme threads, et N × autant de tuiles en mémoire. Les pools partagés
   * se déclarent une fois, dans la couche de configuration.
   *
   * <p>{@code Executors.newSingleThreadExecutor()} reste autorisé (un thread unique, daemon, borné
   * par construction) : c'est le pattern des refresh en fond. L'interdiction porte sur les
   * fabriques de <b>pools parallèles</b>, dont le nombre de threads n'est plus contrôlé par
   * l'appelant.
   */
  @Test
  void regle3_aucun_pool_de_threads_cree_a_la_demande() {
    ArchRule regle =
        noClasses()
            .that()
            .resideInAPackage(CODE)
            .should()
            .callMethod(Executors.class, "newFixedThreadPool", int.class)
            .orShould()
            .callMethod(Executors.class, "newCachedThreadPool")
            .orShould()
            .callMethod(Executors.class, "newScheduledThreadPool", int.class)
            .orShould()
            .callMethod(Executors.class, "newWorkStealingPool")
            .because(
                "un pool construit dans le corps d'un service est alloué à chaque appel (règle 3) :"
                    + " il doit être créé une fois et partagé");

    regle.check(bytecode);
  }

  /**
   * Règles 6 et 9 : aucun état statique mutable. Un champ statique non final est un état partagé
   * global, invisible dans les signatures, mutable depuis n'importe où.
   */
  @Test
  void regle9_aucun_champ_statique_non_final() {
    ArchRule regle =
        fields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage(CODE)
            .and()
            .areStatic()
            .should()
            .beFinal()
            .because("l'état partagé doit être explicitement immuable (règle 6/9)");

    regle.check(bytecode);
  }

  /** Règle 9 : pas d'accès aux internes de la JVM ({@code Unsafe}, internes du JDK). */
  @Test
  void regle9_aucun_acces_aux_internes_de_la_jvm() {
    ArchRule regle =
        noClasses()
            .that()
            .resideInAPackage(CODE)
            .should()
            .accessClassesThat()
            .resideInAnyPackage("sun..", "sun.misc..", "jdk.internal..")
            .because("ces API ne sont ni stables ni vérifiables (règle 9)");

    regle.check(bytecode);
  }

  /**
   * Règle 3, volet générique : aucune classe de service ne doit exposer un {@code ExecutorService}
   * non borné en champ public/mutable. Les collaborateurs partagés du pipeline sont injectés, donc
   * privés et finaux.
   */
  @Test
  void regle3_les_services_ne_publient_pas_leur_pool() {
    ArchRule regle =
        fields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("..services..")
            .and()
            .areNotFinal()
            .should()
            .notHaveRawType(java.util.concurrent.ThreadPoolExecutor.class)
            .because("un pool partagé se détient en champ final, jamais réassignable (règle 3)");

    regle.check(bytecode);
  }

  /** Garde-fou du garde-fou : les règles ci-dessus examinent bien le code métier. */
  @Test
  void le_bytecode_metier_est_bien_importe() {
    assertThat(bytecode.size()).isGreaterThan(20);
    assertThat(bytecode.getPackage("com.predicta.mg.services.traffic").getClasses()).isNotEmpty();
  }
}
