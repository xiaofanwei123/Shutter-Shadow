package com.xfw.shuttershadow.network;


import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import com.xfw.shuttershadow.util.CHelper;
import com.xfw.shuttershadow.util.WorldContextHelper;
import com.xfw.shuttershadow.mixin.minecraft.server.IEServerConfigurationPacketListenerImpl;
import com.xfw.shuttershadow.core.CoreConfig;
import com.xfw.shuttershadow.core.PlatformBridge;

import java.util.function.Consumer;

/** CONFIGURATION阶段协商嵌入内核协议版本，随后允许进入PLAY。 */
public class CoreNetworkHandshake {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    /** major/minor/patch协议版本记录，OTHER标记无法按标准解析的版本。 */
    public static record ModVersion(
        int major, int minor, int patch
    ) {
        // 开发环境的非标准版本标记。
        public static final ModVersion OTHER = new ModVersion(0, 0, 0);
        
        /** 读三个VarInt组成版本记录。 */
        public static ModVersion read(FriendlyByteBuf buf) {
            int major = buf.readVarInt();
            int minor = buf.readVarInt();
            int patch = buf.readVarInt();
            return new ModVersion(major, minor, patch);
        }
        
        /** 按major、minor、patch顺序写VarInt。 */
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(major);
            buf.writeVarInt(minor);
            buf.writeVarInt(patch);
        }
        
        /** 以三段点分格式展示版本。 */
        @Override
        public String toString() {
            return "%d.%d.%d".formatted(major, minor, patch);
        }
        
        /** 判断是否不是OTHER哨兵版本。 */
        public boolean isNormalVersion() {
            return !OTHER.equals(this);
        }
        
    }
    
    public static ModVersion runtimeProtocolVersion;
    
    /** 服务端配置阶段的shuttershadow握手任务。 */
    public static record CoreConfigurationTask(
    ) implements ICustomConfigurationTask {
        public static final ConfigurationTask.Type TYPE =
            new ConfigurationTask.Type("shuttershadow:core_config");

        /** 向该连接发S2CConfigStartPacket携带服务端版本。 */
        @Override
        public void run(Consumer<CustomPacketPayload> sender) {
            sender.accept(
                    new S2CConfigStartPacket(runtimeProtocolVersion)
            );
        }
        
        /** 返回配置任务TYPE，供finishCurrentTask对应完成。 */
        @Override
        public @NotNull Type type() {
            return TYPE;
        }
    }
    
    /** 服务端发往配置阶段客户端的内核版本。 */
    public static record S2CConfigStartPacket(
        ModVersion versionFromServer
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<S2CConfigStartPacket> TYPE = new Type<>(ResourceLocation.parse("shuttershadow:config_start"));

        public static final StreamCodec<FriendlyByteBuf, S2CConfigStartPacket> CODEC = StreamCodec.of(
                (b, p) -> p.write(b), S2CConfigStartPacket::read
        );

        /** 调用ModVersion.read解码。 */
        public static S2CConfigStartPacket read(FriendlyByteBuf buf) {
            ModVersion info = ModVersion.read(buf);
            return new S2CConfigStartPacket(info);
        }
        
        /** 调用版本write编码。 */
        public void write(FriendlyByteBuf buf) {
            versionFromServer.write(buf);
        }

        /** 返回配置包TYPE。 */
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
        
        // 在客户端处理。
        // 此入口只由客户端调用。
        /** 客户端记录服务端版本并回复自身版本与客户端容忍版本差异设置。 */
        public void handle(IPayloadContext configurationPayloadContext) {
            LOGGER.info(
                "Client received Shuttershadow runtime config. Server protocol version: {}", versionFromServer
            );
            
            serverVersion = versionFromServer;
            configurationPayloadContext.reply(new C2SConfigCompletePacket(
                    runtimeProtocolVersion, CoreConfig.CLIENT_TOLERANT_VERSION_MISMATCH_WITH_SERVER.get()
            ));
        }
    }
    
    /** 客户端回复自身内核版本及容忍主/次版本差异设置。 */
    public record C2SConfigCompletePacket(
        ModVersion versionFromClient,
        boolean clientTolerantVersionMismatch
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<C2SConfigCompletePacket> TYPE = new Type<>(ResourceLocation.parse("shuttershadow:config_complete"));
        public static final StreamCodec<FriendlyByteBuf, C2SConfigCompletePacket> CODEC = StreamCodec.of(
                (b, p) -> p.write(b), C2SConfigCompletePacket::read
        );

        /** 读取版本及容忍标记。 */
        public static C2SConfigCompletePacket read(FriendlyByteBuf buf) {
            ModVersion info = ModVersion.read(buf);
            boolean clientTolerantVersionMismatch = buf.readBoolean();
            return new C2SConfigCompletePacket(info, clientTolerantVersionMismatch);
        }

        /** 写版本后写容忍标记。 */
        public void write(FriendlyByteBuf buf) {
            versionFromClient.write(buf);
            buf.writeBoolean(clientTolerantVersionMismatch);
        }

        /** 返回配置包TYPE。 */
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
        
        // 在服务端处理。
        /** 双方版本正常且major/minor不同、双方均不容忍时断开。 */
        public void handle(IPayloadContext configurationPayloadContext
        ) {
           // TODO @Nick1st - This method body must be synced with the fabric system
            if (versionFromClient.isNormalVersion() && runtimeProtocolVersion.isNormalVersion()) {
                if ((versionFromClient.major != runtimeProtocolVersion.major ||
                    versionFromClient.minor != runtimeProtocolVersion.minor) &&
                    !CoreConfig.SERVER_TOLERANT_VERSION_MISMATCH_WITH_CLIENT.get() &&
                    !clientTolerantVersionMismatch
                ) {
                    configurationPayloadContext.disconnect(Component.translatable(
                        "shuttershadow.core.mod_major_minor_version_mismatch",
                        runtimeProtocolVersion.toString(),
                        versionFromClient.toString()
                    ));
                    LOGGER.info(
                        """
                            Disconnecting client because of Shuttershadow protocol version difference (only patch version difference is tolerated).
                            Game Profile:
                            Client runtime protocol version: {}
                            Server runtime protocol version: {}""",
                        versionFromClient, runtimeProtocolVersion
                    );
                    return;
                }
            }

            configurationPayloadContext.finishCurrentTask(CoreConfigurationTask.TYPE);
        }
    }
    
    /** 取得内核版本并注册配置任务。 */
    public static void init(IEventBus eventBus) {
        runtimeProtocolVersion = PlatformBridge.getCoreProtocolVersion();
        
        LOGGER.info("Shuttershadow dimension protocol version {}", runtimeProtocolVersion);

        // TODO @Nick1st - Check that this fixes Server login
        eventBus.addListener(RegisterConfigurationTasksEvent.class, (event) -> {
            if (event.getListener().getConnectionType().isNeoForge()) {
                event.register(new CoreConfigurationTask());
            }
            else {
                if (FMLEnvironment.dist.isDedicatedServer()) {
                    if (CoreConfig.SERVER_REJECT_CLIENT_WITHOUT_SHUTTERSHADOW.get()) {
                        // 此处不能使用翻译键，
                        // 因为未安装本模组的客户端没有对应翻译。
                        event.getListener().disconnect(Component.literal(
                                """
                                    The server detected that the client does not support Shuttershadow's dimension protocol.
                                    This server requires Shuttershadow on the client.

                                    (If another mod interferes with network capability detection, check the server setting `serverRejectClientWithoutShuttershadow` in `config/shuttershadow-core.toml`.)
                                    """
                        ));
                    }
                    else {
                        GameProfile gameProfile =
                                ((IEServerConfigurationPacketListenerImpl) event.getListener()).ip_getGameProfile();

                        LOGGER.warn(
                                "NeoForge detected that the client does not support Shuttershadow's dimension protocol. {} {}",
                                gameProfile.getName(), gameProfile.getId()
                        );
                    }
                }
                // TODO @Nick1st Check if this problem also exist at NEO
                else {
                    LOGGER.error("Shuttershadow dimension configuration is unavailable on this integrated-server connection.");
                }
            }
        });
    }
    
    // 此入口只由客户端调用。
    /** 客户端登录时检查服务端协议，登出清掉serverVersion。 */
    public static void initClient() {
        // 只在连接结束时清理握手，原版重生与换维继续沿用当前连接的状态。
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> serverVersion = null);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, (event) -> onClientJoin());
    }
    
    /** 服务端版本不存在时提醒缺失内核。 */
    private static void onClientJoin() {
        if (serverVersion == null) {
            warnServerMissingDimensionRuntime();
        }
        else {
            if (serverVersion.isNormalVersion() &&
                runtimeProtocolVersion.isNormalVersion() &&
                !serverVersion.equals(runtimeProtocolVersion)
            ) {
                if (CoreConfig.shouldDisplayWarning("mod_version_mismatch")) {
                    MutableComponent text =
                        Component.translatable(
                            "shuttershadow.core.mod_patch_version_mismatch",
                            Component.literal(serverVersion.toString())
                                .withStyle(ChatFormatting.GOLD),
                            Component.literal(runtimeProtocolVersion.toString())
                                .withStyle(ChatFormatting.GOLD)
                        ).append(
                            WorldContextHelper.getDisableWarningText("mod_version_mismatch")
                        );
                    CHelper.printChat(text);
                }
            }
        }
    }
    
    // 客户端使用的握手状态。
    private static @Nullable CoreNetworkHandshake.ModVersion serverVersion = null;
    
    // 应由客户端调用。
    /** 返回握手是否已收到服务端内核版本。 */
    public static boolean doesServerHaveDimensionRuntime() {
        return serverVersion != null;
    }
    
    /** 排入客户端主线程显示服务端缺失维度内核提示。 */
    private static void warnServerMissingDimensionRuntime() {
        Minecraft.getInstance().execute(() -> {
            CHelper.printChat(Component.translatable("shuttershadow.core.server_missing_immptl"));
        });
    }
}
