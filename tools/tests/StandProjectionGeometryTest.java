import com.xfw.shuttershadow.ExposureVisibility;
import com.xfw.shuttershadow.RemoteCaptureContext;
import com.xfw.shuttershadow.ShuttershadowConfig;
import com.xfw.shuttershadow.api.ChunkLoader;
import com.xfw.shuttershadow.api.DimensionFilters;
import fixture.StandPending;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/** Uses the actual Exposure entity query, FrustumCheck and focal length formulas. */
public final class StandProjectionGeometryTest {
    static int checks;
    static final ResourceLocation FILTER = ResourceLocation.parse("shuttershadow:dimension_filter");
    static final class Environment {
        final MinecraftServer server = new MinecraftServer();
        final ServerLevel source, target;
        final ServerPlayer operator;
        final CameraItem item = new CameraItem(ResourceLocation.parse("exposure:camera"));
        final ItemStack camera = new ItemStack(item);
        final CameraStandEntity stand;
        final double scale;

        Environment(double scale) {
            this.scale=scale;
            source=new ServerLevel(key("fixture:source"),scale);
            target=new ServerLevel(key("fixture:target"),1);
            server.levels.put(source.dimension(),source);server.levels.put(target.dimension(),target);
            operator=new ServerPlayer(1,UUID.randomUUID(),server,source);
            server.players.players.put(operator.getUUID(),operator);
            operator.setPos(new Vec3(100,0,100));
            camera.active=true;camera.filter=new ItemStack(new Item(FILTER));camera.filter.target=target.dimension().location();
            DimensionFilters.routes.put(new DimensionFilters.Key(camera.filter.target,source.dimension().location()),
                    new DimensionFilters.Route(FILTER,camera.filter.target,1));
            stand=new CameraStandEntity(99,source,operator,camera);
            stand.setPos(Vec3.ZERO);source.entities.put(99,stand);
        }

        ServerPlayer player(int id,double x,double y,double z) {
            var player=new ServerPlayer(id,UUID.randomUUID(),server,source);
            player.setPos(new Vec3(x,y,z));source.entities.put(id,player);server.players.players.put(player.getUUID(),player);
            return player;
        }

        RemoteCaptureContext remote() { return RemoteCaptureContext.resolveForTransfer(stand,camera); }
        StandPending pending(RemoteCaptureContext remote,boolean redstone,boolean noImage) {
            var pending=new StandPending();pending.stand=stand;pending.player=operator;pending.camera=camera;
            pending.filter=camera.filter.copy();pending.film=camera.film.copy();pending.remote=remote;
            pending.sourceOrigin=stand.position();pending.sourceLevel=source;
            pending.sourceCapture=redstone;pending.discardImage=noImage;
            pending.pitch=stand.getXRot();pending.yaw=stand.getYRot();
            return pending;
        }
    }

    static ResourceKey<Level> key(String id) { return ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(id)); }
    static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
    static boolean targetVisible(Environment env,RemoteCaptureContext remote,ServerPlayer player) {
        return ExposureVisibility.isVisible(env.item.getPointOfView(remote,env.camera),remote.projectedPlayer(player),env.item.fov);
    }

    public static void main(String[] args) {
        switch(args[0]) {
            case "nether-eight" -> {
                var env=new Environment(8);var player=env.player(2,0,5,2);var remote=env.remote();
                check(remote.coordinateScale()==8,"Nether-to-other-world map uses eight");
                check(!ExposureVisibility.playersInFrame(env.stand,env.camera).contains(player),"old source camera rejects the high source eye angle");
                check(targetVisible(env,remote,player),"the eight-times horizontal projection is visibly inside the target frame");
                check(remote.playersInFrame(env.camera).equals(List.of(player)),"target-visible player must qualify");
                check(env.pending(remote,false,true).candidatesForPhoto().equals(List.of(player)),"manual failure-exposure transfer uses target eligibility");
            }
            case "reverse-eighth" -> {
                var env=new Environment(.125);var player=env.player(2,0,2,6);var remote=env.remote();
                check(ExposureVisibility.playersInFrame(env.stand,env.camera).contains(player),"source view can include a player absent from the remote frame");
                check(!targetVisible(env,remote,player),"compressed horizontal coordinates move the eye outside the target view");
                check(remote.playersInFrame(env.camera).isEmpty(),"off-frame target players do not transfer");
            }
            case "unit-and-boundary" -> {
                var env=new Environment(1);var near=env.player(2,0,0,2);var far=env.player(3,0,0,4);
                double tangent=Math.tan(Math.toRadians(env.item.fov*.95)/2);
                var inside=env.player(4,tangent*4-1e-5,0,4);var outside=env.player(5,tangent*4+1e-5,0,4);
                var remote=env.remote();var actual=remote.playersInFrame(env.camera);
                check(actual.contains(near)&&actual.contains(far)&&actual.contains(inside),"near and boundary-inside players qualify");
                check(!actual.contains(outside),"the actual Exposure FOV boundary excludes outside eyes");
                check(actual.equals(ExposureVisibility.playersInFrame(env.stand,env.camera)),"unit maps retain original eligibility and distance order");
                var tie=env.player(6,-tangent*4+1e-5,0,4);actual=remote.playersInFrame(env.camera);
                check(actual.indexOf(near)<actual.indexOf(far),"players keep stable near-to-far target ordering");
                check(actual.contains(tie),"symmetric boundary-inside eye remains eligible");
            }
            case "source-radius" -> {
                var env=new Environment(.125);var boundary=env.player(2,0,0,8);var outside=env.player(3,0,0,8.0001);var remote=env.remote();
                check(targetVisible(env,remote,outside),"remote visibility alone can include a source-out-of-range player");
                check(remote.playersInFrame(env.camera).equals(List.of(boundary)),"source sphere includes its boundary and rejects an arbitrarily small excess");
                env.player(4,0,0,7);ShuttershadowConfig.radius=4;
                check(remote.playersInFrame(env.camera).isEmpty(),"the configured source radius applies before projection");
            }
            case "redstone-source" -> {
                var env=new Environment(8);var player=env.player(2,0,5,2);var remote=env.remote();
                check(env.pending(remote,true,true).candidatesForPhoto().isEmpty(),"redstone remains source-frustum qualified");
                check(env.pending(remote,false,true).candidatesForPhoto().contains(player),"manual view uses target geometry independently of redstone");
                env.stand.setXRot(-65);remote=env.remote();
                check(env.pending(remote,true,true).candidatesForPhoto().equals(ExposureVisibility.playersInFrame(env.stand,env.camera)),"redstone still follows the actual Exposure source query after turning");
            }
            case "lifecycle" -> {
                var env=new Environment(1);var live=env.player(2,0,0,2);var dead=env.player(3,0,0,3);dead.alive=false;
                var removed=env.player(4,0,0,4);removed.removed=true;var moved=env.player(5,0,0,5);moved.world=env.target;
                var remote=env.remote();check(remote.playersInFrame(env.camera).equals(List.of(live)),"dead, removed and other-world entries are excluded");
                env.target.blocked=true;check(remote.playersInFrame(env.camera).isEmpty(),"target-world collisions exclude projected players");
                env.target.blocked=false;env.source.blocked=true;
                check(remote.playersInFrame(env.camera).equals(List.of(live)),"source-world geometry does not hide a model visible in the rendered target world");
                remote.freezePlayersInFrame(List.of(live));live.setPos(new Vec3(0,0,-2));
                check(remote.playersInFrame(env.camera).equals(List.of(live)),"the committed shutter list remains frozen");
            }
            case "rotation-image" -> {
                var env=new Environment(8);env.stand.setXRot(15);env.stand.setYRot(10);var remote=env.remote();
                var manual=env.pending(remote,false,false);var redstone=env.pending(remote,true,false);
                check(manual.invalidReason()==null&&redstone.invalidReason()==null,"ordinary photos accept their original nonzero shutter angles");
                env.stand.setXRot(15.00001F);
                check(manual.invalidReason().equals("stand rotated"),"manual photos still cancel when the live screenshot pitch changes");
                check(redstone.invalidReason().equals("stand rotated"),"redstone photos still cancel when the live screenshot pitch changes");
                env.stand.setXRot(15);env.stand.setYRot(10.00001F);
                check(manual.invalidReason().equals("stand rotated"),"manual photos still cancel when the live screenshot yaw changes");
                check(redstone.invalidReason().equals("stand rotated"),"redstone photos still cancel when the live screenshot yaw changes");
                env.stand.setYRot(10);
                check(manual.invalidReason()==null&&redstone.invalidReason()==null,"restoring original screenshot angles restores photo validity");
            }
            case "rotation-snapshot" -> {
                var env=new Environment(8);env.player(2,0,5,2);var remote=env.remote();var pending=env.pending(remote,false,true);
                var redstone=env.pending(remote,true,true);
                var before=remote.playersInFrame(env.camera);env.stand.setXRot(.00001F);env.stand.setYRot(.00001F);
                check(pending.invalidReason()==null,"small late rotation packets do not cancel exposure-failure transfer");
                check(redstone.invalidReason()==null,"redstone exposure-failure transfer also ignores late rotation packets");
                check(remote.asHolderEntity().getXRot()==0&&remote.asHolderEntity().getYRot()==0,"the observation retains shutter-time rotation");
                env.stand.setXRot(80);env.stand.setYRot(180);
                check(pending.invalidReason()==null&&remote.playersInFrame(env.camera).equals(before),"exposure-failure post-shutter turns preserve the existing pose snapshot");
                env.stand.setPos(new Vec3(1,0,0));check(pending.invalidReason().equals("stand moved"),"actual stand movement still invalidates the transaction");
                env.stand.setPos(Vec3.ZERO);env.camera.filter.target=ResourceLocation.parse("fixture:changed");
                check(pending.invalidReason().equals("filter changed"),"changing filter still cancels the old target transaction");
            }
            case "exposure-failure" -> {
                var env=new Environment(8);var player=env.player(2,0,5,2);var remote=env.remote();var pending=env.pending(remote,false,true);
                check(pending.mobChunksReady(),"player-film failure exposure needs no target terrain readiness");
                check(pending.candidatesForPhoto().equals(List.of(player)),"the no-image shot has a transfer candidate without creating an image");
                check(env.source.chunkQueries==0&&env.target.chunkQueries==0&&ChunkLoader.fullyLoadedQueries==0,"neither qualification nor readiness waits for destination chunks");
            }
            default -> throw new AssertionError(args[0]);
        }
        System.out.println("PASS: "+args[0]+" ("+checks+" checks)");
    }
}
