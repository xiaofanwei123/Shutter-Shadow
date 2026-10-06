"""Audit current packages, Mixin registrations, metadata and the distributable JAR."""

from io import BytesIO
import hashlib
import json
from pathlib import Path
import re
import tomllib
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
JAR = "build/libs/shuttershadow-1.0.jar"
OLD_PACKAGE = re.compile(r"(?:qouteall[./]|de[./]nick1st[./])")
LICENSE_PATH = "META-INF/licenses/immersive-portals.txt"
LICENSE_SHA256 = "ce5c2ebe7ac5c646cd58669830e6e9e7cacbabbee33a8d02e332f87f7595512f"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)



def uncomment_java(source: str) -> str:
    pattern = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\r\n]*|/\*[\s\S]*?\*/')
    return pattern.sub(lambda match: " " if match.group().startswith(("//", "/*")) else match.group(), source)



def verify_sources(root: Path) -> None:
    java_root = root / "src/main/java"
    require(not any((root / "src/embeddedIptl").rglob("*.*")), "Old embedded source directory is not empty")
    for path in java_root.rglob("*.java"):
        rel = path.relative_to(java_root).as_posix()
        source = uncomment_java(path.read_text(encoding="utf-8"))
        declarations = re.findall(r"(?m)^\s*package\s+([\w.]+)\s*;", source)
        require(len(declarations) == 1 and declarations[0].replace(".", "/") == path.parent.relative_to(java_root).as_posix(), f"Package/path mismatch: {rel}")
        require(OLD_PACKAGE.search(source) is None, f"Old binary/dotted package remains: {rel}")
        for method_path in re.findall(r'"(com\.xfw\.shuttershadow\.[\w.]*RemoteCallable[\w.]+)"', source):
            owner, method = method_path.rsplit(".", 1)
            candidate = java_root / (owner.replace(".", "/") + ".java")
            if not candidate.is_file():
                owner = owner.rsplit(".", 1)[0]
                candidate = java_root / (owner.replace(".", "/") + ".java")
            require(candidate.is_file() and re.search(r"\b" + re.escape(method) + r"\s*\(", uncomment_java(candidate.read_text(encoding="utf-8"))), f"RPC target has no source method: {method_path}")
        if re.search(r"@(?:net\.neoforged\.fml\.common\.)?Mod\s*\(", source):
            require(rel == "com/xfw/shuttershadow/Shuttershadow.java", f"Second mod entrypoint: {rel}")
    gradle = (root / "build.gradle").read_text(encoding="utf-8")
    require("src/embeddedIptl" not in gradle, "Gradle still includes embeddedIptl")
    templates = list((root / "src/main/templates/META-INF").glob("neoforge.mods.toml"))
    require(len(templates) == 1, "Missing NeoForge metadata template")
    template = templates[0].read_text(encoding="utf-8")
    require("q_misc_util" not in template, "Old utility mod ID remains in metadata template")
    metadata = tomllib.loads(template.replace("${mod_id}", "shuttershadow"))
    verify_mod_ids(metadata)
    reserved_mixin_packages = set()
    registered_mixin_classes = set()
    mixin_plugins = set()
    for path in (root / "src/main/resources").rglob("*.json"):
        if path.name.endswith(".mixins.json"):
            config = json.loads(path.read_bytes())
            package = config.get("package")
            require(isinstance(package, str) and not OLD_PACKAGE.search(package), f"Old Mixin package: {path}")
            reserved_mixin_packages.add(package)
            for side in ("mixins", "client", "server"):
                entries = config.get(side, [])
                require(isinstance(entries, list) and len(entries) == len(set(entries)), f"Duplicate Mixin entries: {path}")
                for entry in entries:
                    registered_mixin_classes.add(package + "." + entry)
                    mixin_source = (java_root / (package + "." + entry).replace(".", "/")).with_suffix(".java")
                    require(mixin_source.is_file(), f"Mixin has no relocated source: {path} {entry}")
                    mixin_text = uncomment_java(mixin_source.read_text(encoding="utf-8"))
                    if "net.caffeinemc." in mixin_text or "net.irisshaders." in mixin_text:
                        require(config.get("plugin") or "@Pseudo" in mixin_text, f"Optional renderer Mixin lost its loading guard: {entry}")
            plugin = config.get("plugin")
            if plugin:
                mixin_plugins.add(plugin)
                require(not OLD_PACKAGE.search(plugin) and (java_root / plugin.replace(".", "/")).with_suffix(".java").is_file(), f"Mixin plugin missing: {path}")
                if "compat" in path.name:
                    plugin_text = uncomment_java((java_root / plugin.replace(".", "/")).with_suffix(".java").read_text(encoding="utf-8"))
                    require('getModFileById("sodium") != null' in plugin_text and 'return sodiumLoaded;' in plugin_text, "Sodium compatibility plugin lost presence guard")
    for path in java_root.rglob("*.java"):
        rel = path.relative_to(java_root).with_suffix("").as_posix().replace("/", ".")
        if rel in registered_mixin_classes or rel in mixin_plugins:
            continue
        require(not any(rel == package or rel.startswith(package + ".") for package in reserved_mixin_packages), f"Non-Mixin class placed under reserved Mixin package: {rel}")
    for path in (root / "src/main/resources").rglob("*"):
        if path.is_file() and path.suffix in (".json", ".cfg", ".toml") and "META-INF/NOTICE" not in path.as_posix():
            require(OLD_PACKAGE.search(path.read_text(encoding="utf-8")) is None, f"Old package in resource: {path}")



def class_utf8(data: bytes) -> list[str]:
    require(data[:4] == b"\xca\xfe\xba\xbe", "Malformed class header")
    count = int.from_bytes(data[8:10], "big")
    position = 10
    strings = []
    index = 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        require(position < len(data), "Truncated class constant pool")
        tag = data[position]
        position += 1
        if tag == 1:
            length = int.from_bytes(data[position:position + 2], "big")
            position += 2
            require(position + length <= len(data), "Truncated class UTF8 constant")
            strings.append(data[position:position + length].decode("utf-8", "replace"))
            position += length
        else:
            require(tag in sizes, f"Unknown class constant tag {tag}")
            position += sizes[tag]
            if tag in (5, 6):
                index += 1
        index += 1
    return strings



def verify_mod_ids(metadata: dict) -> None:
    require([item.get("modId") for item in metadata.get("mods", [])] == ["shuttershadow"], "JAR must declare only Shuttershadow")
    for owner, dependencies in metadata.get("dependencies", {}).items():
        require(owner == "shuttershadow", f"Dependency owned by old mod: {owner}")
        for entry in dependencies:
            mod_id = entry.get("modId")
            require(mod_id not in ("immersive_portals_core", "q_misc_util", "imm_ptl") or entry.get("type", "").lower() == "incompatible", f"Old module dependency: {mod_id}")
            if mod_id in ("sodium", "embeddium", "iris"):
                require(entry.get("type", "").lower() == "optional", f"Renderer became required: {mod_id}")



def verify_archive_packages(archive: ZipFile, label: str = "JAR") -> None:
    for name in archive.namelist():
        require(not name.startswith(("qouteall/", "de/nick1st/")), f"{label} retains old class/package path: {name}")
        if name.endswith(".class"):
            for literal in class_utf8(archive.read(name)):
                require(OLD_PACKAGE.search(literal) is None, f"Old class constant remains in {label}/{name}: {literal[:120]}")
            require(not name.startswith(("net/caffeinemc/", "net/irisshaders/")), f"Optional renderer class bundled: {name}")
        if name.endswith(".jar"):
            with ZipFile(BytesIO(archive.read(name))) as nested:
                verify_archive_packages(nested, f"{label}/{name}")



def verify_jar(root: Path, jar: Path) -> None:
    with ZipFile(jar) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate JAR entries")
        entries = set(names)
        require("META-INF/neoforge.mods.toml" in entries, "JAR lacks NeoForge metadata")
        mods = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode("utf-8-sig"))
        verify_mod_ids(mods)
        configs = [item.get("config") for item in mods.get("mixins", [])]
        require(len(configs) == len(set(configs)), "Duplicate Mixin registrations")
        source_configs = {p.name for p in (root / "src/main/resources").glob("*.mixins.json")}
        require(set(configs) == source_configs, "Mixin registrations differ from source configs")
        seen_mixins = set()
        reserved_mixin_packages = set()
        mixin_plugins = set()
        for name in configs:
            require(name in entries, f"JAR missing Mixin config: {name}")
            source = (root / "src/main/resources" / name).read_bytes()
            require(archive.read(name) == source, f"Mixin config differs from source: {name}")
            config = json.loads(source)
            package = config["package"]
            reserved_mixin_packages.add(package)
            for side in ("mixins", "client", "server"):
                for entry in config.get(side, []):
                    dotted = package + "." + entry
                    require(dotted not in seen_mixins, f"Mixin registered twice: {dotted}")
                    seen_mixins.add(dotted)
                    require(dotted.replace(".", "/") + ".class" in entries, f"Missing Mixin class: {dotted}")
            if config.get("plugin"):
                mixin_plugins.add(config["plugin"])
                require(config["plugin"].replace(".", "/") + ".class" in entries, f"Missing Mixin plugin: {name}")
            if "compat" in name and any("Sodium" in item or "Iris" in item for item in config.get("mixins", [])):
                require(config.get("plugin"), f"Optional renderer Mixin lacks plugin: {name}")
        for entry in entries:
            if not entry.endswith(".class"):
                continue
            dotted = entry.removesuffix(".class").replace("/", ".")
            owner = dotted.split("$", 1)[0]
            if owner in seen_mixins or owner in mixin_plugins:
                continue
            require(not any(dotted == package or dotted.startswith(package + ".") for package in reserved_mixin_packages), f"Non-Mixin class bundled under reserved Mixin package: {dotted}")
        verify_archive_packages(archive)
        java_root = root / "src/main/java"
        for source in java_root.rglob("*.java"):
            require(source.relative_to(java_root).with_suffix(".class").as_posix() in entries, f"JAR missing compiled source: {source}")
        resource_root = root / "src/main/resources"
        for source in resource_root.rglob("*"):
            if not source.is_file():
                continue
            name = source.relative_to(resource_root).as_posix()
            require(name in entries, f"JAR missing source resource: {name}")
            expected = source.read_bytes()
            if name == "pack.mcmeta":
                expected = expected.replace(b"${mod_id}", b"shuttershadow").replace(b"\r\n", b"\n")
            require(archive.read(name) == expected, f"JAR resource differs from source: {name}")
        license_path = "META-INF/licenses/immersive-portals.txt"
        require(license_path in entries, "Upstream Apache license missing")
        require(hashlib.sha256(archive.read(license_path)).hexdigest() == LICENSE_SHA256,
                "Upstream Apache license changed")



if __name__ == "__main__":
    verify_sources(ROOT)
    verify_jar(ROOT, ROOT / JAR)
    print("Current source, Mixin registrations, metadata and JAR audit passed.")
