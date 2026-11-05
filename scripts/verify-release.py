#!/usr/bin/env python3
"""Check a locally built BossAddon release jar without launching or installing Minecraft."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import zipfile


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--minecraft-jar", type=Path, help="Also verify the two port hook descriptors with JDK 25 javap")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    require(args.jar.name == f"bossaddon-{args.version}.jar", "Unexpected release filename")
    with zipfile.ZipFile(args.jar) as jar:
        names = jar.namelist()
        require(len(names) == len(set(names)), "Duplicate archive entries")
        require(jar.testzip() is None, "Archive CRC failure")
        metadata = json.loads(jar.read("fabric.mod.json"))
        require(metadata["id"] == "bossaddon" and metadata["version"] == args.version, "Wrong mod ID/version")
        require(metadata["environment"] == "client", "Wrong mod environment")
        require(metadata["depends"]["minecraft"] == "~26.2", "Wrong Minecraft range")
        require(metadata["depends"]["java"] == ">=25", "Wrong Java requirement")
        require(metadata["license"] == "GPL-3.0", "Wrong addon license")
        for entries in metadata["entrypoints"].values():
            for entry in entries:
                require(entry.replace(".", "/") + ".class" in names, f"Missing entrypoint: {entry}")
        for mixin_file in metadata["mixins"]:
            config = json.loads(jar.read(mixin_file))
            require(config["compatibilityLevel"] == "JAVA_25", "Wrong mixin Java level")
            for name in config.get("client", []) + config.get("mixins", []):
                path = (config["package"] + "." + name).replace(".", "/") + ".class"
                require(path in names, f"Missing mixin: {path}")
        utility = json.loads(jar.read("boss-utility.mixins.json"))
        require({"ItemRenderCapFrameMixin", "ItemRenderCapDispatcherMixin"} <= set(utility["client"]), "Missing cap hooks")
        for name in ["ItemGroupSelector", "ItemRenderFrame", "ItemRenderCap"]:
            require(f"com/boss/utility/itemrendercap/{name}.class" in names, f"Missing port class: {name}")
        require("com/boss/utility/module/render/ItemRenderCapModule.class" in names, "Missing cap module")
        for name in names:
            require(not name.endswith((".jar", ".log", ".properties")), f"Dependency/config/log bundled: {name}")
            if name.endswith(".class"):
                require(name.startswith("com/boss/"), f"Bundled foreign dependency class: {name}")
                require("/test/" not in name and not name.endswith("Test.class"), f"Packaged test: {name}")
                magic, _, major = struct.unpack(">IHH", jar.read(name)[:8])
                require(magic == 0xCAFEBABE and major == 69, f"Wrong Java class version: {name}")
        licenses = root / "src/main/resources/META-INF/licenses"
        for source in licenses.iterdir():
            if source.is_file():
                require(jar.read("META-INF/licenses/" + source.name) == source.read_bytes(),
                        f"License/notice differs: {source.name}")
        require(jar.read("META-INF/licenses/BossAddon-GPL-3.0.txt") == (root / "LICENSE").read_bytes(), "GPL text differs")
    if args.minecraft_jar:
        javap = Path(os.environ["JAVA_HOME"]) / "bin" / ("javap.exe" if os.name == "nt" else "javap")
        hooks = {
            "net.minecraft.client.renderer.extract.LevelExtractor":
                "(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V",
            "net.minecraft.client.renderer.entity.EntityRenderDispatcher":
                "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
        }
        for owner, descriptor in hooks.items():
            output = subprocess.check_output([str(javap), "-s", "-classpath", str(args.minecraft_jar), owner], text=True)
            require("descriptor: " + descriptor in output, f"Hook descriptor missing: {owner}")
        print("Minecraft 26.2 cap hook descriptors: PASS (not a runtime mixin application test)")
    print("Metadata, entrypoints, all mixin classes, Java 25 classes, licenses, dependency/test/config exclusions: PASS")
    print(hashlib.sha256(args.jar.read_bytes()).hexdigest() + "  " + args.jar.name)


if __name__ == "__main__":
    main()
