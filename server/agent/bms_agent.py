#!/usr/bin/env python3
"""
Agent Bedrock Mod Studio — fait tourner un serveur Minecraft Bedrock (Bedrock Dedicated Server, BDS)
sur cette machine et le pilote depuis l'application, via Supabase.

- Aucune dépendance : Python 3.8+ (bibliothèque standard uniquement).
- Configuration : /opt/bms/agent.json (créé par install.sh).
- Sur processeur ARM (Oracle Cloud Ampere, Raspberry Pi 5…), BDS (x86-64 uniquement) est lancé
  via box64, installé par install.sh.

L'agent :
  * télécharge BDS depuis le site officiel de Mojang (après acceptation du CLUF par l'utilisateur) ;
  * écrit server.properties d'après les réglages choisis dans l'application ;
  * installe les packs (behavior / resource) envoyés depuis l'application, dans l'ordre choisi ;
  * démarre, arrête, redémarre, sauvegarde le monde, relaie la console et la liste des joueurs ;
  * redémarre automatiquement BDS en cas de plantage (avec garde-fou si les plantages s'enchaînent).
"""

import io
import json
import os
import platform
import queue
import re
import shutil
import signal
import struct
import subprocess
import sys
import tarfile
import threading
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

VERSION = "1.0.0"
CONFIG_PATH = Path(os.environ.get("BMS_CONFIG", "/opt/bms/agent.json"))
DOWNLOAD_API = "https://net-secondary.web.minecraft-services.net/api/v1.0/download/links"
HEARTBEAT_SECONDS = 3
KEEP_BACKUPS = 5
PRESERVED = {"server.properties", "allowlist.json", "permissions.json"}


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


# ------------------------------------------------------------------ Supabase (fonctions agent_* uniquement)

class Api:
    def __init__(self, cfg):
        self.url = cfg["supabase_url"].rstrip("/")
        self.anon = cfg["anon_key"]
        self.server = cfg["server_id"]
        self.key = cfg["agent_key"]

    def rpc(self, fn, **params):
        body = json.dumps({"p_server": self.server, "p_key": self.key, **params}).encode()
        req = urllib.request.Request(
            f"{self.url}/rest/v1/rpc/{fn}", data=body, method="POST",
            headers={"apikey": self.anon, "Authorization": f"Bearer {self.anon}", "Content-Type": "application/json",
                     "User-Agent": f"bms-agent/{VERSION}"})
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read()
            return json.loads(raw) if raw else None


def http_get(url, timeout=120):
    req = urllib.request.Request(url, headers={"User-Agent": f"bms-agent/{VERSION}"})
    return urllib.request.urlopen(req, timeout=timeout)


# ------------------------------------------------------------------ NBT petit-boutiste (level.dat de Bedrock)
# Utilisé uniquement pour activer l'expérience « API bêta » quand l'application le demande.

class Nbt:
    END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BARR, STR, LIST, COMP, IARR, LARR = range(13)

    def __init__(self, data):
        self.b = data
        self.i = 0

    def take(self, n):
        v = self.b[self.i:self.i + n]
        self.i += n
        return v

    def string(self):
        (n,) = struct.unpack("<H", self.take(2))
        return self.take(n).decode("utf-8", "replace")

    def payload(self, t):
        if t == self.BYTE: return struct.unpack("<b", self.take(1))[0]
        if t == self.SHORT: return struct.unpack("<h", self.take(2))[0]
        if t == self.INT: return struct.unpack("<i", self.take(4))[0]
        if t == self.LONG: return struct.unpack("<q", self.take(8))[0]
        if t == self.FLOAT: return struct.unpack("<f", self.take(4))[0]
        if t == self.DOUBLE: return struct.unpack("<d", self.take(8))[0]
        if t == self.BARR:
            (n,) = struct.unpack("<i", self.take(4)); return bytes(self.take(n))
        if t == self.STR: return self.string()
        if t == self.LIST:
            et = self.take(1)[0]; (n,) = struct.unpack("<i", self.take(4))
            return (et, [self.payload(et) for _ in range(n)])
        if t == self.COMP:
            out = []
            while True:
                tt = self.take(1)[0]
                if tt == self.END: return out
                name = self.string()
                out.append([tt, name, self.payload(tt)])
        if t == self.IARR:
            (n,) = struct.unpack("<i", self.take(4)); return list(struct.unpack(f"<{n}i", self.take(4 * n)))
        if t == self.LARR:
            (n,) = struct.unpack("<i", self.take(4)); return list(struct.unpack(f"<{n}q", self.take(8 * n)))
        raise ValueError(f"Type NBT inconnu {t}")

    @classmethod
    def write(cls, t, v, out):
        if t == cls.BYTE: out += struct.pack("<b", v)
        elif t == cls.SHORT: out += struct.pack("<h", v)
        elif t == cls.INT: out += struct.pack("<i", v)
        elif t == cls.LONG: out += struct.pack("<q", v)
        elif t == cls.FLOAT: out += struct.pack("<f", v)
        elif t == cls.DOUBLE: out += struct.pack("<d", v)
        elif t == cls.BARR: out += struct.pack("<i", len(v)) + v
        elif t == cls.STR:
            e = v.encode("utf-8"); out += struct.pack("<H", len(e)) + e
        elif t == cls.LIST:
            et, items = v; out += bytes([et]) + struct.pack("<i", len(items))
            for it in items: cls.write(et, it, out)
        elif t == cls.COMP:
            for tt, name, pv in v:
                out += bytes([tt]); cls.write(cls.STR, name, out); cls.write(tt, pv, out)
            out += bytes([cls.END])
        elif t == cls.IARR: out += struct.pack("<i", len(v)) + struct.pack(f"<{len(v)}i", *v)
        elif t == cls.LARR: out += struct.pack("<i", len(v)) + struct.pack(f"<{len(v)}q", *v)


def set_beta_apis(level_dat: Path, enabled: bool) -> bool:
    """Active/désactive l'expérience « API bêta » (clé « gametest ») dans level.dat. Renvoie vrai si modifié."""
    if not level_dat.is_file():
        return False
    raw = level_dat.read_bytes()
    storage_version, _length = struct.unpack("<ii", raw[:8])
    r = Nbt(raw[8:])
    root_type = r.take(1)[0]
    root_name = r.string()
    root = r.payload(root_type)
    exp = next((e for e in root if e[1] == "experiments" and e[0] == Nbt.COMP), None)
    if exp is None:
        if not enabled:
            return False
        exp = [Nbt.COMP, "experiments", []]
        root.append(exp)

    def put(name, value):
        for e in exp[2]:
            if e[1] == name:
                changed = e[2] != value
                e[0], e[2] = Nbt.BYTE, value
                return changed
        exp[2].append([Nbt.BYTE, name, value])
        return True

    changed = put("gametest", 1 if enabled else 0)
    if enabled:
        changed |= put("experiments_ever_used", 1)
        changed |= put("saved_with_toggled_experiments", 1)
    if not changed:
        return False
    body = bytearray([root_type])
    Nbt.write(Nbt.STR, root_name, body)
    Nbt.write(root_type, root, body)
    shutil.copy2(level_dat, level_dat.with_suffix(".dat.bak"))
    level_dat.write_bytes(struct.pack("<ii", storage_version, len(body)) + bytes(body))
    return True


# ------------------------------------------------------------------ Serveur Bedrock

class Bedrock:
    def __init__(self, base: Path):
        self.base = base
        self.dir = base / "bds"
        self.proc = None
        self.lines = queue.Queue()
        self.console_lines = []
        self.players = {}
        self.version = ""
        self.started = False
        self.waiters = []  # (motif, événement, liste résultat)
        self.lock = threading.Lock()

    @property
    def installed(self):
        return (self.dir / "bedrock_server").is_file()

    @property
    def running(self):
        return self.proc is not None and self.proc.poll() is None

    def command_prefix(self):
        if platform.machine().lower() in ("aarch64", "arm64", "armv8l"):
            box = shutil.which("box64")
            if not box:
                raise RuntimeError("Processeur ARM détecté mais box64 est absent : relance install.sh.")
            return [box]
        return []

    def install_or_update(self, url: str):
        log(f"Téléchargement de BDS : {url}")
        data = http_get(url, timeout=600).read()
        self.dir.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for info in z.infolist():
                name = info.filename
                if name.endswith("/") or ".." in Path(name).parts or name.startswith("/"):
                    continue
                target = self.dir / name
                if name in PRESERVED and target.exists():
                    continue  # on garde les réglages et listes de l'utilisateur
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(info) as src, open(target, "wb") as dst:
                    shutil.copyfileobj(src, dst)
        (self.dir / "bedrock_server").chmod(0o755)
        m = re.search(r"bedrock-server-([0-9.]+)\.zip", url)
        if m:
            self.version = m.group(1)
            (self.base / "bds_version.txt").write_text(self.version)

    def start(self):
        if self.running:
            return
        if not self.installed:
            raise RuntimeError("BDS n'est pas encore installé.")
        env = dict(os.environ, LD_LIBRARY_PATH=str(self.dir))
        self.started = False
        self.players.clear()
        self.proc = subprocess.Popen(
            self.command_prefix() + ["./bedrock_server"], cwd=self.dir, env=env,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
            errors="replace")
        threading.Thread(target=self._reader, args=(self.proc,), daemon=True).start()

    def _reader(self, proc):
        for raw in proc.stdout:
            line = raw.rstrip("\n")
            if not line:
                continue
            self.lines.put(line)
            m = re.search(r"Player connected: ([^,]+), xuid: (\d*)", line)
            if m:
                self.players[m.group(1)] = {"name": m.group(1), "xuid": m.group(2), "since": int(time.time())}
            m = re.search(r"Player disconnected: ([^,]+)", line)
            if m:
                self.players.pop(m.group(1), None)
            m = re.search(r"Version:? ([0-9][0-9.]+)", line)
            if m and not self.version:
                self.version = m.group(1)
            if "Server started" in line:
                self.started = True
            with self.lock:
                for pattern, ev, out in list(self.waiters):
                    if re.search(pattern, line):
                        out.append(line)
                        ev.set()

    def send(self, text):
        if not self.running:
            raise RuntimeError("Le serveur est arrêté.")
        self.proc.stdin.write(text.strip() + "\n")
        self.proc.stdin.flush()

    def send_and_wait(self, text, pattern, timeout=10.0):
        ev, out = threading.Event(), []
        with self.lock:
            self.waiters.append((pattern, ev, out))
        try:
            self.send(text)
            ev.wait(timeout)
            return out[0] if out else None
        finally:
            with self.lock:
                self.waiters = [w for w in self.waiters if w[1] is not ev]

    def stop(self, timeout=30):
        if not self.running:
            return
        try:
            self.send("stop")
            self.proc.wait(timeout=timeout)
        except Exception:
            self.proc.kill()
            self.proc.wait(timeout=10)
        self.players.clear()
        self.started = False


# ------------------------------------------------------------------ Agent

class Agent:
    def __init__(self, cfg):
        self.cfg = cfg
        self.api = Api(cfg)
        self.base = Path(cfg.get("base_dir", "/opt/bms"))
        self.bds = Bedrock(self.base)
        self.state_path = self.base / "state.json"
        self.state = json.loads(self.state_path.read_text()) if self.state_path.is_file() else {}
        self.server = {}
        self.config = {}
        self.status = "offline"
        self.message = ""
        self.want_running = self.state.get("want_running", False)
        self.crashes = []
        self.address = cfg.get("public_address", "")
        vf = self.base / "bds_version.txt"
        if vf.is_file():
            self.bds.version = vf.read_text().strip()

    def save_state(self):
        self.state["want_running"] = self.want_running
        self.state_path.write_text(json.dumps(self.state, indent=2))

    # ---- Réglages

    def world_dir(self):
        return self.bds.dir / "worlds" / self.server.get("level_name", "Monde")

    def write_properties(self):
        s = self.server
        props = {
            "server-name": s.get("name", "Serveur BMS"),
            "gamemode": s.get("gamemode", "survival"),
            "difficulty": s.get("difficulty", "normal"),
            "max-players": str(min(5, int(s.get("max_players", 5)))),
            "level-name": s.get("level_name", "Monde"),
            "level-seed": s.get("level_seed", ""),
            "view-distance": str(s.get("view_distance", 12)),
            "tick-distance": str(s.get("tick_distance", 4)),
            "allow-cheats": "true" if s.get("allow_cheats") else "false",
            "server-port": str(s.get("server_port", 19132)),
            "server-portv6": str(int(s.get("server_port", 19132)) + 1),
            "online-mode": "true",
            # Les joueurs téléchargent automatiquement les resource packs du serveur.
            "texturepack-required": "true",
            "content-log-file-enabled": "true",
            "content-log-console-output-enabled": "true",
            # Réglages pour 5 joueurs : plus de fils de calcul, compression réseau efficace.
            "max-threads": "0",
            "compression-threshold": "1",
            "player-idle-timeout": "0",
            "emit-server-telemetry": "false",
        }
        path = self.bds.dir / "server.properties"
        lines = path.read_text(errors="replace").splitlines() if path.is_file() else []
        seen = set()
        out = []
        for line in lines:
            k = line.split("=", 1)[0].strip()
            if not line.startswith("#") and "=" in line and k in props:
                out.append(f"{k}={props[k]}")
                seen.add(k)
            else:
                out.append(line)
        out += [f"{k}={v}" for k, v in props.items() if k not in seen]
        path.write_text("\n".join(out) + "\n")

    # ---- Packs

    def sync_packs(self, packs):
        """Installe exactement les packs demandés (dans l'ordre) et retire ceux qui ne le sont plus."""
        installed = self.state.get("packs", {})  # id pack app -> {uuid, version, kind, dir, size}
        wanted = {}
        world_bp, world_rp = [], []
        notes = []
        for p in packs:
            pid = p["id"]
            prev = installed.get(pid)
            if prev and prev.get("size") == p.get("size") and (self.bds.dir / prev["dir"]).is_dir():
                info = prev
            else:
                info = self.install_pack(p)
                notes.append(f"installé : {p['name']}")
            wanted[pid] = info
            entry = {"pack_id": info["uuid"], "version": info["version"]}
            (world_bp if info["kind"] == "behavior" else world_rp).append(entry)
        for pid, info in installed.items():
            if pid not in wanted:
                shutil.rmtree(self.bds.dir / info["dir"], ignore_errors=True)
                notes.append(f"retiré : {info.get('name', pid)}")
        world = self.world_dir()
        world.mkdir(parents=True, exist_ok=True)
        (world / "world_behavior_packs.json").write_text(json.dumps(world_bp, indent=2))
        (world / "world_resource_packs.json").write_text(json.dumps(world_rp, indent=2))
        self.state["packs"] = wanted
        self.save_state()
        return f"{len(world_bp)} behavior pack(s), {len(world_rp)} resource pack(s). " + "; ".join(notes)

    def install_pack(self, p):
        data = http_get(p["url"], timeout=300).read()
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            names = z.namelist()
            manifests = sorted([n for n in names if n.lower().endswith("manifest.json")], key=len)
            if not manifests:
                raise RuntimeError(f"{p['name']} : pas de manifest.json")
            root = manifests[0][: -len("manifest.json")]
            manifest = json.loads(z.read(manifests[0]).decode("utf-8-sig"))
            header = manifest["header"]
            types = {m.get("type") for m in manifest.get("modules", [])}
            kind = "resource" if "resources" in types else "behavior"
            uuid = header["uuid"]
            folder = ("resource_packs" if kind == "resource" else "behavior_packs") + f"/bms_{uuid}"
            target = self.bds.dir / folder
            shutil.rmtree(target, ignore_errors=True)
            for n in names:
                if not n.startswith(root) or n.endswith("/"):
                    continue
                rel = n[len(root):]
                if ".." in Path(rel).parts or rel.startswith("/"):
                    continue
                dest = target / rel
                dest.parent.mkdir(parents=True, exist_ok=True)
                with z.open(n) as src, open(dest, "wb") as dst:
                    shutil.copyfileobj(src, dst)
        return {"uuid": uuid, "version": header.get("version", [1, 0, 0]), "kind": kind, "dir": folder,
                "size": p.get("size"), "name": p["name"]}

    # ---- Sauvegardes (méthode officielle : save hold / save query / save resume)

    def backup(self):
        backups_dir = self.base / "backups"
        backups_dir.mkdir(parents=True, exist_ok=True)
        name = time.strftime("monde-%Y%m%d-%H%M%S.tar.gz")
        world = self.world_dir()
        if not world.is_dir():
            raise RuntimeError("Aucun monde à sauvegarder pour l'instant.")
        target = backups_dir / name
        if self.bds.running:
            self.bds.send("save hold")
            listing = None
            for _ in range(30):
                time.sleep(1)
                ready = self.bds.send_and_wait("save query", r"Files are now ready|ready to be copied", timeout=3)
                if ready:
                    # La ligne suivante liste « chemin:taille, chemin:taille… »
                    deadline = time.time() + 3
                    while time.time() < deadline:
                        try:
                            l = self.bds.lines.get(timeout=1)
                        except queue.Empty:
                            continue
                        self.forward_log(l)
                        # Liste officielle : « Monde/db/000005.ldb:1234, Monde/level.dat:0, … » (préfixe [INFO] toléré)
                        cand = re.sub(r"^\S*\s*\[[^\]]*\]\s*", "", l).strip()
                        if re.fullmatch(r"[^:,]+:\d+(\s*,\s*[^:,]+:\d+)*", cand):
                            listing = cand
                            break
                    break
            try:
                with tarfile.open(target, "w:gz") as tar:
                    if listing:
                        for part in listing.split(","):
                            part = part.strip()
                            if ":" not in part:
                                continue
                            rel, size = part.rsplit(":", 1)
                            src = self.bds.dir / "worlds" / rel
                            if not src.is_file():
                                continue
                            with open(src, "rb") as f:
                                data = f.read(int(size))
                            ti = tarfile.TarInfo(rel)
                            ti.size = len(data)
                            ti.mtime = int(time.time())
                            tar.addfile(ti, io.BytesIO(data))
                    else:
                        tar.add(world, arcname=world.name)
            finally:
                self.bds.send("save resume")
        else:
            with tarfile.open(target, "w:gz") as tar:
                tar.add(world, arcname=world.name)
        old = sorted(backups_dir.glob("monde-*.tar.gz"))
        for f in old[:-KEEP_BACKUPS]:
            f.unlink()
        return f"Sauvegarde créée : {name} ({target.stat().st_size // 1024} Ko)"

    def backups_list(self):
        d = self.base / "backups"
        return [{"name": f.name, "size": f.stat().st_size, "date": int(f.stat().st_mtime)}
                for f in sorted(d.glob("monde-*.tar.gz"))] if d.is_dir() else []

    # ---- BDS

    def bds_url(self):
        if self.cfg.get("bds_url"):
            return self.cfg["bds_url"]
        with http_get(DOWNLOAD_API, timeout=30) as r:
            data = json.loads(r.read())
        for link in data.get("result", {}).get("links", []):
            if link.get("downloadType") == "serverBedrockLinux":
                return link["downloadUrl"]
        raise RuntimeError("Lien de téléchargement de BDS introuvable : indique « bds_url » dans agent.json.")

    def ensure_installed(self):
        if self.bds.installed:
            return
        if not self.cfg.get("eula_accepted"):
            raise RuntimeError("Le CLUF de Minecraft n'a pas été accepté (relance install.sh).")
        self.status, self.message = "installing", "Téléchargement du serveur Bedrock…"
        self.heartbeat()
        self.bds.install_or_update(self.bds_url())

    def prepare_and_start(self):
        self.ensure_installed()
        self.refresh_config()
        self.write_properties()
        self.sync_packs(self.config.get("packs", []))
        level = self.world_dir() / "level.dat"
        if self.server.get("beta_apis") and not level.is_file():
            # Le monde n'existe pas encore : on le laisse se créer, puis on active les API bêta et on relance.
            self.state["beta_pending"] = True
        elif level.is_file():
            set_beta_apis(level, bool(self.server.get("beta_apis")))
        self.status, self.message = "starting", "Démarrage…"
        self.bds.start()
        self.want_running = True
        self.save_state()

    def refresh_config(self):
        self.config = self.api.rpc("bms_agent_config") or {}
        self.server = self.config.get("server", {})

    # ---- Commandes

    def run_command(self, c):
        kind, payload = c["kind"], c.get("payload", "")
        if kind == "start":
            self.prepare_and_start(); return "Démarrage demandé."
        if kind == "stop":
            self.want_running = False; self.save_state()
            self.status, self.message = "stopping", "Arrêt…"; self.heartbeat()
            self.bds.stop(); return "Serveur arrêté."
        if kind == "restart":
            self.bds.stop(); self.prepare_and_start(); return "Redémarrage."
        if kind == "command":
            if not payload.strip():
                return "Commande vide."
            self.bds.send(payload.lstrip("/")); return f"Envoyé : {payload}"
        if kind == "backup":
            return self.backup()
        if kind in ("sync_packs", "apply_settings"):
            was = self.bds.running
            if was:
                self.bds.stop()
            self.ensure_installed(); self.refresh_config(); self.write_properties()
            r = self.sync_packs(self.config.get("packs", []))
            if was:
                self.prepare_and_start()
            return r
        if kind == "update_bds":
            was = self.bds.running
            if was:
                self.bds.stop()
            self.bds.install_or_update(self.bds_url())
            if was:
                self.prepare_and_start()
            return f"BDS mis à jour : {self.bds.version}"
        raise RuntimeError(f"Commande inconnue : {kind}")

    # ---- Boucle

    def forward_log(self, line):
        self.bds.console_lines.append(line)

    def metrics(self):
        m = {"agent": VERSION, "arch": platform.machine()}
        try:
            m["load"] = float(open("/proc/loadavg").read().split()[0])
            info = {l.split(":")[0]: int(l.split()[1]) for l in open("/proc/meminfo") if l.split()[0][:-1] in ("MemTotal", "MemAvailable")}
            m["mem_total_mb"] = info.get("MemTotal", 0) // 1024
            m["mem_used_mb"] = (info.get("MemTotal", 0) - info.get("MemAvailable", 0)) // 1024
            m["cpus"] = os.cpu_count()
            du = shutil.disk_usage(self.base)
            m["disk_free_gb"] = round(du.free / 1e9, 1)
        except Exception:
            pass
        return m

    def heartbeat(self):
        if self.bds.running:
            self.status = "online" if self.bds.started else "starting"
            if self.bds.started:
                self.message = f"{len(self.bds.players)} joueur(s) connecté(s)"
        elif self.status not in ("crashed", "error", "installing"):
            self.status = "offline"
            self.message = "Serveur arrêté" if self.bds.installed else "Serveur Bedrock pas encore téléchargé"
        if not self.address:
            try:
                ip = http_get("https://api.ipify.org", timeout=5).read().decode().strip()
                self.address = f"{ip}:{self.server.get('server_port', 19132)}"
            except Exception:
                pass
        return self.api.rpc(
            "bms_agent_heartbeat", p_status=self.status, p_message=self.message, p_address=self.address,
            p_version=self.bds.version, p_players=list(self.bds.players.values()), p_metrics=self.metrics(),
            p_backups=self.backups_list()) or []

    def loop(self):
        log(f"Agent BMS {VERSION} — serveur {self.api.server}")
        try:
            self.refresh_config()
        except Exception as e:
            log(f"Configuration illisible : {e}")
        if self.want_running:
            try:
                self.prepare_and_start()
            except Exception as e:
                self.status, self.message = "error", str(e)
        while True:
            # Console : on récupère les nouvelles lignes de BDS
            while True:
                try:
                    self.forward_log(self.bds.lines.get_nowait())
                except queue.Empty:
                    break
            if self.bds.console_lines:
                batch, self.bds.console_lines = self.bds.console_lines[:200], self.bds.console_lines[200:]
                try:
                    self.api.rpc("bms_agent_logs", p_lines=batch)
                except Exception as e:
                    log(f"Envoi console impossible : {e}")
            # Monde créé pour la première fois avec API bêta demandées : on les active puis on relance.
            if self.state.get("beta_pending") and self.bds.started and (self.world_dir() / "level.dat").is_file():
                self.state["beta_pending"] = False
                self.bds.stop()
                set_beta_apis(self.world_dir() / "level.dat", True)
                self.bds.start()
            # Plantage : relance automatique (au plus 3 fois en 10 minutes)
            if self.want_running and self.bds.proc is not None and not self.bds.running:
                now = time.time()
                self.crashes = [t for t in self.crashes if now - t < 600] + [now]
                if len(self.crashes) > 3:
                    self.want_running = False
                    self.status, self.message = "crashed", "Plantages répétés : regarde la console (un pack est peut-être en cause)."
                    self.save_state()
                else:
                    self.status, self.message = "crashed", "Plantage détecté : relance automatique…"
                    try:
                        self.bds.start()
                    except Exception as e:
                        self.message = str(e)
            try:
                commands = self.heartbeat()
            except Exception as e:
                log(f"Supabase injoignable : {e}")
                time.sleep(10)
                continue
            for c in commands:
                ok, result = True, ""
                try:
                    result = self.run_command(c)
                except Exception as e:
                    ok, result = False, str(e)
                    if c["kind"] in ("start", "restart"):
                        self.status, self.message = "error", str(e)
                log(f"Commande {c['kind']} → {'ok' if ok else 'ERREUR'} {result}")
                try:
                    self.api.rpc("bms_agent_finish", p_command=c["id"], p_ok=ok, p_result=result)
                except Exception as e:
                    log(f"Compte rendu impossible : {e}")
            time.sleep(HEARTBEAT_SECONDS)


def main():
    if not CONFIG_PATH.is_file():
        sys.exit(f"Configuration absente : {CONFIG_PATH}. Lance install.sh.")
    cfg = json.loads(CONFIG_PATH.read_text())
    agent = Agent(cfg)

    def shutdown(*_):
        log("Arrêt de l'agent : arrêt propre du serveur…")
        agent.bds.stop()
        sys.exit(0)

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    agent.loop()


if __name__ == "__main__":
    main()
