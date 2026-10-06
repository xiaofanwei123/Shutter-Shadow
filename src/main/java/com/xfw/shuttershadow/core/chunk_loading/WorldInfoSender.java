package com.xfw.shuttershadow.core.chunk_loading;


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

/** 向远程维度观察者同步目标世界的时间与天气。 */
public class WorldInfoSender {
    /** 定期向玩家同步所观察的远程维度信息。 */
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

    // 玩家在下界时仍同步所观察维度的时间与天气。
    /** 按目标维度发送时间和天气状态包。 */
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

        /** 使用游戏状态变化包同步目标维度天气。 */

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
            // 玩家登录时未下雨，无需发送额外的停雨同步。
            // 天气转为停雨时，由其他同步流程发送更新。
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
