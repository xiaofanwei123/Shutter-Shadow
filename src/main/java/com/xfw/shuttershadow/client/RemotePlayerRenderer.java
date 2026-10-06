package com.xfw.shuttershadow.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
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

/**
 * 在 Immersive Portals 的目标世界渲染中绘制所有出镜来源玩家。
 *
 * <p>This is a render-only projection. The player is not inserted into the
 * target {@code ClientLevel}, and no entity or movement packet is generated.
 * 复用源世界真实 {@code AbstractClientPlayer} 的渲染器，保留已同步的皮肤、装备、姿势和动画。</p>
 */
public final class RemotePlayerRenderer {
    private RemotePlayerRenderer() {}

    public static void render(LevelRenderer levelRenderer, Camera camera,
                               DeltaTracker deltaTracker, PoseStack poseStack,
                               MultiBufferSource bufferSource) {
        // LevelRenderer also runs during world join and ordinary vanilla frames.
        // IP only owns a WorldRenderInfo stack while a remote pass is active;
        // getTopRenderInfo() throws when that stack is empty.
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

    /** 只由目标视锥和目标渲染深度决定可见像素，跳过一人不影响其余投影。 */
    private static void renderProjection(ImmersiveCameraClient.PlayerProjection projection,
                                         IEWorldRenderer remoteRenderer,
                                         float partialTick, PoseStack poseStack,
                                         MultiBufferSource bufferSource) {
        Vec3 position = projection.position();

        // The target renderer has already prepared this frustum for the
        // current remote camera. Use the mapped player's target-world box so
        // a stand does not draw the operator when he is outside the view.
        Frustum frustum = remoteRenderer.portal_getFrustum();
        if (frustum != null) {
            //TODO:是否增加原版额外的 0.5 格剔除余量。
            AABB box = projection.player().getBoundingBoxForCulling()
                    // .inflate(0.5D)
                    .move(position.subtract(projection.player().position()));
            if (!frustum.isVisible(box)) return;
        }

        EntityRenderDispatcher dispatcher = remoteRenderer.ip_getEntityRenderDispatcher();
        EntityRenderer<? super AbstractClientPlayer> entityRenderer =
                dispatcher.getRenderer(projection.player());
        if (entityRenderer == null) return;

        // EntityRenderDispatcher.render() also emits a shadow using the
        // entity's source-world position. Calling the native renderer directly
        // keeps the model and animation while avoiding a source-world shadow.
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
