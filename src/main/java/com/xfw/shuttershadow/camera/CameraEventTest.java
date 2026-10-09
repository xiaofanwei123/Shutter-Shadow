package com.xfw.shuttershadow.camera;

import com.xfw.shuttershadow.Shuttershadow;
import com.xfw.shuttershadow.api.CameraCaptureContext;
import com.xfw.shuttershadow.api.event.CameraCaptureEvent;
import com.xfw.shuttershadow.api.event.CameraFrameEvent;
import com.xfw.shuttershadow.api.event.CameraImageEvent;
import com.xfw.shuttershadow.api.event.CameraSubjectsEvent;
import com.xfw.shuttershadow.api.event.CameraTransferEvent;
import com.xfw.shuttershadow.api.event.CameraViewEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 在游戏聊天栏显示相机事件的实际顺序，仅输出测试消息，不修改拍摄和传送。 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class CameraEventTest {
    public static final boolean ENABLED = true;
    private static final Map<UUID, Integer> SERVER_VIEW_TICKS = new HashMap<>();

    /** 禁止创建测试监听器实例，事件由 NeoForge 自动注册。 */
    private CameraEventTest() {}

    /** 显示服务端观察会话打开。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerOpen(CameraViewEvent.Open event) { showServerView(event); }

    /** 限频显示服务端观察更新。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(CameraViewEvent.Tick event) { showServerView(event); }

    /** 显示服务端观察会话关闭。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerClose(CameraViewEvent.Close event) { showServerView(event); }

    /** 共用观察消息输出，抽象父事件只用于内部方法。 */
    private static void showServerView(CameraViewEvent event) {
        if (!ENABLED || event.getContext().side() != CameraViewEvent.Side.SERVER) return;
        String text = describeView(event, SERVER_VIEW_TICKS);
        if (text != null && event.getContext().player() instanceof ServerPlayer player) {
            send(player, text);
        }
    }

    /** 显示拍摄前的最终计划，取消时也通知。 */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onBeforeCapture(CameraCaptureEvent.Before event) {
        send(event.getContext().getExecutor(), shot(event.getContext()) + "拍摄前："
                + (event.isCanceled() ? "已取消" : "已受理") + "，成片=" + event.getPlan().getPhotoOutput()
                + "，照片维度=" + event.getPlan().getPhotoDimension()
                + "，玩家传送=" + yes(event.getPlan().isPlayerTransfer())
                + "，生物传送=" + yes(event.getPlan().isMobTransfer()));
    }

    /** 显示照片对象、玩家传送对象和生物传送对象的选择结果。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSubjects(CameraSubjectsEvent event) {
        String purpose = switch (event.getPurpose()) {
            case PHOTO -> "照片对象";
            case PLAYER_TRANSFER -> "玩家传送对象";
            case MOB_TRANSFER -> "生物传送对象";
        };
        send(event.getContext().getExecutor(), shot(event.getContext()) + "对象选择：" + purpose
                + "，候选=" + event.getCandidates().size() + "，选中=" + event.getSubjects().size());
    }

    /** 显示帧数据生成阶段，曝光失效仍能看到此事件。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFrame(CameraFrameEvent event) {
        send(event.getContext().getExecutor(), shot(event.getContext()) + "帧数据已生成："
                + event.getFrame().identifier().id());
    }

    /** 显示每个传送主体的目的地和取消状态。 */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onBeforeTransfer(CameraTransferEvent.Before event) {
        ServerPlayer player = recipient(event);
        if (player != null) send(player, "传送前：" + event.getEntity().getName().getString()
                + " → " + event.getTargetLevel().dimension().location()
                + "，已取消=" + yes(event.isCanceled()));
    }

    /** 显示真实传送结果，取消和失败也通知。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onAfterTransfer(CameraTransferEvent.After event) {
        ServerPlayer player = recipient(event);
        if (player != null) send(player, "传送后：" + event.getEntity().getName().getString()
                + "，结果=" + event.getResult());
    }

    /** 显示整次拍摄终态及实际收图、写卷和成功传送数量。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCompleted(CameraCaptureEvent.Completed event) {
        String result = switch (event.getResult()) {
            case IMAGE_RECEIVED -> "已完成";
            case NO_IMAGE -> "已完成（免成片）";
            case CANCELED -> "已取消";
            case FAILED -> "失败";
        };
        send(event.getContext().getExecutor(), shot(event.getContext()) + "拍摄" + result
                + "，收到图片=" + yes(event.hasImage()) + "，写入胶卷=" + yes(event.wasFilmWritten())
                + "，传送数量=" + event.getTransferredEntities().size()
                + (event.getFailureReason().isEmpty() ? "" : "，原因=" + event.getFailureReason()));
    }

    /** 观察消息首次立即显示，此后每二十个玩家刻显示一次，关闭后清除计数。 */
    private static @Nullable String describeView(CameraViewEvent event, Map<UUID, Integer> ticks) {
        var context = event.getContext();
        UUID player = context.player().getUUID();
        String action;
        if (event instanceof CameraViewEvent.Open) {
            ticks.remove(player);
            action = "已打开";
        } else if (event instanceof CameraViewEvent.Tick) {
            int current = context.player().tickCount;
            Integer last = ticks.get(player);
            if (last != null && current >= last && current - last < 20) return null;
            ticks.put(player, current);
            action = "观察中";
        } else if (event instanceof CameraViewEvent.Close closed) {
            ticks.remove(player);
            return "已关闭，原因=" + closed.getReason();
        } else {
            return null;
        }
        return action + "，" + (context.cameraStandId() < 0 ? "手持" : "支架")
                + "，模式=" + (context.mode() == CameraViewEvent.Mode.SELFIE ? "自拍" : "远景")
                + "，观察维度=" + (context.targetDimension() == null ? "当前维度" : context.targetDimension());
    }

    /** 为同一拍摄显示短编号和触发入口，便于核对连续照片的顺序。 */
    private static String shot(CameraCaptureContext context) {
        String trigger = switch (context.getTrigger()) {
            case HANDHELD -> "手持";
            case MANUAL_STAND -> "手动支架";
            case REDSTONE -> "红石支架";
        };
        return "[" + trigger + "/" + context.getShotId().toString().substring(0, 8) + "] ";
    }

    /** 传送消息发给本次拍摄执行者，无拍摄上下文时发给被传送玩家。 */
    private static @Nullable ServerPlayer recipient(CameraTransferEvent event) {
        if (event.getContext() != null) return event.getContext().getExecutor();
        return event.getEntity() instanceof ServerPlayer player ? player : null;
    }

    /** 将布尔值显示为中文。 */
    private static String yes(boolean value) { return value ? "是" : "否"; }

    /** 服务端只向相关玩家发送聊天消息。 */
    private static void send(ServerPlayer player, String text) {
        if (ENABLED) player.sendSystemMessage(Component.literal("[相机事件测试·服务端] " + text));
    }

    /** 单独注册客户端事件，专用服务端不加载图片与客户端游戏类。 */
    @EventBusSubscriber(modid = Shuttershadow.MODID, value = Dist.CLIENT)
    public static final class Client {
        private static final Map<UUID, Integer> VIEW_TICKS = new HashMap<>();

        /** 禁止创建客户端测试实例。 */
        private Client() {}

        /** 显示客户端取景器打开。 */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void onOpen(CameraViewEvent.Open event) { showView(event); }

        /** 限频显示客户端观察更新。 */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void onTick(CameraViewEvent.Tick event) { showView(event); }

        /** 显示客户端取景器关闭。 */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void onClose(CameraViewEvent.Close event) { showView(event); }

        /** 共用客户端观察输出，监听器只注册具体事件。 */
        private static void showView(CameraViewEvent event) {
            if (!ENABLED || event.getContext().side() != CameraViewEvent.Side.CLIENT) return;
            String text = describeView(event, VIEW_TICKS);
            if (text != null) send(text);
        }

        /** 显示原始图片就绪，免成片不会产生此消息。 */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void onImage(CameraImageEvent.Ready event) {
            send("图片已生成（颜色处理前）：" + event.getExposureId());
        }

        /** 向本地玩家聊天栏输出，不广播到其他玩家。 */
        private static void send(String text) {
            var player = Minecraft.getInstance().player;
            if (ENABLED && player != null) {
                player.displayClientMessage(Component.literal("[相机事件测试·客户端] " + text), false);
            }
        }
    }
}
