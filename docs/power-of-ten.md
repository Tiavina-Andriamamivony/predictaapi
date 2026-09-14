# Power of Ten — comment ces règles sont appliquées ici

Les **Power of Ten** sont dix règles de codage publiées en 2006 par le JPL/NASA pour du logiciel
critique (*The Power of 10: Rules for Developing Safety-Critical Code*). L'objectif est de rendre le
code **sûr, prévisible et vérifiable automatiquement** : simplifier au maximum pour que les erreurs
subtiles n'aient plus d'endroit où se cacher.

Ce document dit, règle par règle, **comment elle est appliquée dans ce dépôt Java** — c'est-à-dire
quel outil la vérifie et où. Les règles non applicables en Java sont explicitement listées comme
telles plutôt que silencieusement ignorées.

## Où c'est vérifié

| Endroit | Ce qu'il applique |
|---|---|
| `config/checkstyle/checkstyle.xml` | règles 1, 2, 4, 6, 7, 8, 9 (analyse de la source) |
| `src/test/java/com/predicta/mg/architecture/PowerOfTenRulesTest.java` | règles 3, 6, 9 (analyse du bytecode via ArchUnit) |
| `build.gradle` → `options.compilerArgs` | règle 10 : `-Xlint:all -Werror` sur `main` **et** `test` |
| `.github/workflows/ci.yml` → job `quality` | exécute Checkstyle ; les règles ArchUnit tournent avec le job `test` |

```sh
./gradlew checkstyleMain checkstyleTest   # analyse statique
./gradlew test --tests "*PowerOfTenRulesTest"   # règles sur le bytecode
./gradlew build                           # les deux (checkstyle est branché sur `check`)
```

Le **scaffold POJA** (`@PojaGenerated` : `mail/`, `file/`, `concurrency/`, `datastructure/`,
contrôleurs health, `handler/`, `PojaApplication`) est **exclu** des analyseurs : il est régénéré au
déploiement, le corriger ne servirait à rien et la CI échouerait sur du code qui revient tout seul.

## Règle par règle

### 1. Flux de contrôle très simple (pas de `goto`, pas de récursion)

- `CyclomaticComplexity` (seuil 10, celui de la règle), `NestedIfDepth` (3), `NestedForDepth` (2),
  `FallThrough`, `MissingSwitchDefault`, `SimplifyBooleanExpression`, `SimplifyBooleanReturn`.
- `IllegalToken(LABELED_STAT)` : les labels sont l'équivalent Java du `goto`.
- La **récursion** n'est pas détectable par ces outils. Aucune n'existe dans le dépôt aujourd'hui :
  c'est un point de revue, pas une règle outillée. À outiller si du code récursif apparaît.

### 2. Boucles à borne fixe

- `RegexpSinglelineJava` interdit `while (true)` et `for (;;)` (commentaires ignorés).
- Au-delà de la syntaxe, l'esprit de la règle est appliqué là où il compte vraiment :
  `QuartierTrafficCache.evictOverCapacity()` calcule son nombre d'itérations **une fois**, au lieu de
  boucler « tant que le cache est trop plein » — sous écritures concurrentes, une telle boucle peut
  ne jamais converger. Même logique pour la file bornée du pool de fetch de `TrafficService`.

### 3. Pas d'allocation dynamique après l'initialisation

C'est la règle qui a coûté le plus cher ici, et elle est appliquée sur le **bytecode** :
`PowerOfTenRulesTest.regle3_aucun_pool_de_threads_cree_a_la_demande` interdit
`Executors.newFixedThreadPool / newCachedThreadPool / newScheduledThreadPool / newWorkStealingPool`
dans tout le code métier.

**Pourquoi c'est la bonne formulation :** ce n'est pas l'appel qui pose problème, c'est *l'endroit*.
`TrafficService` construisait son pool de fetch dans la méthode appelée à chaque requête : 16 threads
et 16 tuiles en mémoire **par appel simultané**, au lieu d'un pool partagé unique. C'est exactement
ce qui remplissait les 512 Mo du conteneur.

`Executors.newSingleThreadExecutor()` reste autorisé : un thread unique, daemon, borné par
construction — le pattern des refresh en fond (`OsmIndex`, `QuartierTrafficCache`).

Où vivent les pools partagés : en champ `final` du service qui les possède, fermés par `@PreDestroy`.
Une autre vérification (`regle3_les_services_ne_publient_pas_leur_pool`) empêche de détenir un
`ThreadPoolExecutor` réassignable.

Corollaire mémoire appliqué en même temps : **tout cache est borné**. `QuartierTrafficCache` a une
capacité (`scrape.quartier-cache-max-entries`) ; sans elle, chaque quartier visité gardait sa
`FeatureCollection` à vie.

### 4. Fonctions courtes (une page imprimée)

- `MethodLength` : **60 lignes** maximum, pour les méthodes **et** les constructeurs.
- `ParameterNumber` : 7 paramètres maximum, sur les méthodes (voir « écarts assumés » plus bas).

Deux méthodes dépassaient réellement lors de la mise en place et ont été découpées plutôt que
tolérées : `QuartierGeometryIndex.build()` (65 lignes → `centroids` / `voronoiDiagram` /
`envelopeWithMargin` / `assignCells` / `nearestQuartierId`) et
`MvtToGeoJsonConverter.decodeLineGeometry()`.

### 5. Densité minimale d'assertions (≈ 2 par fonction)

**Non exprimable par un analyseur statique en Java.** L'intention — rendre explicites les hypothèses
d'une fonction — est poursuivie autrement :

- validation des paramètres au démarrage (règle 7) plutôt que des `assert` dispersés ;
- la couverture est mesurée par JaCoCo et le seuil ne doit **jamais baisser** (voir
  `jacocoTestCoverageVerification` dans `build.gradle`) ;
- la suite de tests est le vrai filet : `src/test/java/**` couvre le découpage MVT, le filtrage
  géométrique, le cache, le filtre gzip et les patterns d'authentification.

Le candidat naturel pour aller plus loin est le **mutation testing (PIT)** sur
`com.predicta.mg.services.traffic` : la couverture de lignes dit ce qui est exécuté, le mutation
testing dit ce qui est *vérifié*.

### 6. Portée minimale des variables

- `HiddenField` (le masquage de champ hors constructeur), `VisibilityModifier`, `FinalClass`.
- `ignoreConstructorParameter = true` est **volontaire** : `this.x = x` dans un constructeur est la
  convention d'injection du dépôt (Lombok `@RequiredArgsConstructor`). La règle 6 vise la portée des
  variables, pas cette écriture.
- Sur le bytecode : tout champ statique doit être `final` (règle 6/9).

### 7. Vérifier les valeurs de retour et valider les paramètres

- `IllegalCatch` interdit d'attraper `Error` ; `IllegalThrows` interdit de déclarer `Error`/
  `Throwable` ; `EmptyCatchBlock` exige un bloc non vide (sauf variable nommée `expected`/`ignored`).
- Le compilateur fait le gros du travail : `-Xlint:all -Werror` sur `main` et `test` — un
  avertissement est une erreur, y compris dans les tests (les 5 avertissements *unchecked* qui
  traînaient dans `QuartierGeometryIndexTest` ont été corrigés pour cette raison).
- Au démarrage, les invariants de configuration échouent avec un message qui nomme la propriété
  fautive (`TrafficService` : `scrape.fetch-parallelism >= 1` ;
  `QuartierTrafficCache` : `scrape.quartier-cache-max-entries >= 1`).

### 8. Préprocesseur limité

**Sans objet en Java** (pas de macros, pas de `#include`). L'équivalent le plus proche est la
maîtrise des transformations à la compilation : les seuls annotation processors sont ceux déjà
déclarés dans `build.gradle` (Lombok). En ajouter un est une décision explicite, pas un effet de
bord d'une dépendance.

### 9. Usage restreint des pointeurs

- `IllegalImport` interdit `sun.*`, `sun.misc.*`, `jdk.internal.*` et `java.lang.reflect.*` dans le
  code métier.
- `RegexpSinglelineJava` interdit `System.exit(...)` (interrompt le processus sans dépiler le flux de
  contrôle) et `System.gc()`.
- Sur le bytecode : aucun accès à `sun..`, `sun.misc..`, `jdk.internal..`, et aucun champ statique
  mutable.

### 10. Compilation stricte et analyse statique

C'est la règle qui porte toutes les autres :

```gradle
options.compilerArgs += ['-Xlint:all,-processing', '-Werror']
```

`-processing` est retiré du lot : avec des annotations runtime (Spring, JPA, Jackson, Lombok), javac
signale « no processor claimed these annotations » — un faux positif, pas un défaut de code.

En plus : **Checkstyle** (règles ci-dessus, `maxWarnings = 0`) et **ArchUnit**. Le formatage reste
sous l'autorité unique de **google-java-format** (`format.sh` + le job `format`) : aucune règle de
mise en forme n'est dupliquée dans Checkstyle, pour ne pas créer deux autorités contradictoires.

## Écarts assumés

Ils sont listés ici pour ne pas passer pour des oublis.

| Sujet | Décision | Raison |
|---|---|---|
| `ParameterNumber` sur les constructeurs | désactivé | Le constructeur à 9 collaborateurs de `TrafficService` n'est pas une fonction trop longue : c'est un défaut de découpage, à traiter comme tel. Le masquer derrière un seuil à 9 aurait faussé le signal. **Dette connue** : `TrafficService` fait trop de choses (grille, fetch, filtre géométrique, cache) ; l'éclater en `QuartierTrafficLoader` + `TrafficTileFetcher` ferait tomber le constructeur à 4-5 dépendances. |
| Récursion (règle 1) | non outillée | Aucune récursion dans le dépôt ; outiller coûterait plus que ça ne rapporte aujourd'hui. |
| PIT / mutation testing (règle 5) | non installé | Prochaine étape naturelle, à faire sur le paquet `services.traffic` d'abord. |
| SpotBugs / PMD / Error Prone | non installés | Checkstyle + ArchUnit + `-Werror` couvrent déjà les dix règles. À ajouter seulement si un défaut réel passe au travers — un analyseur de plus n'est pas une amélioration en soi. |
| `spring.jpa.hibernate.ddl-auto=update` | inchangé | Un `validate` serait plus sûr (Flyway possède le schéma), mais le changement ne se vérifie qu'avec une base réelle : à faire avec un test d'intégration qui monte le schéma Flyway. |

## Références

- *The Power of 10: Rules for Developing Safety-Critical Code* — Gerard J. Holzmann, JPL/NASA, 2006.
- `NPR 7150.2` — NASA Software Engineering Requirements.
