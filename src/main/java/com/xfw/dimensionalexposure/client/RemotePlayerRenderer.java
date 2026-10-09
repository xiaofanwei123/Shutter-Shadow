package com.xfw.dimensionalexposure.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.xfw.dimensionalexposure.access.IEWorldRenderer;
import com.xfw.dimensionalexposure.core.render.WorldRenderInfo;
import net.neoforged.neoforge.client.model.pipeline.VertexConsumerWrapper;

/** 远景与照片共用源世界玩家的半透明投影，不移动真实玩家。 */
public final class RemotePlayerRenderer {
    private static final int GHOST_ALPHA = 102;
    /** 禁止实例化此工具类。 */
    private RemotePlayerRenderer() {}

    /** 仅在WorldRenderInfo目标渲染中取得投影玩家，逐个绘制。 */
    public static void render(LevelRenderer levelRenderer, DeltaTracker deltaTracker, PoseStack poseStack,
                               MultiBufferSource bufferSource) {
        if (!WorldRenderInfo.isRendering()) return;
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        WorldRenderInfo renderInfo = WorldRenderInfo.getTopRenderInfo();
        IEWorldRenderer remoteRenderer = (IEWorldRenderer) (Object) levelRenderer;
        MultiBufferSource ghostBufferSource = ghostBuffers(bufferSource);
        for (ImmersiveCameraClient.PlayerProjection projection :
                ImmersiveCameraClient.playerProjections(renderInfo.world, partialTick)) {
            renderProjection(projection, remoteRenderer, partialTick, poseStack, ghostBufferSource);
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
            AABB box = projection.player().getBoundingBoxForCulling()
                    .move(position.subtract(projection.player().position())).inflate(0.5D);
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

    /** 使用原皮肤、装备及手持物纹理，仅把此次投影的顶点颜色与材质改为半透明。 */
    private static MultiBufferSource ghostBuffers(MultiBufferSource buffers) {
        return type -> {
            boolean entityGeometry = type.format() == DefaultVertexFormat.NEW_ENTITY
                    || type.format() == DefaultVertexFormat.BLOCK;
            if (!entityGeometry || !(type instanceof RenderType.CompositeRenderType composite)) {
                return buffers.getBuffer(type);
            }
            var texture = composite.state.textureState.cutoutTexture();
            if (texture.isEmpty()) return buffers.getBuffer(type);
            // 保留剔除，第一人称镜头位于自己的头部时不会被头部内侧铺满画面。
            VertexConsumer consumer = buffers.getBuffer(RenderType.entityTranslucentCull(texture.get()));
            return new VertexConsumerWrapper(consumer) {
                @Override
                public VertexConsumer addVertex(float x, float y, float z) {
                    parent.addVertex(x, y, z).setOverlay(OverlayTexture.NO_OVERLAY);
                    return this;
                }

                @Override
                public VertexConsumer setColor(int red, int green, int blue, int alpha) {
                    parent.setColor(red, green, blue, alpha * GHOST_ALPHA / 255);
                    return this;
                }
            };
        };
    }
}
