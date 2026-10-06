package com.xfw.shuttershadow.network;
// Shuttershadow phase seven: relocated into the camera core.

import com.google.common.collect.ImmutableMap;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.util.McHelper;

public class MiscNetworking {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static record DimIdSyncPacket(
        CompoundTag dimTypeTag
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DimIdSyncPacket> TYPE =
                new Type<>(ResourceLocation.parse("shuttershadow:dimension_ids"));

        public static final StreamCodec<FriendlyByteBuf, DimIdSyncPacket> CODEC =
                StreamCodec.of(
                        (b, p) -> p.write(b), DimIdSyncPacket::read
                );

        public static DimIdSyncPacket createFromServer(MinecraftServer server) {
            RegistryAccess registryManager = server.registryAccess();
            Registry<DimensionType> dimensionTypes = registryManager.registryOrThrow(Registries.DIMENSION_TYPE);
            
            CompoundTag dimIdToDimTypeIdTag = new CompoundTag();
            for (ServerLevel world : server.getAllLevels()) {
                ResourceKey<Level> dimId = world.dimension();
                
                DimensionType dimType = world.dimensionType();
                ResourceLocation dimTypeId = dimensionTypes.getKey(dimType);
                
                if (dimTypeId == null) {
                    LOGGER.error("Cannot find dimension type for {}", dimId.location());
                    LOGGER.error(
                        "Registered dimension types {}", dimensionTypes.keySet()
                    );
                    dimTypeId = BuiltinDimensionTypes.OVERWORLD.location();
                }
                
                dimIdToDimTypeIdTag.putString(
                    dimId.location().toString(),
                    dimTypeId.toString()
                );
            }
            
            return new DimIdSyncPacket(dimIdToDimTypeIdTag);
        }
        
        public static DimIdSyncPacket createPacket(MinecraftServer server) {
            return DimIdSyncPacket.createFromServer(server);
        }

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeNbt(dimTypeTag);
        }

        public static DimIdSyncPacket read(FriendlyByteBuf buf) {
            CompoundTag typeTag = buf.readNbt();

            return new DimIdSyncPacket(typeTag);
        }
        // must be handled early
        // should not be handled in client main thread, otherwise it may be late
        public void handleOnNetworkingThread() {
            ImmutableMap.Builder<ResourceKey<Level>, ResourceKey<DimensionType>> builder =
                new ImmutableMap.Builder<>();
            
            for (String key : dimTypeTag.getAllKeys()) {
                ResourceKey<Level> dimId = ResourceKey.create(
                    Registries.DIMENSION,
                    McHelper.newResourceLocation(key)
                );
                String dimTypeId = dimTypeTag.getString(key);
                ResourceKey<DimensionType> dimType = ResourceKey.create(
                    Registries.DIMENSION_TYPE,
                    McHelper.newResourceLocation(dimTypeId)
                );
                builder.put(dimId, dimType);
            }
            
            var dimTypeMap = builder.build();
            ClientWorldLoader.dimIdToDimTypeId = dimTypeMap;
            LOGGER.info(
                "Client accepted dimension type mapping {}",
                dimTypeMap
            );
        }
    }
}
