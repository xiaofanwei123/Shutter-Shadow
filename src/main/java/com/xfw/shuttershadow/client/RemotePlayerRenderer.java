package com.xfw.shuttershadow.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.xfw.shuttershadow.access.IEWorldRenderer;
import com.xfw.shuttershadow.core.render.WorldRenderInfo;

/** 手动支架远场中绘制源世界玩家模型，不移动真实玩家。 */
public final class RemotePlayerRenderer {
    /** 禁止实例化此工具类。 */
    private RemotePlayerRenderer() {}

    /** 仅在WorldRenderInfo目标渲染中取得投影玩家，逐个绘制。 */
    public static void render(LevelRenderer levelRenderer, Camera camera,
                               DeltaTracker deltaTracker, PoseStack poseStack,
                               MultiBufferSource bufferSource) {
        if (!WorldRenderInfo.isRendering()) return;
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        WorldRenderInfo renderInfo = WorldRenderInfo.getTopRenderInfo();
        if (renderInfo == null || renderInfo.world == null) return;
        IEWorldRenderer remoteRenderer = (IEWorldRenderer) (Object) levelRenderer;
        for (ImmersiveCameraClient.PlayerProjection projection :
                ImmersiveCameraClient.playerProjections(renderInfo.world, camera, partialTick)) {
            renderProjection(projection, remoteRenderer, partialTick, poseStack, bufferSource);
        }
    }

    /** 将玩家剔除盒移动到投影位置，目标frustum剔除。 */
    private static void renderProjection(ImmersiveCameraClient.PlayerProjection projection,
                                         IEWorldRenderer remoteRenderer,
                                         float partialTick, PoseStack poseStack,
                                         MultiBufferSource bufferSource) {
        Vec3 position = projection.position();

        Frustum frustum = remoteRenderer.portal_getFrustum();
        if (frustum != null) {
            //TODO:是否增加原版额外的 0.5 格剔除余量。
            AABB box = projection.player().getBoundingBoxForCulling()
                    // 可选的额外半格剔除余量，当前未启用。
                    .move(position.subtract(projection.player().position()));
            if (!frustum.isVisible(box)) return;
        }

        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        EntityRenderer<? super AbstractClientPlayer> entityRenderer =
                dispatcher.getRenderer(projection.player());
        if (entityRenderer == null) return;
        Vec3 renderOffset = entityRenderer.getRenderOffset(projection.player(), partialTick);
        Vec3 cameraPosition = projection.cameraPosition();
        float yRot = net.minecraft.util.Mth.lerp(partialTick,
                projection.player().yRotO, projection.player().getYRot());
        int packedLight = LevelRenderer.getLightColor(projection.level(), BlockPos.containing(position));

        poseStack.pushPose();
        try {
            poseStack.translate(
                    position.x - cameraPosition.x + renderOffset.x,
                    position.y - cameraPosition.y + renderOffset.y,
                    position.z - cameraPosition.z + renderOffset.z);
            entityRenderer.render(projection.player(), yRot, partialTick,
                    poseStack, bufferSource, packedLight);
        } finally {
            poseStack.popPose();
        }
    }
}
