package com.xfw.shuttershadow.mixin.minecraft.client;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.access.IEClientWorld;
import com.xfw.shuttershadow.core.chunk_loading.RemoteClientChunkMap;

import java.util.function.Supplier;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel implements IEClientWorld {
    
    @Mutable
    @Shadow
    @Final
    private ClientChunkCache chunkSource;
    
    @Mutable
    @Shadow
    @Final
    private LevelRenderer levelRenderer;
    
    @Shadow
    @Final
    private BlockStatePredictionHandler blockStatePredictionHandler;
    
    @Shadow
    @Final
    @Mutable
    private TickRateManager tickRateManager;
    
    //use my client chunk manager
    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    void onConstructed(
        ClientPacketListener clientPacketListener, ClientLevel.ClientLevelData clientLevelData,
        ResourceKey resourceKey, Holder holder, int loadDistance, int j, Supplier supplier,
        LevelRenderer levelRenderer, boolean bl, long l, CallbackInfo ci
    ) {
        ClientLevel clientWorld = (ClientLevel) (Object) this;
        chunkSource = new RemoteClientChunkMap(clientWorld);
    }
    
    // avoid entity duplicate when an entity travels
    @Inject(
        method = "addEntity",
        at = @At("TAIL")
    )
    private void onOnEntityAdded(Entity entityIn, CallbackInfo ci) {
        if (ClientWorldLoader.getIsInitialized()) {
            for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
                if (world != (Object) this) {
                    world.removeEntity(entityIn.getId(), Entity.RemovalReason.DISCARDED);
                }
            }
        }
    }
    
    /**
     * If the player goes into a portal when the other side chunk is not yet loaded
     * freeze the player so the player won't drop
     * {@link net.minecraft.client.player.LocalPlayer#tick()}
     */
    @Inject(
        method = "Lnet/minecraft/client/multiplayer/ClientLevel;hasChunk(II)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onHasChunk(int chunkX, int chunkZ, CallbackInfoReturnable<Boolean> cir) {
        LevelChunk chunk = chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null || chunk instanceof EmptyLevelChunk) {
            cir.setReturnValue(false);
        }
    }
    
    // for debug
    @Inject(method = "Lnet/minecraft/client/multiplayer/ClientLevel;toString()Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private void onToString(CallbackInfoReturnable<String> cir) {
        ClientLevel this_ = (ClientLevel) (Object) this;
        cir.setReturnValue("ClientWorld " + this_.dimension().location());
    }
    
    @Override
    public void ip_resetWorldRendererRef() {
        levelRenderer = null;
    }
    
    @Override
    public BlockStatePredictionHandler ip_getBlockStatePredictionHandler() {
        return blockStatePredictionHandler;
    }
    
    @Override
    public void ip_setTickRateManager(TickRateManager cond) {
        tickRateManager = cond;
    }
}
