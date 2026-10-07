package com.xfw.shuttershadow.client;

import com.mojang.datafixers.util.Either;
import io.github.mortuusars.exposure.world.item.camera.Attachment;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;

import java.util.List;

/** 在相机原生物品提示框中只读显示胶卷和附件槽位。 */
public final class CameraAttachmentTooltip implements TooltipComponent, ClientTooltipComponent {
    private static final int SLOT_COLOR = 0xFF211A23;
    private final List<ItemStack> items;

    /** 保存当前附件的显示快照，不修改相机或其中的物品。 */
    private CameraAttachmentTooltip(ItemStack camera, List<Attachment<?>> attachments) {
        items = attachments.stream().map(attachment -> attachment.get(camera).getForReading().copy()).toList();
    }

    /** 注册原生提示框组件的客户端绘制工厂。 */
    public static void register(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(CameraAttachmentTooltip.class, component -> component);
    }

    /** 在相机名称后加入槽位，保留其余提示及原来的右键操作。 */
    public static void gather(RenderTooltipEvent.GatherComponents event) {
        ItemStack camera = event.getItemStack();
        if (!(camera.getItem() instanceof CameraItem item)
                || camera.has(DataComponents.HIDE_TOOLTIP)
                || camera.has(DataComponents.HIDE_ADDITIONAL_TOOLTIP)) return;
        List<Attachment<?>> attachments = item.getAttachments();
        if (attachments.isEmpty()) return;
        var elements = event.getTooltipElements();
        if (elements.stream().anyMatch(element -> element.right()
                .filter(CameraAttachmentTooltip.class::isInstance).isPresent())) return;
        elements.add(Math.min(1, elements.size()), Either.right(new CameraAttachmentTooltip(camera, attachments)));
    }

    /** 为十八像素槽位和底部留白分配高度。 */
    @Override
    public int getHeight() {
        return 22;
    }

    /** 按相机实际附件数量计算槽位行宽。 */
    @Override
    public int getWidth(Font font) {
        return items.size() * 20 - 2;
    }

    /** 绘制无图案的深色槽位、已装物品及数量和胶卷进度。 */
    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
        for (int index = 0; index < items.size(); index++) {
            int slotX = x + index * 20;
            ItemStack stack = items.get(index);
            graphics.fill(slotX + 1, y, slotX + 17, y + 18, SLOT_COLOR);
            graphics.fill(slotX, y + 1, slotX + 18, y + 17, SLOT_COLOR);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, slotX + 1, y + 1, index);
                graphics.renderItemDecorations(font, stack, slotX + 1, y + 1);
            }
        }
    }
}
