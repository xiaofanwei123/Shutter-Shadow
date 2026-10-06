package com.xfw.shuttershadow.core.chunk_loading;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import com.xfw.shuttershadow.util.McHelper;
import com.xfw.shuttershadow.network.PacketRedirection;

import java.util.Set;

public class WorldInfoSender {
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            event.getServer().getProfiler().push("shuttershadow_send_camera_world_info");
            if (McHelper.getServerGameTime() % 100 == 42) {
                for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                    Set<ResourceKey<Level>> visibleDimensions = RemoteChunkTracking.getVisibleDimensions(player);

                    for (ResourceKey<Level> dimension : visibleDimensions) {
                        ServerLevel world = event.getServer().getLevel(dimension);
                        if (world != null && world != player.level()) sendWorldInfo(player, world);
                    }

                }
            }
            event.getServer().getProfiler().pop();

        });
    }

    //send the daytime and weather info to player when player is in nether
    public static void sendWorldInfo(ServerPlayer player, ServerLevel world) {
        ResourceKey<Level> remoteDimension = world.dimension();

        PacketRedirection.sendRedirectedMessage(
                player,
                remoteDimension,
                new ClientboundSetTimePacket(
                        world.getGameTime(),
                        world.getDayTime(),
                        world.getGameRules().getBoolean(
                                GameRules.RULE_DAYLIGHT
                        )
                )
        );

        /**{@link net.minecraft.client.network.ClientPlayNetworkHandler#onGameStateChange(GameStateChangeS2CPacket)}*/

        if (world.isRaining()) {
            PacketRedirection.sendRedirectedMessage(
                    player,
                    world.dimension(),
                    new ClientboundGameEventPacket(
                            ClientboundGameEventPacket.START_RAINING,
                            0.0F
                    )
            );
        } else {
            //if the weather is already not raining when the player logs in then no need to sync
            //if the weather turned to not raining then elsewhere syncs it
        }

        PacketRedirection.sendRedirectedMessage(
                player,
                world.dimension(),
                new ClientboundGameEventPacket(
                        ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,
                        world.getRainLevel(1.0F)
                )
        );
        PacketRedirection.sendRedirectedMessage(
                player,
                world.dimension(),
                new ClientboundGameEventPacket(
                        ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE,
                        world.getThunderLevel(1.0F)
                )
        );
    }

}
