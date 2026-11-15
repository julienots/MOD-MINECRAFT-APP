#!/usr/bin/env bash
# Installation de l'agent Bedrock Mod Studio sur une machine Linux (Ubuntu / Debian).
#
# Usage (la commande exacte, avec tes identifiants, est affichée dans l'application → Serveurs → ton serveur) :
#   curl -fsSL <url-de-ce-script> | sudo bash -s -- --url https://xxxx.supabase.co --anon <clé anon> \
#        --server <id du serveur> --key <clé de l'agent>
#
# Ce que fait ce script :
#   1. installe Python 3, unzip, curl et les bibliothèques demandées par le serveur Bedrock ;
#   2. sur processeur ARM (Oracle Cloud Ampere, Raspberry Pi…), compile box64 pour exécuter le serveur Bedrock (x86-64) ;
#   3. te demande d'accepter le CLUF de Minecraft et la politique de confidentialité de Microsoft ;
#   4. ouvre le port UDP du jeu dans le pare-feu local ;
#   5. installe l'agent comme service système (démarrage automatique, relance en cas d'arrêt).
set -euo pipefail

AGENT_URL="${BMS_AGENT_URL:-https://raw.githubusercontent.com/julienots/MOD-MINECRAFT-APP/HEAD/server/agent/bms_agent.py}"
BASE=/opt/bms
URL="" ANON="" SERVER="" KEY="" PORT=19132 ACCEPT_EULA="${BMS_ACCEPT_EULA:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --url) URL="$2"; shift 2 ;;
    --anon) ANON="$2"; shift 2 ;;
    --server) SERVER="$2"; shift 2 ;;
    --key) KEY="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --accept-eula) ACCEPT_EULA=yes; shift ;;
    *) echo "Option inconnue : $1"; exit 1 ;;
  esac
done

if [ "$(id -u)" -ne 0 ]; then echo "Lance ce script avec sudo."; exit 1; fi
if [ -z "$URL" ] || [ -z "$ANON" ] || [ -z "$SERVER" ] || [ -z "$KEY" ]; then
  echo "Paramètres manquants : --url, --anon, --server et --key sont obligatoires (copie la commande depuis l'application)."; exit 1
fi
if ! command -v apt-get >/dev/null; then echo "Ce script prend en charge Ubuntu et Debian (apt)."; exit 1; fi

echo "== 1/5 Paquets"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q python3 unzip curl ca-certificates libcurl4 openssl iptables

ARCH="$(uname -m)"
if [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then
  echo "== 2/5 Processeur ARM : installation de box64 (quelques minutes)"
  if ! command -v box64 >/dev/null; then
    apt-get install -y -q git cmake build-essential python3
    rm -rf /tmp/box64-src && git clone --depth 1 https://github.com/ptitSeb/box64 /tmp/box64-src
    mkdir -p /tmp/box64-src/build && cd /tmp/box64-src/build
    cmake .. -DARM_DYNAREC=ON -DCMAKE_BUILD_TYPE=RelWithDebInfo >/dev/null
    make -j"$(nproc)" >/dev/null
    make install >/dev/null
    systemctl restart systemd-binfmt 2>/dev/null || true
    cd / && rm -rf /tmp/box64-src
  fi
  box64 --version || true
elif [ "$ARCH" = "x86_64" ]; then
  echo "== 2/5 Processeur x86-64 : rien à ajouter"
else
  echo "Processeur $ARCH non pris en charge (il faut x86-64 ou ARM 64 bits)."; exit 1
fi

echo "== 3/5 Conditions d'utilisation de Minecraft"
echo "Le serveur Bedrock (Bedrock Dedicated Server) est téléchargé directement depuis Mojang."
echo "En continuant, tu acceptes le CLUF de Minecraft (https://www.minecraft.net/eula)"
echo "et la politique de confidentialité de Microsoft (https://go.microsoft.com/fwlink/?LinkId=521839)."
if [ "$ACCEPT_EULA" != "yes" ]; then
  if [ -r /dev/tty ]; then
    read -r -p "Tu acceptes ? (oui/non) " answer < /dev/tty
  else
    answer=""
  fi
  case "$answer" in oui|o|yes|y|OUI|Oui) ;; *) echo "Installation annulée (conditions refusées ou pas de terminal : ajoute --accept-eula)."; exit 1 ;; esac
fi

echo "== 4/5 Agent et configuration"
id bms >/dev/null 2>&1 || useradd --system --home-dir "$BASE" --shell /usr/sbin/nologin bms
mkdir -p "$BASE"
curl -fsSL "$AGENT_URL" -o "$BASE/bms_agent.py"
python3 -m py_compile "$BASE/bms_agent.py"
umask 077
cat > "$BASE/agent.json" <<EOF
{
  "supabase_url": "$URL",
  "anon_key": "$ANON",
  "server_id": "$SERVER",
  "agent_key": "$KEY",
  "eula_accepted": true,
  "base_dir": "$BASE"
}
EOF
chown -R bms:bms "$BASE"

# Pare-feu local (les images Ubuntu d'Oracle Cloud bloquent tout par défaut, sauf SSH).
iptables -C INPUT -p udp --dport "$PORT" -j ACCEPT 2>/dev/null || iptables -I INPUT -p udp --dport "$PORT" -j ACCEPT
iptables -C INPUT -p udp --dport "$((PORT + 1))" -j ACCEPT 2>/dev/null || iptables -I INPUT -p udp --dport "$((PORT + 1))" -j ACCEPT
if command -v netfilter-persistent >/dev/null; then netfilter-persistent save >/dev/null 2>&1 || true; fi
if command -v ufw >/dev/null && ufw status | grep -q active; then ufw allow "$PORT/udp" >/dev/null; fi

echo "== 5/5 Service système"
cat > /etc/systemd/system/bms-agent.service <<EOF
[Unit]
Description=Agent Bedrock Mod Studio (serveur Minecraft Bedrock)
After=network-online.target
Wants=network-online.target

[Service]
User=bms
WorkingDirectory=$BASE
Environment=BMS_CONFIG=$BASE/agent.json
ExecStart=/usr/bin/python3 $BASE/bms_agent.py
Restart=always
RestartSec=5
TimeoutStopSec=60
# Priorité au jeu par rapport aux autres programmes de la machine
Nice=-5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now bms-agent
sleep 3
systemctl --no-pager --lines=5 status bms-agent || true

echo
echo "Terminé ! Retourne dans l'application : le serveur doit apparaître « Arrêté » (prêt). Touche « Démarrer »."
echo "Oracle Cloud : n'oublie pas d'ouvrir le port UDP $PORT dans la « Security List » du réseau (voir le guide)."
echo "Journal de l'agent : sudo journalctl -u bms-agent -f"
