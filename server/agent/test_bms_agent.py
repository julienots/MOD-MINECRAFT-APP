"""Tests de l'agent (sans réseau ni serveur Bedrock réel) : python3 -m unittest -v (dans server/agent)."""

import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

import bms_agent as a


def level_dat(path: Path):
    n = a.Nbt
    root = [[n.STR, "LevelName", "Monde"], [n.INT, "GameType", 0], [n.LIST, "lastOpenedWithVersion", (n.INT, [1, 21, 0, 0, 0])],
            [n.LONG, "RandomSeed", 123456789012], [n.COMP, "abilities", [[n.FLOAT, "walkSpeed", 0.1]]]]
    body = bytearray([n.COMP])
    n.write(n.STR, "", body)
    n.write(n.COMP, root, body)
    path.write_bytes(struct.pack("<ii", 10, len(body)) + bytes(body))


def read_tree(path: Path):
    raw = path.read_bytes()
    r = a.Nbt(raw[8:])
    t = r.take(1)[0]
    r.string()
    return raw, r.payload(t)


class NbtTest(unittest.TestCase):
    def test_beta_apis_on_off(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "level.dat"
            level_dat(p)
            self.assertTrue(a.set_beta_apis(p, True))
            self.assertFalse(a.set_beta_apis(p, True), "idempotent")
            raw, tree = read_tree(p)
            exp = {e[1]: e[2] for e in next(e for e in tree if e[1] == "experiments")[2]}
            self.assertEqual(exp["gametest"], 1)
            self.assertEqual(struct.unpack("<i", raw[4:8])[0], len(raw) - 8, "longueur d'en-tête à jour")
            self.assertEqual(next(e for e in tree if e[1] == "RandomSeed")[2], 123456789012, "reste du fichier intact")
            self.assertTrue((Path(d) / "level.dat.bak").is_file())
            self.assertTrue(a.set_beta_apis(p, False))
            _, tree = read_tree(p)
            self.assertEqual({e[1]: e[2] for e in next(e for e in tree if e[1] == "experiments")[2]}["gametest"], 0)

    def test_absent_level(self):
        self.assertFalse(a.set_beta_apis(Path("/nonexistent/level.dat"), True))


class AgentTest(unittest.TestCase):
    def make(self, d):
        cfg = {"supabase_url": "https://x.supabase.co", "anon_key": "anon", "server_id": "s", "agent_key": "k",
               "base_dir": d, "public_address": "1.2.3.4:19132"}
        ag = a.Agent(cfg)
        ag.server = {"name": "Mon serveur", "gamemode": "creative", "difficulty": "hard", "max_players": 9,
                     "level_name": "Monde", "level_seed": "42", "view_distance": 14, "tick_distance": 6,
                     "allow_cheats": True, "server_port": 19132}
        return ag

    def test_properties_keep_unknown_and_cap_players(self):
        with tempfile.TemporaryDirectory() as d:
            ag = self.make(d)
            ag.bds.dir.mkdir(parents=True)
            (ag.bds.dir / "server.properties").write_text("# commentaire\nserver-name=Dedicated Server\nmax-players=10\nlevel-type=DEFAULT\n")
            ag.write_properties()
            props = dict(l.split("=", 1) for l in (ag.bds.dir / "server.properties").read_text().splitlines() if "=" in l and not l.startswith("#"))
            self.assertEqual(props["max-players"], "5", "jamais plus de 5 joueurs")
            self.assertEqual(props["gamemode"], "creative")
            self.assertEqual(props["level-type"], "DEFAULT", "réglage inconnu conservé")
            self.assertEqual(props["texturepack-required"], "true")
            self.assertIn("# commentaire", (ag.bds.dir / "server.properties").read_text())

    def test_sync_packs_install_order_and_removal(self):
        with tempfile.TemporaryDirectory() as d:
            ag = self.make(d)
            ag.bds.dir.mkdir(parents=True)

            def mcpack(uuid, kind):
                buf = Path(d) / f"{uuid}.mcpack"
                with zipfile.ZipFile(buf, "w") as z:
                    z.writestr("Pack/manifest.json", json.dumps({"header": {"name": uuid, "uuid": uuid, "version": [1, 0, 0]},
                                                               "modules": [{"type": kind}]}))
                    z.writestr("Pack/textures/a.png", b"png")
                    z.writestr("../evil.txt", b"x")
                return buf.read_bytes()

            files = {"u1": mcpack("aaaa", "data"), "u2": mcpack("bbbb", "resources"), "u3": mcpack("cccc", "data")}

            class R:
                def __init__(self, b): self.b = b
                def read(self): return self.b

            with mock.patch.object(a, "http_get", side_effect=lambda url, timeout=0: R(files[url])):
                ag.sync_packs([{"id": "1", "name": "A", "url": "u1", "size": 1}, {"id": "2", "name": "B", "url": "u2", "size": 1},
                               {"id": "3", "name": "C", "url": "u3", "size": 1}])
                world = ag.world_dir()
                bp = json.loads((world / "world_behavior_packs.json").read_text())
                self.assertEqual([p["pack_id"] for p in bp], ["aaaa", "cccc"])
                self.assertEqual(json.loads((world / "world_resource_packs.json").read_text())[0]["pack_id"], "bbbb")
                self.assertTrue((ag.bds.dir / "resource_packs/bms_bbbb/textures/a.png").is_file())
                self.assertFalse((Path(d) / "evil.txt").exists(), "chemin dangereux ignoré")
                # Retrait d'un pack
                ag.sync_packs([{"id": "1", "name": "A", "url": "u1", "size": 1}])
                self.assertFalse((ag.bds.dir / "behavior_packs/bms_cccc").exists())
                self.assertEqual(len(json.loads((world / "world_behavior_packs.json").read_text())), 1)


if __name__ == "__main__":
    unittest.main()
