# Bedrock Mod Studio — Phase 1 : audit et plan

Date de l'audit : 20 octobre 2026.

Ce document rend compte de l'inspection du dépôt, de la vérification de l'environnement de compilation et du plan technique. Il ne contient aucun code d'application : comme demandé, le développement commence à la phase 2.

---

## 1. Ce que contient le dépôt

| Élément | Constat |
|---|---|
| Dépôt local `MOD-MINECRAFT-APP` | **Vide** : aucun fichier, aucun commit. |
| Dépôt distant `github.com/julienots/MOD-MINECRAFT-APP` | **Vide** : aucune branche publiée. |
| Branche de travail | `ccr-196279a0-pv03r9` (créée par ce commit). |

Il n'y a donc aucune application existante à préserver : le projet part de zéro. Ce document est le premier fichier du dépôt.

---

## 2. Environnement de compilation : ce qui a été vérifié

Chaque point ci-dessous a été **réellement exécuté** dans l'environnement de développement (conteneur Linux dans le cloud), pas supposé.

| Outil | État constaté |
|---|---|
| Java | OpenJDK 21 installé. |
| Gradle | 8.14.3 installé. |
| SDK Android | **Absent au départ.** Installé avec succès depuis `dl.google.com` : command-line tools, `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`. Licences acceptées. |
| Accès réseau | Google Maven, Maven Central, Gradle Plugin Portal, npm et GitHub accessibles. |
| Maven Central | Renvoie par moments **HTTP 429 (« trop de requêtes »)**. Le build passe après quelques relances, une fois le cache rempli. |
| Émulateur Android | **Impossible** : pas de virtualisation matérielle (`/dev/kvm` absent). |
| Minecraft Bedrock | **Non disponible** dans l'environnement. |

### Test de compilation réel

Un projet jetable minimal (Kotlin 2.2.21, Android Gradle Plugin 8.13.2, Jetpack Compose BOM 2025.06.00, `compileSdk 35`, `minSdk 26`) a été compilé hors du dépôt :

```
BUILD SUCCESSFUL → app-debug.apk (9,3 Mo)
aapt2 dump badging : compileSdkVersion='35' minSdkVersion:'26'
```

**Conclusion : un APK Android peut être compilé directement ici.** Deux problèmes rencontrés et résolus pendant ce test :
1. `jvmToolchain(17)` échoue (seul le JDK 21 est installé) → on fixe la cible bytecode à Java 17 sans exiger de JDK 17.
2. Erreurs 429 de Maven Central → relances avec pause.

### Ce que l'environnement ne permet pas

- **Lancer l'application** sur un émulateur ou un téléphone. Les tests d'interface se feront avec Robolectric (simulation Android sur la JVM), et un test manuel sur ton téléphone restera indispensable.
- **Charger un addon dans Minecraft.** Aucun addon ne sera déclaré « testé en jeu » tant que tu ne l'auras pas ouvert dans Minecraft. Les tests automatiques vérifient la structure des fichiers, pas le comportement en jeu.

### Compilation de secours : GitHub Actions

Pour ne pas dépendre de ce conteneur temporaire, la phase 2 ajoutera un workflow GitHub Actions qui, à chaque envoi de code :
1. installe le SDK Android ;
2. lance tous les tests ;
3. compile l'APK ;
4. le publie comme « artefact » téléchargeable dans l'onglet **Actions** du dépôt GitHub (la procédure pas à pas sera dans la documentation utilisateur).

---

## 3. Références Bedrock vérifiées

Sources consultées : le dépôt officiel `Mojang/bedrock-samples` et les paquets npm officiels `@minecraft/*`.

| Référence | Valeur constatée |
|---|---|
| Dernière version des échantillons Mojang | **1.26.50.4** (15/09/2026) ; précédentes : 1.26.40.5, 1.26.30.5 |
| Manifeste d'exemple | `format_version: 2`, `min_engine_version: [1, 26, 50]` |
| Entité (behavior) | `format_version: "1.26.50"` |
| Entité client (resource) | `format_version: "1.26.0"` |
| Biomes data-driven | présents dans `behavior_pack/biomes/` (`format_version: "1.26.0"`) |
| `@minecraft/server` stable | **2.10.0** (publié le 15/09/2026, même jour que 1.26.50) ; bêta 2.13.0-beta (preview 1.26.70) |
| `@minecraft/server-ui` stable | **2.2.0** |

**Licence :** le dépôt `bedrock-samples` n'a pas de fichier de licence permettant la redistribution. Il servira **uniquement de référence de format** ; aucune de ses textures, modèles ou sons ne sera intégré à l'application. La bibliothèque intégrée contiendra uniquement des ressources créées pour le projet.

---

## 4. Architecture choisie et justification

### Choix : Android natif, Kotlin + Jetpack Compose

| Besoin | Pourquoi Kotlin/Compose natif |
|---|---|
| Fichiers (import/export) | Accès direct au Storage Access Framework (sélecteur de fichiers Android), à `FileProvider` et au partage vers Minecraft, sans couche intermédiaire. |
| Archives `.mcaddon`/`.mcpack` | `java.util.zip` est intégré, fiable, testable sur la JVM. |
| 3D | OpenGL ES 3 natif, contrôle total de la mémoire et du rendu des modèles en cubes. |
| Interface tactile | Compose + Material 3 : thème sombre, navigation inférieure, gestes. |
| Tests | Le cœur (génération, validation, export) est en Kotlin pur, testable sur la JVM sans téléphone. |
| Hors ligne | Rien ne dépend d'un serveur. |

**Alternatives écartées :**
- *Flutter / React Native* : le rendu 3D personnalisé, les archives et le SAF demanderaient des ponts natifs ; on écrirait de toute façon une grande partie en Kotlin.
- *Kotlin Multiplatform* : intéressant pour une version ordinateur plus tard. La séparation en modules Kotlin purs ci-dessous rend cette évolution possible sans tout réécrire.
- *Moteur 3D complet (Filament, Unity)* : surdimensionné pour des modèles en cubes, alourdit l'APK et complique l'édition.

### Versions cibles Android

- `minSdk 26` (Android 8.0) : couvre la grande majorité des téléphones en service.
- `targetSdk / compileSdk 35` (Android 15), versions vérifiées par la compilation.
- Aucune permission de stockage large : tout passe par le sélecteur de fichiers Android.

### Découpage en modules

```
app/                    Interface Compose, navigation, écrans
core/model/             Modèle de données du projet (Kotlin pur)
core/bedrock/           Générateurs de fichiers Bedrock + matrice de versions (Kotlin pur)
core/validation/        Validation JSON, références, UUID, dépendances (Kotlin pur)
core/packaging/         Export/import .mcpack, .mcaddon, .zip, protection des archives (Kotlin pur)
core/storage/           Projets sur le disque, sauvegarde auto, historique, journal
core/graph/             Éditeur de logique : graphe + compilateur vers Bedrock (Kotlin pur)
feature/model3d/        Éditeur de modèles + rendu OpenGL ES
feature/texture/        Éditeur de pixel art
feature/animation/      Timeline d'animation
feature/script/         Éditeur de scripts
```

Les modules `core/*` n'utilisent pas Android : ils sont compilés et testés sur la JVM, ce qui rend les tests rapides et fiables ici comme dans GitHub Actions.

### Stockage d'un projet

Chaque projet est un dossier privé de l'application :

```
projects/<id>/
  project.json            Métadonnées éditeur (nom, version cible, contenus, UUID)
  behavior_pack/          Arborescence Bedrock réelle (manifest.json, entities/, items/, blocks/, recipes/,
                          functions/, loot_tables/, spawn_rules/, structures/, scripts/, ...)
  resource_pack/          (manifest.json, entity/, models/entity/, animations/, animation_controllers/,
                          render_controllers/, textures/, sounds/, particles/, texts/fr_FR.lang, ...)
  .history/               Instantanés pour annuler/restaurer
  .imported/              Originaux importés, jamais modifiés
```

Les fichiers Bedrock sont la source de vérité sur le disque. Les assistants écrivent dans ces fichiers ; l'éditeur JSON y a accès directement ; l'export n'a rien à « reconstruire » en cachette.

### Points techniques clés

| Sujet | Solution prévue |
|---|---|
| JSON Bedrock | Analyseur tolérant : Bedrock accepte les commentaires `//` et `/* */` dans ses JSON, le validateur doit les accepter aussi tout en signalant les vraies erreurs avec ligne et colonne. |
| UUID | Générés une fois à la création du projet, conservés pour toujours (Minecraft s'en sert pour reconnaître les mises à jour d'un pack). |
| Version du pack | Incrémentée automatiquement à chaque export pour que Minecraft accepte la mise à jour. |
| Archives malveillantes | Refus des chemins `../` et absolus (« zip slip »), plafond de taille décompressée et de nombre de fichiers (« zip bomb »). |
| Export | Fichier temporaire → validation → archive → relecture de l'archive → seulement alors renommage en `.mcaddon`. Une archive invalide ne reçoit jamais l'extension. |
| Ouvrir avec Minecraft | Partage via `FileProvider` + intention `ACTION_VIEW`. Minecraft importe les `.mcaddon` qu'on lui ouvre. |
| Sauvegarde auto | Écriture atomique (fichier temporaire puis renommage) + instantanés périodiques ; récupération proposée au démarrage après un arrêt brutal. |
| Fichiers `.mcstructure` | Format binaire NBT : un lecteur/écrivain NBT sera écrit et testé. |
| Scripts | Jamais exécutés dans l'application. Seule une analyse syntaxique est faite, dans un processus Android isolé. |
| Clés d'IA externes | Saisies par l'utilisateur, stockées chiffrées via le Keystore Android, jamais dans le code. |

---

## 5. Fonctionnalités : réalisables, partielles, limitées par Bedrock

Légende : ✅ réalisable avec les fonctions stables de Bedrock — 🟡 réalisable avec contournement, script ou expérimental — ❌ non faisable comme demandé (alternative indiquée).

| Fonctionnalité | Statut | Comment / limite |
|---|---|---|
| Mobs passifs, neutres, hostiles | ✅ | Composants `minecraft:entity` (santé, vitesse, IA, attaques, butin, apparition). |
| Boss multi-phases | ✅ | Groupes de composants + événements + `minecraft:boss` (barre de vie) ; seuils de vie via capteurs ou script. |
| Objets, armes, outils, armures | ✅ | Composants `minecraft:item` (durabilité, dégâts, `wearable`, réparation...). |
| Effets à l'utilisation, capacités | 🟡 | Composants personnalisés d'objets avec le Script API (code généré automatiquement). |
| Blocs personnalisés | ✅ | `minecraft:block` (géométrie, matériaux, états, permutations). Comportement interactif via composants personnalisés scriptés. |
| Recettes (établi, four, etc.) | ✅ | `recipe_shaped`, `recipe_shapeless`, `recipe_furnace`, `recipe_brewing_*`. |
| Tables de butin, apparition naturelle | ✅ | `loot_tables/`, `spawn_rules/`. |
| Modèles 3D en cubes, animations, contrôleurs | ✅ | Géométrie `minecraft:geometry`, animations et contrôleurs Bedrock. |
| Import OBJ / glTF | 🟡 | Bedrock n'affiche que des cubes : un maillage libre sera approximé ou refusé avec explication. Les modèles Blockbench au format Bedrock s'importent directement. |
| Particules | ✅ | Fichiers `particles/` (émetteurs et composants), aperçu simplifié dans l'app. |
| Sons | ✅ | `sounds/` + `sound_definitions.json`, formats `.ogg`/`.wav`. |
| Structures | ✅ | `.mcstructure` + commande `/structure` ou génération mondiale (feature rules). |
| Donjons générés aléatoirement | 🟡 | Les structures « jigsaw » data-driven existent dans les versions récentes : **support exact à vérifier** en phase 3 avant d'être proposé. Repli : placement par script. |
| Portails personnalisés | 🟡 | Bloc personnalisé + script qui détecte le cadre et téléporte, y compris **vers une dimension personnalisée**. Pas d'effet « portail du Nether » natif. |
| Dimensions personnalisées | ✅ (Script API, ≥ 1.26.30) | **Vérifié le 20/10/2026** (voir section 5 bis) : `registerCustomDimension` est dans l'API **stable** `@minecraft/server` depuis la 2.8.0 (Minecraft 1.26.30). La dimension naît **vide (générateur « void »)** : le terrain doit être construit par script ou par structures. |
| Biomes personnalisés | 🟡 | Fichiers `biomes/` existent ; la génération de terrain personnalisée dépend d'expérimentations. Avertissement affiché. |
| Interfaces personnalisées | 🟡 | Formulaires officiels `@minecraft/server-ui` (menus, boutons, curseurs). La modification du HUD par « JSON UI » n'est pas une API officielle : non prise en charge au départ. |
| Systèmes de magie, compétences, classes, quêtes, progression, familiers, compagnons, transformations | 🟡 | Générés en Script API + propriétés dynamiques + scoreboards. Réalisable, mais demande Script API : le pack nécessite la dépendance `@minecraft/server`. |
| Éditeur de logique par nœuds | ✅/🟡 | Compilé vers JSON (événements, filtres, groupes), contrôleurs d'animation, fonctions `.mcfunction` ou scripts. **Un nœud qui ne peut pas être traduit produit une erreur de compilation visible**, jamais un faux succès. |
| Test en jeu automatique | ❌ | Impossible depuis l'app. Workflow prévu : export de test → ouverture dans Minecraft → lecture du « Journal du contenu » (Paramètres → Créateur) → correction. |
| Assistant en langage naturel hors ligne | 🟡 | Analyse par mots-clés et modèles prédéfinis (règles déterministes, honnêtement limitées). |
| Assistant avec IA externe | ✅ (optionnel) | Si tu configures ta propre clé. L'IA produit un *plan structuré* validé par l'app, jamais des fichiers bruts acceptés sans contrôle. |
| Mods Java (Forge, Fabric, `.jar`) | ❌ | Incompatibles avec Bedrock. L'import les refusera avec une explication. |

## 5 bis. Vérification : dimensions personnalisées (demandée le 20/10/2026)

**Résultat : oui, on peut créer de vraies dimensions personnalisées dans un addon Bedrock**, à partir de Minecraft **1.26.30**.

Preuves consultées (définitions TypeScript officielles du paquet npm `@minecraft/server`, publié par Mojang) :

| Version `@minecraft/server` (stable) | Version Minecraft | `registerCustomDimension` |
|---|---|---|
| 2.5.0 → 2.7.0 | 1.26.0 → 1.26.20 | absent |
| **2.8.0** | **1.26.30** | **présent** |
| 2.9.0 | 1.26.40 | présent |
| 2.10.0 | 1.26.50 | présent |

Ce que dit la documentation officielle de l'API (texte des définitions) :
- L'enregistrement se fait **uniquement** pendant l'événement de démarrage `system.beforeEvents.startup`, via `event.dimensionRegistry.registerCustomDimension("espace:nom")`.
- L'identifiant doit avoir un espace de noms (`monaddon:ma_dimension`).
- La dimension est créée avec le **générateur vide (« void »)** : pas de terrain, pas de biome, pas de génération automatique.
- Une nouvelle dimension ne peut pas être ajoutée par la commande `/reload` : il faut recharger le monde.
- On y accède avec `world.getDimension("espace:nom")` puis `player.teleport(..., { dimension })`.

Ce que l'application générera (phase 3) :
1. Le script d'enregistrement de la dimension et la dépendance `@minecraft/server` dans le manifeste.
2. Une plateforme ou une structure d'arrivée (blocs posés par script, zone de chargement `tickingAreaManager` avant de construire ou de téléporter).
3. Un moyen d'y aller : portail (bloc personnalisé + détection du cadre), objet, ou commande personnalisée.
4. Optionnellement un terrain simple généré par script (îles, plateformes, salles de donjon).

Limites honnêtes :
- Pas de générateur de terrain intégré : tout terrain est construit par script, donc plus lent et plus limité qu'une génération native.
- Ciel, brouillard, biome et musique propres à la dimension : **non vérifiés**, ne seront proposés qu'après vérification.
- Le monde doit être en version **1.26.30 ou plus**. L'application bloquera l'option pour une version cible plus ancienne.
- Comme pour tout le reste, le fonctionnement réel devra être confirmé en jeu sur ton téléphone.

---

## 6. Matrice de versions Bedrock

La version cible est choisie par projet. Chaque profil de version est un fichier de données dans l'application qui décrit :
- `min_engine_version` et versions de format (`format_version`) par type de fichier ;
- les composants disponibles et ceux qui sont expérimentaux ;
- les versions des modules de script (`@minecraft/server`, `@minecraft/server-ui`).

Profils de départ prévus :

| Profil | Statut |
|---|---|
| **1.26.50** (actuelle) | Vérifié à partir des échantillons officiels, `@minecraft/server 2.10.0`. Profil par défaut. |
| 1.26.30, 1.26.40 | Vérifiés à partir des échantillons officiels (tags `v1.26.30.5`, `v1.26.40.05`) et de `@minecraft/server` 2.8.0 / 2.9.0. |
| Versions antérieures | Ajoutées uniquement après vérification, jamais devinées. |

Les fonctionnalités non disponibles dans la version choisie seront grisées avec une explication.

---

## 7. Risques techniques

| Risque | Gravité | Mesure |
|---|---|---|
| Formats Bedrock qui évoluent à chaque version | Haute | Profils de version en fichiers de données, mis à jour à partir des échantillons officiels ; tests « golden » par profil. |
| Impossible de vérifier le chargement en jeu ici | Haute | Validation structurelle stricte + checklist de test manuel + tu fais le test réel sur ton téléphone. |
| Pas d'émulateur dans l'environnement | Moyenne | Logique dans des modules JVM testés ; interface testée avec Robolectric ; test manuel de l'APK. |
| Maven Central limite le débit (429) | Basse | Relances ; GitHub Actions comme compilation de référence. |
| Signature de l'APK | Moyenne | Un APK signé avec une clé différente ne peut pas mettre à jour le précédent. Une clé de signature stable sera générée et stockée en secret GitHub (jamais dans le dépôt). Procédure expliquée. |
| Mémoire (textures, modèles, archives) | Moyenne | Lecture en flux des archives, plafonds de taille, textures limitées à des tailles raisonnables. |
| Ampleur du cahier des charges | Haute | Livraison par paliers, chacun compilé, testé et utilisable avant le suivant. |

---

## 8. Plan de développement

Chaque palier se termine par : tests verts, APK compilé, documentation à jour, commit et envoi sur la branche.

### Phase 2 — Socle fonctionnel (priorité absolue)

1. Squelette Gradle multi-modules, thème sombre, navigation inférieure (Accueil, Projets, Bibliothèque, Paramètres), textes en français dans des ressources traduisibles.
2. Workflow GitHub Actions : tests + APK téléchargeable.
3. Modèle de projet, création de projet (nom, description, version cible), génération des deux manifestes avec UUID et dépendance BP → RP.
4. Stockage : sauvegarde automatique atomique, liste des projets récents, récupération après crash, journal des erreurs.
5. Explorateur de fichiers : arborescence, ouverture, création, renommage, duplication, suppression **avec confirmation**, recherche d'identifiant.
6. Éditeur JSON : coloration, numéros de ligne, erreurs de syntaxe localisées, formatage.
7. Historique : instantanés, restauration d'une version antérieure.
8. Centre de validation : JSON, manifestes, UUID, identifiants en double, références manquantes (textures, géométries, animations).
9. **Export** `.mcpack` (par pack), `.mcaddon` (les deux), `.zip` de sauvegarde ; enregistrement via le sélecteur Android ; partage / ouverture dans Minecraft.
10. **Import** `.mcaddon`, `.mcpack`, `.zip` avec rapport d'import et conservation des originaux.
11. Un projet d'exemple complet (un mob simple avec texture et modèle originaux).

### Phase 3 — Éditeurs avancés (dans cet ordre)

1. Assistant mob (tous les champs listés, avec explications) → entité BP + RP + traduction + butin + apparition.
2. Assistants objet / arme / outil / armure / bloc / recette.
3. Éditeur de textures pixel art.
4. Éditeur de modèles 3D (cubes, groupes, pivots, UV, caméra orbitale tactile, annuler/rétablir).
5. Éditeur d'animations avec timeline.
6. Assistant boss multi-phases.
7. Éditeur de logique par nœuds + compilateur.
8. Éditeur de scripts + modèles de scripts + validation des dépendances d'API.
9. Structures (NBT `.mcstructure`), coffres et butin, portails par script, **dimensions personnalisées** (≥ 1.26.30).
10. Systèmes de gameplay (magie, classes, quêtes, progression...) générés en Script API.
11. Bibliothèque de composants originaux, assistant en langage naturel (hors ligne + IA optionnelle).

### Phase 4 — Tests et corrections

Boucle systématique : test → cause racine → correction → non-régression.

### Phase 5 — Livraison

APK, code source, guide d'installation, guide d'ouverture dans Minecraft, résultats des tests, limites restantes, documentation pour débutants.

---

## 9. Tests prévus

| Niveau | Contenu |
|---|---|
| Unitaires (JVM) | Génération de chaque type de fichier, JSON tolérant aux commentaires, UUID, versionnage, NBT, compilateur de graphes. |
| « Golden files » | Le fichier généré est comparé à un fichier de référence vérifié à la main contre les échantillons officiels. |
| Archives | Export puis relecture : structure attendue, manifeste à la racine de chaque pack, aucune entrée invalide ; refus des archives `../` et des archives géantes. |
| Import/export aller-retour | Importer → exporter → comparer : aucun fichier perdu, fichiers inconnus conservés. |
| Validation | Projets volontairement cassés (texture manquante, UUID dupliqué, JSON invalide) → l'erreur attendue est détectée et l'export est bloqué. |
| Interface (Robolectric/Compose) | Parcours « Créer un mod → assistant mob → exporter ». |
| Test manuel sur téléphone | Checklist : installer l'APK, créer, exporter, ouvrir dans Minecraft, vérifier le Journal du contenu. **Seul ce test prouve le fonctionnement en jeu.** |

---

## 10. État d'avancement

### Phase 2 — socle : livrée (20/10/2026)

Points 1 à 11 du plan de phase 2 réalisés, avec deux écarts assumés :
- le cœur est **un seul module** `core/` (paquets `json`, `bedrock`, `storage`, `validation`, `packaging`, `image`) au lieu de cinq : plus simple à compiler, découpable plus tard sans réécriture ;
- un **assistant de créature de base** a été avancé de la phase 3, car il sert au projet d'exemple et permet déjà le parcours complet « créer → exporter → jouer ».

Vérification croisée avec les échantillons officiels 1.26.50 : chaque composant d'entité généré est utilisé par des entités vanilla. Correction faite grâce à elle : `minecraft:behavior.melee_attack` (1 seule entité vanilla) remplacé par `minecraft:behavior.melee_box_attack` (32 entités vanilla).

Tests exécutés dans l'environnement de développement :

| Suite | Résultat |
|---|---|
| `:core:test` (JSON, projets, historique, corbeille, validation, export, import, zip slip, bombe zip, refus des mods Java) | 32/32 ✅ |
| `:app:testDebugUnitTest` (Robolectric : exemple → vérifier → exporter ; nouveau projet → créature ; éditeur → erreur JSON → enregistrement → historique ; paramètres) | 4/4 ✅ |
| `:app:lintDebug` (y compris compatibilité Android 8 du module cœur) | 0 erreur ✅ |
| `:app:assembleDebug` | APK signé ✅ |

**Non testé :** chargement réel dans Minecraft, et exécution sur un vrai téléphone. Ces deux essais sont à faire en premier.

### Phase 3a — objets, blocs, recettes : livrée (21/10/2026)

- Générateurs : objets (simple, nourriture, épée, pioche, hache, pelle, 4 pièces d'armure avec rendu porté), blocs (6 styles, lumière, son, butin), recettes (avec forme, sans forme, four/fumoir/haut fourneau).
- Formats vérifiés : fichiers vanilla de `bedrock-samples` v1.26.50.4 (pomme, lance, armures en fer, recettes) et pages Microsoft Learn (`minecraft:wearable`, `minecraft:digger`, `minecraft:glint`, `minecraft:enchantable`, `minecraft:damage`, tutoriel du bloc dé). Étiquettes d'objets vérifiées sur les fichiers vanilla et le tableau communautaire bedrock.dev.
- Catalogue des composants connus (402 d'entités, 50 d'objets, 37+1 de blocs) généré à partir des trois versions d'échantillons et des listes officielles : le validateur propose une correction pour les fautes de frappe (« minecraft:helth » → « minecraft:health »).
- Tests : 41 tests du cœur, 5 parcours d'interface. **Non testé en jeu.**

Point non vérifié à surveiller lors de l'essai en jeu : la capacité d'une pioche personnalisée à récolter les minerais qui exigent un niveau minimum.

### Phase 3b à 3g : livrées (21/10/2026)

| Lot | Contenu | Vérification |
|---|---|---|
| 3b | Éditeur de textures pixel art | tests du cœur (trait, remplissage 1024², redimensionnement) + parcours « dessin au doigt » |
| 3c | Scripts : activation, `main.js` géré, validation des imports | **TypeScript contre @minecraft/server 2.8.0 / 2.9.0 / 2.10.0** en CI ; contrôle négatif vérifié |
| 3d | Dimensions personnalisées, portail, clé, commande | scripts vérifiés par TypeScript ; parcours d'interface |
| 3e | Boss multi-phases | capteur `environment_sensor` + `int_property` + groupes de composants, formats relevés dans les fichiers vanilla ; script de messages vérifié |
| 3f | Éditeur de comportements par blocs | chaque événement/condition/action compilé ensemble et vérifié par TypeScript |
| 3g | Éditeur 3D et animations | lecture/écriture .geo.json (formats moderne et ancien), patron UV, normales, OBJ, animations (Molang conservé) ; parcours d'interface |

Limite de l'environnement de test découverte : l'Android simulé (Robolectric) se bloque dès qu'une boîte de dialogue contient un champ de texte. Ces boîtes (renommer, nouveau fichier, nouvelle texture…) ne sont donc pas couvertes par les tests automatiques et doivent être essayées sur un téléphone.

**Toujours non testé en jeu.**

### Phase 3h — structures : livrée (21/10/2026)

- Lecture/écriture NBT petit-boutiste et du format `.mcstructure` (ordre des blocs `(x × tailleY + y) × tailleZ + z`, deux couches, palette, données de blocs, entités conservées). Source : wiki.bedrock.dev (format communautaire, pas de documentation Microsoft complète).
- Éditeur par couches + aperçu 3D, modèles de départ (maison, tour, salle au trésor, arche), coffres à butin.
- Apparition naturelle : `features/` (`minecraft:structure_template_feature`) et `feature_rules/` (format 1.13.0, Microsoft Learn).
- Action « Placer une structure » (`world.structureManager.place`) vérifiée par TypeScript.
- Vérification : en-tête NBT octet par octet, aller-retour, fichiers endommagés refusés, références cassées détectées, parcours d'interface complet.
- **Non vérifié en jeu** : le champ `LootTable` des coffres et la valeur `version` des blocs proviennent de la documentation communautaire.

### Prochaine étape : phase 3 (suite)

Phase 3i — bibliothèque de composants : livrée (21/10/2026). 56 composants (créatures, objets, blocs) dont le nom est contrôlé par test contre les listes extraites de Mojang/bedrock-samples et Microsoft Learn ; insertion avec confirmation avant remplacement.

Phase 3j — particules : livrée (21/10/2026). Format vérifié dans Microsoft Learn (particlesreference) et les fichiers vanilla `basic_flame.json` / `heart.json` ; seuls les éléments confirmés sont écrits (matériau `particles_alpha`, couleurs en tableaux, `facing_camera_mode`, formes point/sphère/boîte — la forme disque n'est pas proposée car son nom diffère selon les pages). Simulation testée (émission, expiration, gravité, salve unique) ; lecture prudente : un fichier avec du Molang passe en aperçu seul. Action « Afficher des particules » vérifiée par TypeScript.

Limite de l'environnement de test : l'Android simulé ne fait pas défiler deux zones imbriquées (rangée horizontale dans une page verticale) ; le parcours utilise alors l'action d'accessibilité équivalente au toucher.

Phase 3k — assistant en langage naturel : livrée (21/10/2026).

- **Plan structuré.** Les deux sources produisent le même plan : l'analyse hors ligne par mots-clés et l'IA facultative.
- **Validation.** Le plan est relu strictement : types connus, valeurs bornées, 8 étapes au maximum. Il est ensuite converti en fichiers par les générateurs existants, après accord de l'utilisateur.
- **IA facultative** (SDK officiel `com.anthropic:anthropic-java`, modèle `claude-opus-5-5`) :
  - la réponse est contrainte par un schéma JSON (sorties structurées) ;
  - repli côté serveur en cas de refus ;
  - la clé appartient à l'utilisateur et est chiffrée avec le Keystore Android ;
  - seul le texte de la demande est envoyé.
- **Ajout du réseau.** La permission `INTERNET` est ajoutée uniquement pour cette fonction. Le reste de l'application fonctionne hors ligne.
- **Vérification.**
  - Tests du cœur : demandes en français, plan JSON hostile (valeurs hors bornes, types inconnus), installation de chaque type d'étape avec un projet valide.
  - Parcours d'interface hors ligne.
  - Construction de la requête IA vérifiée sans réseau.
  - **Non vérifié** : un appel réel avec une clé, et le SDK sur un vrai téléphone.

Phase 3 terminée. Prochaines étapes : phase 4 (essais sur téléphone et en jeu, corrections) et phase 5 (livraison).
