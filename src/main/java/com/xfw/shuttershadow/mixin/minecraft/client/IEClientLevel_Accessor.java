package com.xfw.shuttershadow.mixin.minecraft.client;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(ClientLevel.class)
public interface IEClientLevel_Accessor {
    
    @Accessor("mapData")
    Map<MapId, MapItemSavedData> ip_getMapData();
    
    @Mutable
    @Accessor("mapData")
    void ip_setMapData(Map<MapId, MapItemSavedData> mapData);
}
