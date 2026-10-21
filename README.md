# Bedrock Mod Studio

Application Android pour créer des addons **Minecraft Bedrock Edition** depuis un téléphone, sans écrire de code.

> État : **phase 3 bien avancée** (socle, assistants, éditeurs de textures, 3D, animations, comportements, dimensions et boss livrés). Voir [l'audit et le plan](docs/PHASE1_AUDIT_ET_PLAN.md) et le [guide d'utilisation](docs/GUIDE_UTILISATEUR.md).

## Ce que l'application fait déjà

| Fonction | État |
|---|---|
| Créer un projet (behavior pack + resource pack liés, UUID, icônes, traductions) | ✅ |
| Choisir la version de Minecraft ciblée (1.26.30, 1.26.40, 1.26.50) | ✅ |
| Assistant « Créer une créature » (comportement, vie, vitesse, attaque, taille, couleurs, butin, expérience, apparition naturelle) avec modèle 3D, texture et animations générés | ✅ |
| Assistants « Créer un objet » (objet, nourriture, épée, pioche, hache, pelle, casque, plastron, jambières, bottes, avec recette optionnelle), « Créer un bloc » et « Créer une recette » (établi avec/sans forme, four) | ✅ |
| Explorateur de fichiers : ouvrir, créer, renommer, dupliquer, importer un fichier, supprimer (vers une corbeille, avec confirmation) | ✅ |
| Éditeur de texte/JSON : coloration, numéros de ligne, erreurs localisées (ligne/colonne), mise en forme, annuler/rétablir | ✅ |
| Historique des versions de chaque fichier, restauration | ✅ |
| Récupération des modifications après une fermeture inattendue | ✅ |
| Recherche d'un identifiant ou d'un texte dans tout le projet | ✅ |
| Vérification : JSON, manifestes, UUID, dépendances, identifiants en double, textures/modèles/animations manquants, fautes de frappe dans les composants, recettes vers des objets inexistants | ✅ |
| Export `.mcaddon`, `.mcpack` (behavior ou resource) et sauvegarde `.zip` | ✅ |
| Enregistrer l'export, le partager, l'ouvrir avec Minecraft | ✅ |
| Import `.mcaddon`, `.mcpack`, `.zip` avec rapport détaillé ; restauration des sauvegardes | ✅ |
| Projet d'exemple complet | ✅ |
| Éditeur de textures pixel art (crayon, gomme, remplissage, pipette, symétrie, zoom à deux doigts, redimensionnement) | ✅ |
| Éditeur de modèles 3D (os, cubes, pivots, rotations, UV, miroir, duplication, caméra orbitale tactile, éclairage, export OBJ) | ✅ |
| Éditeur d'animations (timeline, images clés, boucle, modèles marche/attaque/apparition/mort/transformation) | ✅ |
| Boss multi-phases (barre de boss, seuils de vie, immunités, butin rare, messages) | ✅ |
| Dimensions personnalisées (API officielle ≥ 1.26.30) avec portail, clé et commande | ✅ |
| Éditeur de comportements par blocs (quand / si / alors → script vérifié) | ✅ |
| Scripts : activation automatique, point d'entrée géré, vérification des imports, coloration | ✅ |
| Structures (.mcstructure), bibliothèque de composants, assistant en langage naturel | ⏳ à venir |

**Important :** l'application vérifie la *structure* des fichiers. Aucun addon n'a encore été testé dans Minecraft par le développement : seul un essai en jeu (sur ton téléphone) prouve qu'un addon fonctionne. Le guide explique comment faire cet essai et lire les erreurs de Minecraft.

## Récupérer l'APK

### Méthode 1 : GitHub Actions (recommandée)

À chaque envoi de code, GitHub compile l'application et lance tous les tests.

1. Ouvre le dépôt sur GitHub, onglet **Actions**.
2. Clique sur le dernier passage **« Android — tests et APK »** marqué d'une coche verte.
3. En bas de la page, section **Artifacts**, télécharge **BedrockModStudio-APK** (un fichier `.zip`).
4. Décompresse-le : il contient `BedrockModStudio-xxxxxxx.apk` et l'addon d'exemple.

### Méthode 2 : compiler soi-même (ordinateur)

Prérequis : JDK 17 ou plus récent, SDK Android (plateforme 35).

```bash
./gradlew :app:assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

## Installer sur le téléphone

1. Copie le fichier `.apk` sur le téléphone (ou télécharge-le directement depuis le téléphone).
2. Ouvre-le. Android demande d'autoriser l'installation depuis cette source : accepte pour l'application utilisée (Fichiers, Chrome…).
3. Appuie sur **Installer**.

Android 8.0 ou plus récent est nécessaire. Les nouvelles versions s'installent par-dessus l'ancienne sans perdre les projets (même signature).

## Tests

```bash
./gradlew :core:test              # 59 tests : JSON, projets, validation, export, import, sécurité des archives
./gradlew :app:testDebugUnitTest  # 10 parcours d'interface simulés (Robolectric)
./gradlew :app:lintDebug          # analyse Android Lint
```

Les tests d'interface simulent Android sur l'ordinateur ; ils ne remplacent pas un essai sur un vrai téléphone.
Les scripts générés (dimensions, boss, comportements) sont en plus vérifiés par TypeScript contre les définitions
officielles `@minecraft/server` 2.8.0, 2.9.0 et 2.10.0 (étape « scripts » de GitHub Actions).

## Organisation du code

```
core/   Kotlin pur, sans Android (testable partout)
  json/        parseur JSON tolérant aux commentaires, comme Minecraft
  bedrock/     profils de versions vérifiés, manifestes, générateur de créature, langues
  storage/     projets, historique, corbeille, brouillons, journal d'erreurs
  validation/  centre de vérification
  packaging/   export .mcaddon/.mcpack/.zip, import sécurisé
  image/       encodeur PNG (textures générées)
app/    Application Android (Jetpack Compose, Material 3, thème sombre)
exemples/  Addon d'exemple généré par l'application
docs/   Plan, guide d'utilisation
```

## Licence et contenus

Toutes les textures, modèles et icônes générés sont originaux. Les échantillons officiels de Mojang ont servi uniquement de référence de format ; aucun de leurs fichiers n'est inclus.

Ce projet n'est ni affilié ni approuvé par Mojang ou Microsoft. « Minecraft » est une marque de Mojang Synergies AB.
