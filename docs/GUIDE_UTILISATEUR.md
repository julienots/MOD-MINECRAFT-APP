# Guide d'utilisation — Bedrock Mod Studio

Ce guide est écrit pour quelqu'un qui ne sait pas programmer.

## 1. Premiers pas

L'écran d'accueil propose trois actions :

- **Créer un mod** : un nouveau projet vide.
- **Importer un addon** : ouvre un `.mcaddon`, un `.mcpack` ou une sauvegarde `.zip` pour le modifier.
- **Ouvrir le projet d'exemple** : un addon complet avec une créature, pour voir à quoi tout ressemble.

En bas de l'écran : **Accueil**, **Projets** (tous tes projets) et **Paramètres**.

## 2. Créer un mod

1. Appuie sur **Créer un mod**.
2. Donne un **nom** (affiché dans Minecraft) et, si tu veux, une description et ton nom d'auteur.
3. L'**espace de noms** se remplit tout seul. C'est le préfixe de tous tes identifiants (`monmod:dragon`). Il ne peut plus changer ensuite.
4. Choisis la **version de Minecraft** : garde la plus récente sauf si tes joueurs ont une version plus ancienne.
5. Appuie sur **Créer**.

Le projet contient déjà un *behavior pack* (les comportements) et un *resource pack* (l'apparence), reliés entre eux.

## 3. Ajouter une créature

Dans le projet, appuie sur **Créer une créature** :

| Réglage | Ce que ça change |
|---|---|
| Nom | Le nom affiché en jeu. |
| Comportement | **Passif** (fuit), **Neutre** (se défend), **Hostile** (attaque le joueur). |
| Santé | Points de vie. 2 points = 1 cœur. Un zombie en a 20. |
| Vitesse | 0,25 = un zombie. |
| Dégâts | Par coup. Un zombie fait 3. |
| Distance de détection | Pour un mob hostile : jusqu'où il te repère. |
| Taille | Agrandit ou rétrécit le modèle. |
| Couleurs | Couleurs de la texture et de l'œuf d'apparition. L'aperçu montre la texture générée. |
| Butin | Objet laissé à la mort, et quantité. |
| Apparition naturelle | La créature apparaît seule dans le monde (la nuit si elle est hostile). |

Appuie sur **Générer la créature**. L'application affiche la liste des fichiers créés. Si une créature du même identifiant existe déjà, elle demande avant de remplacer ; les anciennes versions restent dans l'historique.

## 4. Fichiers et éditeur

**Fichiers** montre l'arborescence du projet. Appuie sur un dossier pour l'ouvrir, sur un fichier pour l'éditer. Le bouton **⋮** d'une ligne propose : Renommer, Dupliquer, Historique, Supprimer.

- **Supprimer** demande toujours une confirmation et déplace l'élément dans la **corbeille du projet** (icône poubelle en haut) : tu peux le restaurer.
- Le bouton **+** crée un fichier, un dossier, ou importe un fichier du téléphone (une image PNG, par exemple).

Dans l'éditeur :

- La barre du haut indique **JSON valide** ou l'erreur avec sa ligne et sa colonne. Appuie dessus pour aller à l'erreur.
- Boutons : annuler, rétablir, mettre en forme, enregistrer, historique.
- Tes modifications sont gardées en brouillon toutes les 1,5 seconde. Si l'application se ferme brutalement, elle te propose de les récupérer.

## 5. Vérifier

**Vérifier** analyse tout le projet :

- 🔴 **Erreur** : l'export est bloqué tant qu'elle n'est pas corrigée (JSON cassé, UUID en double, texture introuvable…).
- 🟠 **Avertissement** : à regarder, mais l'export reste possible. Par exemple, une référence vers une ressource qui existe peut-être dans Minecraft lui-même : l'application ne peut pas le vérifier et te le dit.
- 🔵 **Information**.

Appuie sur un problème pour ouvrir le fichier concerné à la bonne ligne.

## 6. Exporter et jouer

1. Appuie sur **Exporter** puis **Addon complet (.mcaddon)**.
2. L'application vérifie le projet, augmente la version (pour que Minecraft accepte la mise à jour), construit l'archive puis la relit pour s'assurer qu'elle est correcte.
3. Ensuite :
   - **Ouvrir avec Minecraft** : Minecraft importe l'addon directement.
   - **Enregistrer dans…** : choisis un dossier du téléphone (Téléchargements, par exemple).
   - **Partager** : envoie le fichier à une autre application.

Dans Minecraft :

1. Crée (ou modifie) un monde.
2. Dans **Packs de comportements**, active ton pack. Comme il dépend du pack de ressources, Minecraft propose normalement de l'activer aussi ; vérifie qu'il apparaît bien dans **Packs de ressources**, sinon active-le à la main.
3. Lance le monde. En créatif, l'œuf d'apparition de ta créature est dans l'inventaire (onglet Nature), ou tape `/summon monmod:nom`.

### Si quelque chose ne marche pas en jeu

Active le journal de Minecraft : **Paramètres → Créateur → Journal du contenu** (*Content Log*). Les erreurs de ton addon s'affichent alors à l'écran et dans **Paramètres → Créateur → Historique du journal du contenu**. Recopie le message, corrige le fichier dans l'application, puis exporte à nouveau.

## 7. Importer un addon existant

**Importer un addon** accepte `.mcaddon`, `.mcpack` et `.zip`. Le **rapport d'import** indique :

- les packs trouvés et leur rôle ;
- les ressources reconnues (entités, textures, modèles…) ;
- les fichiers modifiables (texte) et ceux conservés tels quels (images, sons…) ;
- les formats inconnus (conservés, jamais supprimés) ;
- les dépendances, notamment les packs externes non inclus ;
- les remarques (par exemple un contenu du Marketplace peut être chiffré).

Le fichier d'origine est toujours conservé intact à l'intérieur du projet.

Les mods **Java Edition** (`.jar`, Forge, Fabric, `pack.mcmeta`) sont refusés avec une explication : ils ne fonctionnent pas sur Bedrock.

## 8. Sauvegardes

- **Exporter → Sauvegarde du projet (.zip)** crée une copie complète.
- Pour la restaurer : **Importer un addon** et choisis ce `.zip`. Elle devient un nouveau projet « (restauré) », sans toucher à l'original.
- Un projet supprimé va dans la **corbeille des projets** (Paramètres), d'où tu peux le restaurer.

## 9. Ce qui n'est pas encore possible

- Éditeurs de modèles 3D, de textures, d'animations, de logique par blocs, de scripts et de structures : prévus dans la phase 3.
- Dimensions personnalisées : Minecraft les permet depuis la version 1.26.30 (par script, la dimension naît vide). Leur assistant arrive dans la phase 3.
- Les fichiers son et structure se conservent et s'exportent, mais ne se modifient pas encore dans l'application.
