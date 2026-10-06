"""Verify userdev packaging against the unchanged official Sodium distribution."""

import io
import json
from pathlib import Path
import sys
import zipfile


def verify(dev_path: Path, distribution_path: Path) -> None:
    with zipfile.ZipFile(distribution_path) as distribution, zipfile.ZipFile(dev_path) as dev:
        metadata = json.loads(distribution.read("META-INF/jarjar/metadata.json"))
        implementations = [entry for entry in metadata["jars"]
                           if entry["path"].startswith("META-INF/jarjar/net.caffeinemc.sodium-")
                           and entry["path"].endswith("-mod.jar")]
        assert len(implementations) == 1, "Expected exactly one Sodium implementation"
        main_path = implementations[0]["path"]
        with zipfile.ZipFile(io.BytesIO(distribution.read(main_path))) as implementation:
            for name in implementation.namelist():
                if not name.endswith("/"):
                    assert dev.read(name) == implementation.read(name), f"Changed implementation entry: {name}"
            overridden = set(implementation.namelist())
        for name in distribution.namelist():
            if name.endswith("/") or name in overridden or name in (main_path, "META-INF/jarjar/metadata.json"):
                continue
            assert dev.read(name) == distribution.read(name), f"Lost distribution entry: {name}"
        metadata["jars"].remove(implementations[0])
        assert json.loads(dev.read("META-INF/jarjar/metadata.json")) == metadata, "Unexpected nested dependency change"
        assert main_path not in dev.namelist(), "Duplicate Sodium implementation remains nested"
        assert b"FMLModType: LIBRARY" not in dev.read("META-INF/MANIFEST.MF"), "Userdev must discover the implementation as a mod"
        for entry in metadata["jars"]:
            assert entry["path"] in dev.namelist(), f"Missing nested dependency: {entry['path']}"
    print("Sodium userdev distribution: implementation, resources and nested dependencies preserved")


if __name__ == "__main__":
    verify(Path(sys.argv[1]), Path(sys.argv[2]))
