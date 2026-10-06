"""Compare the narrowed production stand query with the pre-review implementation."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
PATH = "src/main/java/com/xfw/shuttershadow/ExposureVisibility.java"


def method(text):
    start = text.index("public static List<Player> playersInFrame(")
    opening = text.index("{", start)
    depth, end = 1, opening + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


ENVIRONMENT = r"""
import java.util.*;
import com.xfw.shuttershadow.util.CaptureEntitySearchRange;

class ShuttershadowConfig {
    static int radius;
    static int standPlayerRadius() { return radius; }
}
interface CameraHolder { Entity asHolderEntity(); }
class ItemStack {
    Object item = new CameraItem();
    Object getItem() { return item; }
}
class CameraItem {
    Object getPointOfView(CameraHolder holder, ItemStack camera) { return holder; }
    double getViewfinderFov(Object level, ItemStack camera) { return 60; }
}
class Entity {
    double x, y, z, width = .6, height = 1.8;
    boolean visible = true;
    Entity(double x, double y, double z) { this.x=x; this.y=y; this.z=z; }
    Object level() { return this; }
    double distanceToSqr(Entity other) {
        return Math.pow(x-other.x,2)+Math.pow(y-other.y,2)+Math.pow(z-other.z,2);
    }
}
class Player extends Entity {
    Player(double x, double y, double z) { super(x,y,z); }
}
class EntitiesInFrame {
    static final List<Entity> entities = new ArrayList<>();
    static int examined;
    static List<Entity> get(Entity holder, Object view, double fov) {
        // Exposure 1.9.19: AABB(holder.blockPosition()).inflate(128), then
        // stable distance sort and independent per-entity visibility checks.
        double radius=CaptureEntitySearchRange.currentOr(128);
        double minX=Math.floor(holder.x)-radius, maxX=Math.floor(holder.x)+1+radius;
        double minY=Math.floor(holder.y)-radius, maxY=Math.floor(holder.y)+1+radius;
        double minZ=Math.floor(holder.z)-radius, maxZ=Math.floor(holder.z)+1+radius;
        List<Entity> result=new ArrayList<>();
        for(Entity e:entities) {
            if(e.x+e.width/2>minX && e.x-e.width/2<maxX && e.y+e.height>minY && e.y<maxY
                    && e.z+e.width/2>minZ && e.z-e.width/2<maxZ) {
                examined++;
                if(e.visible) result.add(e);
            }
        }
        result.sort(Comparator.comparingDouble(holder::distanceToSqr));
        return result;
    }
}
"""

HARNESS = r"""
public class StandPlayerQueryTest {
    static int checks;
    static void check(boolean value, String message) {
        checks++; if(!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Random random=new Random(612023);
        ItemStack camera=new ItemStack();
        int oldExamined=0, newExamined=0;
        for(int radius=1;radius<=64;radius++) {
            ShuttershadowConfig.radius=radius;
            for(int sample=0;sample<80;sample++) {
                Entity holder=new Entity(random.nextDouble()*40-20, random.nextDouble()*40-20, random.nextDouble()*40-20);
                CameraHolder cameraHolder=()->holder;
                EntitiesInFrame.entities.clear();
                for(int i=0;i<120;i++) {
                    double x=holder.x+random.nextDouble()*280-140;
                    double y=holder.y+random.nextDouble()*280-140;
                    double z=holder.z+random.nextDouble()*280-140;
                    Entity e=i%4==0?new Entity(x,y,z):new Player(x,y,z);
                    e.visible=random.nextBoolean(); EntitiesInFrame.entities.add(e);
                }
                // Fractional/negative block coordinates, exact spherical boundary,
                // slightly outside it, equal-distance ordering and hidden players.
                for(int axis=0;axis<3;axis++) for(int sign:new int[]{-1,1}) {
                    Player boundary=new Player(holder.x+(axis==0?sign*radius:0), holder.y+(axis==1?sign*radius:0), holder.z+(axis==2?sign*radius:0));
                    EntitiesInFrame.entities.add(boundary);
                    EntitiesInFrame.entities.add(new Player(boundary.x+(axis==0?sign*.0001:0), boundary.y+(axis==1?sign*.0001:0), boundary.z+(axis==2?sign*.0001:0)));
                }
                EntitiesInFrame.examined=0;
                List<Player> expected=BeforeQuery.playersInFrame(cameraHolder,camera);
                oldExamined+=EntitiesInFrame.examined;
                EntitiesInFrame.examined=0;
                List<Player> actual=CurrentQuery.playersInFrame(cameraHolder,camera);
                newExamined+=EntitiesInFrame.examined;
                check(expected.equals(actual), "stand eligibility/order changed at radius="+radius);
                check(CaptureEntitySearchRange.currentOr(128)==128, "stand query leaked its range");
            }
        }
        check(newExamined<oldExamined, "narrowed query failed to avoid unrelated entity work");
        Entity holder=new Entity(0,0,0);
        CameraHolder cameraHolder=()->holder;
        CaptureEntitySearchRange.withRadius(16, ()->{
            CurrentQuery.playersInFrame(cameraHolder,camera);
            check(CaptureEntitySearchRange.currentOr(128)==16, "nested stand query lost outer range");
            return null;
        });
        camera.item=new Object();
        check(CurrentQuery.playersInFrame(cameraHolder,camera).isEmpty(), "invalid camera must remain empty");
        System.out.println("Stand player query: "+checks+" checks; examined "+oldExamined+" -> "+newExamined+" entities.");
    }
}
"""


class StandPlayerQueryTests(unittest.TestCase):
    def test_eligibility_and_order_match_previous_query(self):
        with ZipFile(ROOT / "tools/fixtures/queries-traversal-before.zip") as archive:
            before = archive.read(PATH).decode("utf-8")
        current = (ROOT / PATH).read_text(encoding="utf-8")
        with tempfile.TemporaryDirectory(prefix="shuttershadow-stand-query-") as temporary:
            folder = Path(temporary)
            source = folder / "StandPlayerQueryTest.java"
            source.write_text(ENVIRONMENT + "\nclass BeforeQuery {\n" + method(before) + "\n}\n"
                              + "class CurrentQuery {\n" + method(current) + "\n}\n" + HARNESS, encoding="utf-8")
            subprocess.run([shutil.which("javac"), "-encoding", "UTF-8", "--release", "21", "-d", str(folder),
                            str(source), str(ROOT / "src/main/java/com/xfw/shuttershadow/util/CaptureEntitySearchRange.java")], check=True)
            subprocess.run([shutil.which("java"), "-ea", "-cp", str(folder), "StandPlayerQueryTest"], check=True)


if __name__ == "__main__":
    unittest.main()
