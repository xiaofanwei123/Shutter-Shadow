package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** 客户端 ClientLevel 地图数据访问器，给多世界切换/创建共享原地图状态。 */
@Mixin(ClientLevel.class)
public interface IEClientLevel_Accessor {
    
    /** Accessor 读取 mapData 映射，返回实际引用。 */
    @Accessor("mapData")
    Map<MapId, MapItemSavedData> ip_getMapData();
    
    /** Mutable Accessor 替换 mapData 引用。 */
    @Mutable
    @Accessor("mapData")
    void ip_setMapData(Map<MapId, MapItemSavedData> mapData);
}
