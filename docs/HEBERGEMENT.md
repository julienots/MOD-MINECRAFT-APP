# Héberger un serveur Minecraft Bedrock (5 joueurs, avec tes mods)

L'onglet **Serveurs** de l'application crée et pilote un **vrai serveur Minecraft Bedrock** (le serveur officiel
de Mojang, « Bedrock Dedicated Server ») pour toi et jusqu'à 4 amis, avec tous les mods de tes projets.

## Ce qu'il faut savoir d'abord

**Supabase ne peut pas faire tourner le jeu lui-même.** Ses fonctions (Edge Functions) s'arrêtent au bout de quelques
secondes, n'ont pas de disque permanent et n'ouvrent pas de port UDP : un serveur Minecraft y est impossible.
L'hébergement est donc fait de deux morceaux, tous deux gratuits :

| Morceau | Rôle | Gratuit ? |
|---|---|---|
| **Supabase** (ton propre projet) | Tableau de bord : comptes, liste des serveurs, mods à installer, commandes, console, état en direct | Oui (offre gratuite) |
| **Une machine allumée en permanence** | Fait tourner le serveur Minecraft, piloté par un petit programme (« l'agent ») | Oui : Oracle Cloud « Always Free », ou un vieux PC sous Linux |

```
 Téléphone (application)  ──►  Supabase (tables + stockage des mods)  ◄──  Agent sur la machine  ──►  Serveur Bedrock
        ▲                                                                                                 ▲
        └──────────────────── les joueurs se connectent directement à la machine (UDP 19132) ─────────────┘
```

### Pourquoi c'est plus puissant qu'Aternos

- **Oracle Cloud Always Free (Ampere A1)** : jusqu'à **4 cœurs et 24 Go de mémoire**, allumé 24 h/24, sans file d'attente
  ni publicité, sans arrêt automatique quand personne ne joue.
- Le serveur Bedrock officiel n'existe qu'en x86-64 ; sur ces processeurs ARM il passe par **box64** (traduction
  à la volée), qui coûte un peu de performance mais laisse une grosse marge pour 5 joueurs.
- Un PC x86-64 (même ancien, 4 Go de mémoire) fait tourner le serveur sans traduction.

> Honnêtement : je n'ai pas pu mesurer les performances sur une vraie instance Oracle depuis l'environnement de
> développement. Le fonctionnement de l'agent est testé (voir plus bas), pas la fluidité en jeu.

### Ce qui est garanti, ce qui ne l'est pas

- ✅ 5 joueurs maximum (vérifié par la base de données et par l'agent).
- ✅ Les mods sont vérifiés par l'application avant l'envoi (un projet avec des erreurs bloquantes est refusé).
- ✅ Les joueurs téléchargent automatiquement les textures des mods en se connectant.
- ✅ Relance automatique en cas de plantage, arrêt des relances si les plantages s'enchaînent (un mod est alors souvent en cause : regarde la console).
- ⚠️ Deux mods qui modifient la même chose peuvent entrer en conflit : l'ordre de la liste décide (le premier gagne). Ajoute les mods un par un et teste.
- ⚠️ Les mods qui utilisent des scripts « bêta » demandent l'option **API bêta** (réglages du serveur).
- ⚠️ Consoles (Xbox, PlayStation, Switch) : elles n'ont pas de bouton « Ajouter un serveur » ; il faut une astuce
  extérieure (par ex. BedrockConnect). Téléphones, tablettes et PC Windows se connectent normalement.

## Étape 1 — Projet Supabase (5 minutes)

1. Crée un compte sur [supabase.com](https://supabase.com) puis **New project** (offre gratuite, région proche de toi).
2. Ouvre **SQL Editor → New query**, colle tout le contenu de
   [`server/supabase/migrations/20261115000000_bms_servers.sql`](../server/supabase/migrations/20261115000000_bms_servers.sql)
   et clique **Run**. Le script crée les tables, les règles de sécurité et le stockage privé des mods.
3. **Authentication → Sign In / Providers → Email** : laisse « Email » activé. Pour aller plus vite, tu peux désactiver
   « Confirm email » (sinon il faudra cliquer le lien reçu par e-mail après la création du compte).
4. **Project Settings → API** : copie l'**URL du projet** et la clé **anon (public)**.
   ⚠️ Ne copie jamais la clé `service_role` dans l'application.
5. Dans l'application : onglet **Serveurs**, colle l'URL et la clé anon, puis crée ton compte.

## Étape 2 — La machine

### Option A : Oracle Cloud Always Free (recommandé)

1. Crée un compte sur [oracle.com/cloud/free](https://www.oracle.com/cloud/free/) (une carte bancaire est demandée
   pour vérifier l'identité ; les ressources « Always Free » ne sont pas facturées).
2. **Compute → Instances → Create instance** :
   - Image : **Ubuntu 24.04** (ou 22.04) ;
   - Shape : **Ampere → VM.Standard.A1.Flex**, **4 OCPU** et **24 Go** de mémoire ;
   - ajoute ta clé SSH (ou télécharge celle proposée).
   Si Oracle répond « Out of capacity », réessaie plus tard ou dans une autre zone de disponibilité.
3. **Networking → Virtual cloud networks → ton réseau → Security Lists → Default** → **Add Ingress Rules** :
   source `0.0.0.0/0`, protocole **UDP**, port de destination **19132** (ajoute aussi **19133** pour l'IPv6).
4. Connecte-toi : `ssh ubuntu@<adresse IP publique>`.

### Option B : un PC sous Ubuntu / Debian chez toi

Installe Ubuntu, puis redirige le port **UDP 19132** de ta box Internet vers ce PC (menu « NAT / redirection de ports »).

## Étape 3 — Installer l'agent (une commande)

Dans l'application : **Serveurs → Nouveau serveur**, puis dans la fiche du serveur, section **Installation**, touche
**Copier la commande**. Colle-la dans le terminal SSH de la machine. Elle ressemble à :

```bash
curl -fsSL https://raw.githubusercontent.com/julienots/MOD-MINECRAFT-APP/HEAD/server/agent/install.sh \
  | sudo bash -s -- --url https://xxxx.supabase.co --anon <clé anon> --server <id> --key <clé secrète du serveur>
```

Le script ([`server/agent/install.sh`](../server/agent/install.sh)) :

1. installe Python 3 et les bibliothèques du serveur Bedrock ;
2. sur ARM, compile et installe **box64** (5 à 10 minutes la première fois) ;
3. te demande d'accepter le **CLUF de Minecraft** et la politique de confidentialité de Microsoft
   (le serveur est téléchargé directement depuis Mojang) ;
4. ouvre le port UDP dans le pare-feu de la machine ;
5. installe l'agent comme service (`bms-agent`) qui démarre tout seul avec la machine.

Après une minute, le serveur apparaît « Arrêté » dans l'application : touche **Démarrer**.
Le premier démarrage télécharge le serveur Bedrock (environ 100 Mo).

## Étape 4 — Ajouter des mods et jouer

- **Ajouter un projet** : choisis un projet de l'application ; il est vérifié, empaqueté (behavior + resource pack)
  et envoyé dans ton stockage Supabase privé. Touche ensuite **Appliquer les mods** (le serveur redémarre).
- Pour combiner des mods existants (.mcaddon téléchargés), importe-les dans un projet : **Contenu du mod → Ajouter un mod**.
- Dans Minecraft : **Jouer → Serveurs → Ajouter un serveur**, adresse et port affichés dans l'application.
- Console : `op TonPseudo`, `say Bonjour`, `time set day`, `gamerule keepinventory true`…
- **Sauvegarder** crée une copie du monde sur la machine (`/opt/bms/backups`, les 5 plus récentes), sans arrêter le jeu
  (méthode officielle `save hold` / `save query` / `save resume`).

## Dépannage

| Problème | Solution |
|---|---|
| « Machine hors ligne » | `sudo systemctl status bms-agent` puis `sudo journalctl -u bms-agent -f` sur la machine |
| Le jeu ne trouve pas le serveur | Port UDP 19132 ouvert dans la Security List d'Oracle (ou la box) ? |
| « Plantages répétés » | Regarde la console : désactive le dernier mod ajouté et réessaie |
| Un mod à scripts ne marche pas | Active **API bêta** dans les réglages du serveur |
| « tables introuvables » | Le script SQL n'a pas été exécuté dans Supabase (étape 1.2) |
| Le projet Supabase est en pause | L'offre gratuite met en pause un projet inactif depuis une semaine ; tant que l'agent tourne, il reste actif |

## Sécurité

- Chaque utilisateur ne voit et ne pilote que ses serveurs (règles RLS de PostgreSQL).
- L'agent n'a pas de compte ni la clé `service_role` : il s'identifie avec une **clé propre au serveur** dont seule
  l'empreinte SHA-256 est stockée dans Supabase ; il ne peut appeler que 4 fonctions (`bms_agent_*`).
- Les mods sont dans un stockage **privé** ; l'agent les télécharge avec des liens signés propres à chaque fichier.
- Sur le téléphone, la session et les clés des serveurs sont chiffrées par le Keystore Android.
- L'état affiché (en ligne, joueurs…) ne peut pas être falsifié depuis l'application : seul l'agent l'écrit.

## Ce qui a été testé

- Script SQL exécuté sur un vrai PostgreSQL 16 avec une imitation des schémas `auth` et `storage` de Supabase :
  isolation entre utilisateurs, refus d'une mauvaise clé d'agent, protection des colonnes d'état, limite à 5 joueurs,
  remise des commandes, journal limité à 1000 lignes.
- Agent exécuté de bout en bout contre cette base, avec un faux serveur Bedrock : téléchargement et installation,
  `server.properties`, installation d'un pack et `world_behavior_packs.json`, console, joueurs, commande,
  sauvegarde (fichiers tronqués aux tailles annoncées), arrêt ; activation des API bêta dans `level.dat` (NBT).
- **Non testé** : un vrai serveur Bedrock, box64 sur une vraie machine ARM, un vrai projet Supabase et l'application
  sur un vrai téléphone. Le premier essai réel dira s'il reste des réglages à ajuster.
