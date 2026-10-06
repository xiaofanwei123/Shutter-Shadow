"""Execute production stand qualification against Exposure's actual frustum and entity-query bytecode."""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest
import zipfile

from test_camera_route_identity import ROOT, JAVA, declaration, fixtures


EXPOSURE = Path(os.environ["USERPROFILE"]) / ".gradle/caches/modules-2/files-2.1/curse.maven/exposure-871755/8957000/181d328a0328523fa99dd310937c40d917e91029/exposure-871755-8957000.jar"
BACKUP = ROOT / "tools/fixtures/stand-projection-before.zip"


def environment():
    result = fixtures()
    del result["com.xfw.shuttershadow.ExposureVisibility"]
    result["net.minecraft.world.phys.Vec3"] = result["net.minecraft.world.phys.Vec3"].replace(
        "public int hashCode()", """public double dot(Vec3 other) { return x*other.x+y*other.y+z*other.z; }
        public Vec3 cross(Vec3 other) { return new Vec3(y*other.z-z*other.y,z*other.x-x*other.z,x*other.y-y*other.x); }
        public Vec3 normalize() { double length=Math.sqrt(dot(this)); return length<1.0E-4 ? ZERO : new Vec3(x/length,y/length,z/length); }
        public double distanceTo(Vec3 other) { return Math.sqrt(distanceToSqr(other)); }
        public int hashCode()""")
    result["net.minecraft.world.phys.AABB"] = """public class AABB {
        public final double x0,y0,z0,x1,y1,z1;
        public AABB(net.minecraft.core.BlockPos pos) { this(pos.getX(),pos.getY(),pos.getZ(),pos.getX()+1,pos.getY()+1,pos.getZ()+1); }
        public AABB(double x0,double y0,double z0,double x1,double y1,double z1) { this.x0=x0;this.y0=y0;this.z0=z0;this.x1=x1;this.y1=y1;this.z1=z1; }
        public AABB inflate(double radius) { return new AABB(x0-radius,y0-radius,z0-radius,x1+radius,y1+radius,z1+radius); }
        public double getSize() { return ((x1-x0)+(y1-y0)+(z1-z0))/3; }
        public boolean intersects(AABB other) { return x0<other.x1 && x1>other.x0 && y0<other.y1 && y1>other.y0 && z0<other.z1 && z1>other.z0; }
    }"""
    result["net.minecraft.world.phys.HitResult"] = "public class HitResult { public enum Type { MISS, BLOCK } }"
    result["net.minecraft.world.phys.BlockHitResult"] = "public class BlockHitResult extends HitResult { private final Type type; public BlockHitResult(Type type) { this.type=type; } public Type getType() { return type; } }"
    result["net.minecraft.world.level.ClipContext"] = """public class ClipContext {
        public enum Block { COLLIDER } public enum Fluid { NONE }
        public ClipContext(net.minecraft.world.phys.Vec3 from,net.minecraft.world.phys.Vec3 to,Block block,Fluid fluid,net.minecraft.world.entity.Entity entity) {}
    }"""
    result["net.minecraft.world.level.Level"] = result["net.minecraft.world.level.Level"].replace(
        "public boolean hasChunkAt", """public int chunkQueries,rayQueries; public boolean blocked;
        public java.util.List<net.minecraft.world.entity.Entity> getEntities(net.minecraft.world.entity.Entity ignored,net.minecraft.world.phys.AABB box) {
            return entities.values().stream().filter(entity -> entity!=ignored && box.intersects(entity.getBoundingBoxForCulling())).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        }
        public net.minecraft.world.phys.BlockHitResult clip(ClipContext context) { rayQueries++;return new net.minecraft.world.phys.BlockHitResult(blocked?net.minecraft.world.phys.HitResult.Type.BLOCK:net.minecraft.world.phys.HitResult.Type.MISS); }
        public boolean hasChunkAt""").replace("return true; }\n        public Fluid", "chunkQueries++; throw new AssertionError(\"unexpected terrain readiness query\"); }\n        public Fluid")
    result["net.minecraft.world.entity.Entity"] = result["net.minecraft.world.entity.Entity"].replace(
        "public float getEyeHeight()", """public boolean isAlive() { return true; } public boolean isRemoved() { return false; }
        public double distanceToSqr(Entity other) { return position().distanceToSqr(other.position()); }
        public double distanceToSqr(net.minecraft.world.phys.Vec3 other) { return position().distanceToSqr(other); }
        public net.minecraft.world.phys.AABB getBoundingBoxForCulling() { var p=position(); return new net.minecraft.world.phys.AABB(p.x-.3,p.y,p.z-.3,p.x+.3,p.y+1.8,p.z+.3); }
        public float pitch,yaw;
        public float getEyeHeight()""").replace("getXRot() { return 0; }", "getXRot() { return pitch; }").replace(
        "getYRot() { return 0; }", "getYRot() { return yaw; }").replace(
        "setXRot(float angle) {}", "setXRot(float angle) { pitch=angle; }").replace(
        "setYRot(float angle) {}", "setYRot(float angle) { yaw=angle; }")
    result["net.minecraft.world.entity.LivingEntity"] = "public class LivingEntity extends Entity { public LivingEntity(int id,net.minecraft.world.level.Level level) { super(id,level); } }"
    result["net.minecraft.world.entity.player.Player"] = result["net.minecraft.world.entity.player.Player"].replace(
        "extends net.minecraft.world.entity.Entity", "extends net.minecraft.world.entity.LivingEntity")
    result["io.github.mortuusars.exposure.util.PointOfView"] = "public record PointOfView(net.minecraft.world.phys.Vec3 pos,net.minecraft.world.phys.Vec3 dir) {}"
    result["io.github.mortuusars.exposure.world.item.camera.CameraItem"] = """public class CameraItem extends net.minecraft.world.item.Item {
        public double fov=60;
        public CameraItem(net.minecraft.resources.ResourceLocation id) { super(id); }
        public boolean isActive(net.minecraft.world.item.ItemStack stack) { return stack.active; }
        public io.github.mortuusars.exposure.util.PointOfView getPointOfView(io.github.mortuusars.exposure.world.entity.CameraHolder holder,net.minecraft.world.item.ItemStack stack) {
            var e=holder.asHolderEntity();double pitch=Math.toRadians(e.getXRot()),yaw=Math.toRadians(e.getYRot());
            return new io.github.mortuusars.exposure.util.PointOfView(e.getEyePosition(),new net.minecraft.world.phys.Vec3(-Math.sin(yaw)*Math.cos(pitch),-Math.sin(pitch),Math.cos(yaw)*Math.cos(pitch)));
        }
        public double getViewfinderFov(net.minecraft.world.level.Level level,net.minecraft.world.item.ItemStack stack) { return fov; }
    }"""
    result["net.minecraft.world.item.ItemStack"] = result["net.minecraft.world.item.ItemStack"].replace(
        "public ItemStack filter = EMPTY;", "public ItemStack film=EMPTY; public boolean mob; public ItemStack filter = EMPTY;").replace(
        "public ItemStack copy()", "public static boolean isSameItemSameComponents(ItemStack a,ItemStack b) { return a.getItem()==b.getItem() && java.util.Objects.equals(a.target,b.target); } public ItemStack copy()")
    result["io.github.mortuusars.exposure.world.item.camera.Attachment"] = """public class Attachment {
        public static final Attachment FILTER=new Attachment(false),FILM=new Attachment(true); private final boolean film;
        Attachment(boolean film) { this.film=film; }
        public Reading get(net.minecraft.world.item.ItemStack camera) { return new Reading(film?camera.film:camera.filter); }
        public record Reading(net.minecraft.world.item.ItemStack filter) { public net.minecraft.world.item.ItemStack getForReading() { return filter; } }
    }"""
    result["com.xfw.shuttershadow.ShuttershadowConfig"] = "public class ShuttershadowConfig { public static int radius=8; public static int standPlayerRadius() { return radius; } public static int mobCaptureRadius() { return 16; } public static int maxRemoteViewDistance() { return 32; } }"
    result["com.xfw.shuttershadow.MobDimensionFilmCapture"] = "public class MobDimensionFilmCapture { public static boolean hasMobDimensionFilm(net.minecraft.world.item.ItemStack camera) { return camera.mob; } }"
    result["com.xfw.shuttershadow.api.ChunkLoading"] = result["com.xfw.shuttershadow.api.ChunkLoading"].replace(
        "public static int adds, removes;", "public static int adds, removes; public static void addGlobalChunkLoader(Object server,Object loader) { throw new AssertionError(\"player film added terrain preload\"); } public static void removeGlobalChunkLoader(Object server,Object loader) {}")
    return result


def pending_fixture():
    source = JAVA / "network/RemoteStandPreparation.java"
    return """package fixture;
        import com.xfw.shuttershadow.*;
        import com.xfw.shuttershadow.api.*;
        import com.xfw.shuttershadow.network.RemoteCameraSession;
        import com.xfw.shuttershadow.util.McHelper;
        import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
        import io.github.mortuusars.exposure.world.item.camera.Attachment;
        import net.minecraft.core.BlockPos;
        import net.minecraft.server.level.*;
        import net.minecraft.world.item.ItemStack;
        import net.minecraft.world.phys.Vec3;
        import java.util.*;
        public class StandPending {
            public CameraStandEntity stand;
            public ServerPlayer player;
            public ItemStack camera,filter,film;
            public RemoteCaptureContext remote;
            public ServerLevel sourceLevel;
            public Vec3 sourceOrigin;
            public boolean sourceCapture,discardImage;
            public float pitch,yaw;
            public long sequence;
            public List<ServerPlayer> playersInFrame=List.of();
            public Set<ChunkLoader> transferLoaders=new HashSet<>();
    """ + "\n".join(declaration(source, signature).replace("private ", "public ", 1) for signature in (
        "private String invalidReason(", "private List<ServerPlayer> candidatesForPhoto(",
        "private boolean mobChunksReady(")) + "}"


class StandProjectionGeometryTests(unittest.TestCase):
    def test_actual_exposure_geometry(self):
        with tempfile.TemporaryDirectory(prefix="shuttershadow-stand-projection-") as temporary:
            folder = Path(temporary)
            sources = []
            for name, body in environment().items():
                path = folder / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body, encoding="utf-8")
                sources.append(str(path))
            pending = folder / "fixture/StandPending.java"
            pending.parent.mkdir(parents=True, exist_ok=True)
            pending.write_text(pending_fixture(), encoding="utf-8")
            sources.append(str(pending))
            sources.extend(str(JAVA / path) for path in (
                "RemoteCaptureContext.java", "ExposureVisibility.java", "util/CaptureEntitySearchRange.java",
                "network/RemoteCameraSession.java", "network/CameraSessionRequestC2S.java"))
            sources.append(str(ROOT / "tools/tests/StandProjectionGeometryTest.java"))
            javac, java = shutil.which("javac"), shutil.which("java")

            def compile_sources(values):
                run = subprocess.run([javac, "-encoding", "UTF-8", "--release", "21", "-cp", str(EXPOSURE),
                                      "-d", str(folder), *values], capture_output=True, text=True, encoding="utf-8", errors="replace")
                self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

            def execute(scenario):
                return subprocess.run([java, "-ea", "-cp", str(folder) + os.pathsep + str(EXPOSURE),
                                       "StandProjectionGeometryTest", scenario], capture_output=True,
                                      text=True, encoding="utf-8", errors="replace")

            compile_sources(sources)
            for scenario in ("nether-eight", "reverse-eighth", "unit-and-boundary", "source-radius",
                             "redstone-source", "lifecycle", "rotation-image", "rotation-snapshot", "exposure-failure"):
                run = execute(scenario)
                self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
                print(run.stdout.strip(), flush=True)
            with zipfile.ZipFile(BACKUP) as archive:
                baseline = folder / "baseline/RemoteCaptureContext.java"
                baseline.parent.mkdir(parents=True)
                baseline.write_bytes(archive.read("RemoteCaptureContext.java"))
            compile_sources([str(baseline), *[value for value in sources if not value.endswith("RemoteCaptureContext.java")]])
            run = execute("nether-eight")
            self.assertNotEqual(run.returncode, 0, "the previous source-space query should reproduce the bug")
            self.assertIn("target-visible player must qualify", run.stderr)
            print("BASELINE reproduced: Nether target-visible player rejected by source-space frustum", flush=True)


if __name__ == "__main__":
    unittest.main()
