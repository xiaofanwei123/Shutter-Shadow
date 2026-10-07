package com.xfw.shuttershadow.network;

import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/** 在登录位置包解码前确认连接就绪，兼容性由 NeoForge 必需通道协商决定。 */
public final class CoreNetworkHandshake {
    private static volatile @Nullable Connection serverConnection;

    /** 禁止实例化此工具类。 */
    private CoreNetworkHandshake() {}

    /** 配置阶段等待客户端记录真实连接，再允许进入游戏阶段。 */
    public record ConfigurationReadyTask() implements ICustomConfigurationTask {
        public static final ConfigurationTask.Type TYPE =
                new ConfigurationTask.Type("shuttershadow:network_ready");

        /** 发送不携带版本或配置数据的就绪请求。 */
        @Override
        public void run(Consumer<CustomPacketPayload> sender) {
            sender.accept(new ReadyS2C());
        }

        /** 返回就绪任务标识，供客户端回执完成任务。 */
        @Override
        public Type type() { return TYPE; }
    }

    /** 客户端收到后保存配置阶段连接，避免首次登录依赖尚未创建的玩家对象。 */
    public record ReadyS2C() implements CustomPacketPayload {
        public static final Type<ReadyS2C> TYPE =
                new Type<>(ResourceLocation.parse("shuttershadow:network_ready"));
        public static final StreamCodec<FriendlyByteBuf, ReadyS2C> CODEC =
                StreamCodec.unit(new ReadyS2C());

        /** 返回客户端就绪包标识。 */
        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        /** 保存实际连接后回复空回执，不比较内核版本。 */
        public void handle(IPayloadContext context) {
            serverConnection = context.connection();
            context.reply(new ReadyC2S());
        }
    }

    /** 客户端已完成位置包解码前的连接准备。 */
    public record ReadyC2S() implements CustomPacketPayload {
        public static final Type<ReadyC2S> TYPE =
                new Type<>(ResourceLocation.parse("shuttershadow:network_ready_ack"));
        public static final StreamCodec<FriendlyByteBuf, ReadyC2S> CODEC =
                StreamCodec.unit(new ReadyC2S());

        /** 返回服务端就绪回执标识。 */
        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        /** 收到回执后完成配置任务，后续登录沿用原版流程。 */
        public void handle(IPayloadContext context) {
            context.finishCurrentTask(ConfigurationReadyTask.TYPE);
        }
    }

    /** 仅对已协商就绪通道的连接注册准备任务，不额外拒绝或容忍版本差异。 */
    public static void init(IEventBus eventBus) {
        // TODO @Nick1st - Check that this fixes Server login
        eventBus.addListener(RegisterConfigurationTasksEvent.class, event -> {
            if (event.getListener().hasChannel(ReadyS2C.TYPE)
                    && event.getListener().hasChannel(ReadyC2S.TYPE)) {
                event.register(new ConfigurationReadyTask());
            }
        });
    }

    /** 只在退出连接时清理，重生与无缝换维不清除连接能力。 */
    public static void initClient() {
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class,
                event -> serverConnection = null);
    }

    /** 从当前存活连接的 NeoForge 游戏通道能力决定是否读写扩展位置协议。 */
    public static boolean doesServerHaveDimensionRuntime() {
        Connection connection = serverConnection;
        return connection != null && connection.isConnected()
                && NetworkRegistry.hasChannel(connection, ConnectionProtocol.PLAY,
                PacketRedirection.Payload.TYPE.id());
    }
}
