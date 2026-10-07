package com.xfw.shuttershadow.loot;

import com.mojang.serialization.MapCodec;
import com.xfw.shuttershadow.Shuttershadow;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.structures.EndCityPieces;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParam;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

import java.util.Set;

/** 仅允许末地城船结构中的宝箱追加安全传送附魔书。 */
public record EndShipChestCondition() implements LootItemCondition {
    public static final MapCodec<EndShipChestCondition> CODEC = MapCodec.unit(new EndShipChestCondition());

    /** 返回本模组注册的末地船宝箱条件类型。 */
    @Override
    public LootItemConditionType getType() {
        return Shuttershadow.END_SHIP_CHEST_CONDITION.get();
    }

    /** 声明需要宝箱的世界坐标。 */
    @Override
    public Set<LootContextParam<?>> getReferencedContextParams() {
        return Set.of(LootContextParams.ORIGIN);
    }

    /** 核对宝箱所在结构段，普通末地城宝箱不满足此条件。 */
    @Override
    public boolean test(LootContext context) {
        var origin = context.getParamOrNull(LootContextParams.ORIGIN);
        if (origin == null) {
            return false;
        }
        var level = context.getLevel();
        BlockPos position = BlockPos.containing(origin);
        var endCity = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .getOrThrow(BuiltinStructures.END_CITY);
        StructureStart start = level.structureManager().getStructureAt(position, endCity);
        if (!start.isValid()) {
            return false;
        }
        for (var piece : start.getPieces()) {
            if (piece instanceof EndCityPieces.EndCityPiece && piece.getBoundingBox().isInside(position)
                    && piece.createTag(StructurePieceSerializationContext.fromLevel(level))
                            .getString("Template").equals("ship")) {
                return true;
            }
        }
        return false;
    }
}
