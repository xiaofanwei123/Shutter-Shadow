"""Positive and negative regression tests for the current source/JAR audit."""

from io import BytesIO
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

import verify_current_core as audit

LICENSE = (Path(__file__).resolve().parents[1] / "src/main/resources" / audit.LICENSE_PATH).read_bytes()


class CurrentCoreAuditTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)


    def write(self, path, data):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data if isinstance(data, bytes) else data.encode())


    def test_source_scan_rejects_stale_descriptors_and_second_mod(self):
        self.write("build.gradle", "plugins { id 'java' }")
        self.write("src/main/templates/META-INF/neoforge.mods.toml", '[[mods]]\nmodId="shuttershadow"\n')
        path = "src/main/java/com/xfw/shuttershadow/Example.java"
        self.write(path, 'package com.xfw.shuttershadow; class Example { String desc = "Lqouteall/imm_ptl/core/Foo;"; }')
        with self.assertRaisesRegex(ValueError, "Old binary/dotted package"):
            audit.verify_sources(self.root)
        self.write(path, 'package com.xfw.shuttershadow; @net.neoforged.fml.common.Mod("old") class Example {}')
        with self.assertRaisesRegex(ValueError, "Second mod entrypoint"):
            audit.verify_sources(self.root)
        self.write(path, "package wrong.place; class Example {}")
        with self.assertRaisesRegex(ValueError, "Package/path mismatch"):
            audit.verify_sources(self.root)


    def test_reserved_mixin_package_contains_only_registered_mixins_or_plugin(self):
        self.write("build.gradle", "plugins { id 'java' }")
        self.write("src/main/templates/META-INF/neoforge.mods.toml", '[[mods]]\nmodId="shuttershadow"\n')
        self.write("src/main/resources/shuttershadow.mixins.json", json.dumps({
            "package": "com.xfw.shuttershadow.mixin",
            "mixins": ["ActualMixin"],
            "plugin": "com.xfw.shuttershadow.mixin.CompatibilityPlugin",
        }))
        self.write("src/main/java/com/xfw/shuttershadow/mixin/ActualMixin.java", "package com.xfw.shuttershadow.mixin; public class ActualMixin {}")
        self.write("src/main/java/com/xfw/shuttershadow/mixin/CompatibilityPlugin.java", "package com.xfw.shuttershadow.mixin; public class CompatibilityPlugin {}")
        audit.verify_sources(self.root)
        helper = "src/main/java/com/xfw/shuttershadow/mixin/Helper.java"
        self.write(helper, "package com.xfw.shuttershadow.mixin; public class Helper {}")
        with self.assertRaisesRegex(ValueError, "Non-Mixin class placed under reserved Mixin package"):
            audit.verify_sources(self.root)
        (self.root / helper).unlink()
        duck = "src/main/java/com/xfw/shuttershadow/mixin/access/IDuck.java"
        self.write(duck, "package com.xfw.shuttershadow.mixin.access; public interface IDuck {}")
        with self.assertRaisesRegex(ValueError, "Non-Mixin class placed under reserved Mixin package"):
            audit.verify_sources(self.root)
        (self.root / duck).unlink()
        self.write("src/main/java/com/xfw/shuttershadow/access/IDuck.java", "package com.xfw.shuttershadow.access; public interface IDuck {}")
        audit.verify_sources(self.root)


    @staticmethod
    def fake_class(literals):
        pool = b"".join(b"\x01" + len(value.encode()).to_bytes(2, "big") + value.encode() for value in literals)
        return b"\xca\xfe\xba\xbe\x00\x00\x00\x3d" + (len(literals) + 1).to_bytes(2, "big") + pool


    def jar(self, *, mods=None, dependencies="", class_literals=None, config=None):
        mods = mods or ["shuttershadow"]
        config = config or {"package": "com.xfw.shuttershadow.mixin", "mixins": ["ExampleMixin"]}
        self.write("src/main/resources/shuttershadow.mixins.json", json.dumps(config))
        metadata = 'modLoader="javafml"\nloaderVersion="[4,)"\n' + "".join(f'[[mods]]\nmodId="{mod}"\nversion="1"\n' for mod in mods)
        metadata += '[[mixins]]\nconfig="shuttershadow.mixins.json"\n' + dependencies
        path = self.root / audit.JAR
        path.parent.mkdir(parents=True, exist_ok=True)
        with ZipFile(path, "w") as archive:
            archive.writestr("META-INF/neoforge.mods.toml", metadata)
            archive.writestr(audit.LICENSE_PATH, LICENSE)
            archive.writestr("shuttershadow.mixins.json", json.dumps(config))
            archive.writestr("com/xfw/shuttershadow/mixin/ExampleMixin.class", self.fake_class(class_literals or ["ExampleMixin"]))
        return path


    def test_jar_single_mod_optional_renderers_and_old_constants(self):
        jar = self.jar()
        audit.verify_jar(self.root, jar)
        jar = self.jar(mods=["shuttershadow", "immersive_portals_core"])
        with self.assertRaisesRegex(ValueError, "only Shuttershadow"):
            audit.verify_jar(self.root, jar)
        jar = self.jar(dependencies='[[dependencies.shuttershadow]]\nmodId="sodium"\ntype="required"\n')
        with self.assertRaisesRegex(ValueError, "Renderer became required"):
            audit.verify_jar(self.root, jar)
        jar = self.jar(class_literals=["Lqouteall/imm_ptl/core/Old;"])
        with self.assertRaisesRegex(ValueError, "Old class constant"):
            audit.verify_jar(self.root, jar)


    def test_mixin_missing_plugin_or_class_rejected(self):
        jar = self.jar(config={"package": "com.xfw.shuttershadow.mixin", "mixins": ["MissingMixin"]})
        with self.assertRaisesRegex(ValueError, "Missing Mixin class"):
            audit.verify_jar(self.root, jar)
        jar = self.jar(config={"package": "com.xfw.shuttershadow.mixin", "mixins": ["ExampleMixin"], "plugin": "com.xfw.shuttershadow.mixin.MissingPlugin"})
        with self.assertRaisesRegex(ValueError, "Missing Mixin plugin"):
            audit.verify_jar(self.root, jar)


    def test_jar_rejects_unregistered_class_in_reserved_mixin_package(self):
        jar = self.jar()
        with ZipFile(jar, "a") as archive:
            archive.writestr("com/xfw/shuttershadow/mixin/HiddenHelper.class", self.fake_class(["HiddenHelper"]))
        with self.assertRaisesRegex(ValueError, "Non-Mixin class bundled under reserved Mixin package"):
            audit.verify_jar(self.root, jar)


    def test_nested_jar_cannot_hide_old_binary_constants(self):
        jar = self.jar()
        nested_buffer = BytesIO()
        with ZipFile(nested_buffer, "w") as nested:
            nested.writestr("com/example/Hidden.class", self.fake_class(["qouteall.imm_ptl.core.Hidden"]))
        with ZipFile(jar, "a") as archive:
            archive.writestr("META-INF/jarjar/hidden.jar", nested_buffer.getvalue())
        with self.assertRaisesRegex(ValueError, "Old class constant"):
            audit.verify_jar(self.root, jar)



if __name__ == "__main__":
    unittest.main()
