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

## 3 bis. Objets, armes, armures, blocs et recettes

Dans le projet :

- **Créer un objet** : choisis le type (objet simple, nourriture, épée, pioche, hache, pelle, casque, plastron, jambières, bottes), le nom, les couleurs (l'icône se dessine en direct), puis les réglages propres au type : dégâts, durabilité, niveau, protection, nourriture… Pour les équipements, l'option **Ajouter la recette de fabrication** crée la recette vanilla habituelle avec le matériau de ton choix. Les armures sont visibles sur le personnage quand on les porte.
- **Créer un bloc** : style de texture (pierre, minerai, planches, briques, verre, lampe), couleurs, temps pour le casser, résistance aux explosions, lumière (0 à 15), son, et objet laissé en cassant.
- **Créer une recette** : *établi avec forme* (touche les cases de la grille 3×3), *établi sans forme* ou *four* (avec l'option fumoir ou haut fourneau). Les objets de ton projet sont proposés en premier.

Les fichiers partagés (`item_texture.json`, `terrain_texture.json`, `blocks.json`) sont complétés sans effacer ce qui y était déjà. Si l'un d'eux contient une erreur JSON, rien n'est généré tant qu'elle n'est pas corrigée.

Pour tester en jeu : `/give @s monmod:mon_objet`.

## 3 ter. Boss, dimensions, comportements

- **Boss** : dans « Créer une créature », active « C'est un boss ». Tu obtiens une barre de vie, des immunités, un butin rare et des **phases** : chacune commence sous un seuil de vie et change les dégâts et la vitesse, avec un message facultatif.
- **Créer une dimension** : une vraie nouvelle dimension (Minecraft 1.26.30 ou plus). Choisis le terrain (plateforme, îles flottantes, plaine), les blocs, et les accès : bloc portail (monte dessus), clé (clic droit) ou commande. Après l'ajout, **quitte et recharge le monde**. Ciel et brouillard propres à la dimension ne sont pas encore proposés.
- **Comportements (blocs)** : des règles « QUAND … SI … ALORS … » sans code. Exemples prêts à l'emploi : message de bienvenue, bâton de vitesse, minerai chanceux, commande de soin. « Enregistrer et compiler » crée le script ; une règle incomplète est signalée et rien n'est écrit.

## 3 quater. Textures, modèles 3D et animations

- **Textures** (tuile du tableau de bord) : galerie de toutes les images du projet, rangées par type (créatures, objets, blocs, particules). Touche-en une pour la dessiner au doigt : crayon, gomme, remplissage, pipette, symétrie, grille, taille. « Nouvelle texture » crée une image vierge (16 × 16 pour objets et blocs, 64 × 64 pour les créatures).
- **Modèles 3D et animations** (tuile du tableau de bord) :
  - Donne un nom, choisis **Créature** (cube simple, humanoïde, quadrupède, gelée) ou **Bloc 3D** (piédestal, lanterne, table, champignon), deux couleurs, puis « Créer le modèle et sa texture ». La texture est générée et rangée automatiquement.
  - Dans l'éditeur : un doigt fait tourner la vue, deux doigts zooment, un toucher sélectionne un cube. Les onglets sont :
    - *Structure* : os, cubes, dupliquer, miroir.
    - *Propriétés* : position, taille, pivot, rotation, UV.
    - *Texture* : choix de l'image, « Modifier dans l'éditeur de textures ». Au retour, la vue 3D montre la nouvelle texture.
    - *Animation* : modèles de départ, lecture, images clés.
  - **Utiliser ce modèle** (onglet Texture, après avoir enregistré) : touche une créature ou un bloc du projet pour lui donner cette forme et cette texture. L'application demande confirmation avant de modifier son fichier.
  - Un bloc 3D doit rester dans le volume d'un bloc ; sinon, l'application refuse et explique pourquoi. Ses boîtes de collision et de sélection suivent la forme.
- L'aperçu est une approximation : le rendu exact se vérifie dans Minecraft. Les animations écrites en Molang sont conservées mais ne s'animent pas dans l'aperçu.
- Pas encore possible : un objet tenu en main en 3D (il resterait plat), et peindre directement sur le modèle en 3D (la texture se dessine à plat).

## 3 quinquies. Structures

- Tuile **Structures** : donne un nom, choisis un modèle de départ (vide, maison, tour, salle au trésor, arche) et touche « Créer la structure ».
- Onglet **Couches** : chaque couche est vue de dessus (nord en haut). Choisis un bloc puis touche les cases. « Vide » garde le terrain du monde, « Air » le creuse. « Coffre à butin » pose un coffre. Les boutons + / − changent de couche ; la taille va jusqu'à 64 × 256 × 64.
- Onglet **Aperçu 3D** : un doigt tourne, deux doigts zooment ; touche un bloc pour voir son nom.
- Onglet **Butin et apparition** : contenu des coffres (trésor, provisions, aventurier) et apparition naturelle (biomes, rareté, enterrée ou non).
- Touche l'icône Enregistrer pour écrire le fichier ; l'ancienne version reste dans l'historique.
- Pour tester : « /structure load <identifiant> ~ ~ ~ » dans un monde avec commandes, ou l'action « Placer une structure » de l'éditeur de comportements. L'apparition naturelle ne touche que les **nouveaux** tronçons du monde.
- Limites : les blocs sont posés avec leurs états par défaut (orientation…). Pour des blocs orientés précisément, construis dans Minecraft, enregistre avec un bloc de structure, et importe le fichier. Le lien coffre ↔ table de butin n'a pas encore été vérifié en jeu.

## 3 sexies. Bibliothèque de composants

Tuile **Bibliothèque de composants** : recherche une propriété (« feu », « vitesse », « lumière »…), touche-la pour voir son code, puis touche la créature, l'objet ou le bloc auquel l'ajouter. Si le composant existe déjà, l'application demande avant de le remplacer ; l'ancienne version reste dans l'historique. Les noms sont vérifiés contre les fichiers officiels ; les valeurs sont des exemples à ajuster. Les commentaires du fichier JSON sont retirés à l'ajout.

## 3 septies. Particules

- Tuile **Particules** : donne un nom, choisis un modèle (étincelles, fumée, magie, petits cœurs, pluie) et touche « Créer l'effet ». La texture (un point blanc adouci) est créée par l'application.
- Touche l'effet pour l'ouvrir : l'**aperçu animé** se met à jour à chaque réglage (quantité, salve, boucle, forme, direction, vitesse, durée de vie, gravité, freinage, taille, couleurs). Icône Enregistrer pour écrire le fichier.
- L'aperçu est une approximation : lumière et aspect exact se vérifient dans Minecraft avec « /particle <identifiant> ~ ~1 ~ » ou l'action « Afficher des particules » de l'éditeur de comportements.
- Un fichier qui utilise des réglages que l'éditeur ne sait pas reprendre (expressions Molang, autres composants) s'ouvre en **aperçu seul** : il reste modifiable dans l'éditeur de texte, et l'application ne le réécrit jamais à ta place.

## 3 octies. Assistant (décris ton idée)

- Tuile **Assistant** : écris ta demande en français, par exemple « un zombie de feu rouge hostile avec 40 points de vie, une épée de glace bleue et une tour », puis « Analyser (hors ligne) ».
- L'assistant propose un **plan** : une ligne par élément (créature, objet, bloc, particules, structure, dimension). Décoche ce que tu ne veux pas, puis « Créer ». Rien n'est créé avant, et un fichier existant n'est jamais remplacé sans confirmation.
- L'analyse hors ligne repère des mots-clés (types, couleurs, « hostile », « boss », « 40 points de vie », « lumineux »…). Pour une demande plus libre, l'**IA facultative** peut aider :
  - elle est désactivée tant que tu n'as pas ajouté ta propre clé d'API Anthropic (Claude) ;
  - la clé est chiffrée sur le téléphone ;
  - seul le texte de ta demande est envoyé, et l'utilisation est facturée sur ton compte Anthropic ;
  - la réponse ne peut contenir que des éléments du plan, que l'application vérifie ;
  - aucun code n'est exécuté ;
  - demande l'accord d'un adulte avant d'ajouter une clé.
- Les mods Java (Forge, Fabric) ne sont pas possibles : l'assistant le dit au lieu d'inventer.

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

- Les fichiers son se conservent et s'exportent, mais ne se modifient pas dans l'application.
