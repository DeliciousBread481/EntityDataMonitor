package deliciousbread481.entitydatamonitor;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Mod("entity_data_monitor")
public class EntityDataMonitorMod {
    private static final String LOGGER_NAME = "deliciousbread481.entitydatamonitor";
    private static final String APPENDER_NAME = "EntityDataMonitorFileAppender";
    private static final String HANDLER_NAME = "entity_data_monitor";

    private static final Logger LOGGER;

    static {
        String logFilePath = FMLPaths.GAMEDIR.get()
                .resolve("EntityDataMonitor.log")
                .toAbsolutePath()
                .toString();

        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();

        PatternLayout layout = PatternLayout.newBuilder()
                .withPattern("[%d{yyyy-MM-dd HH:mm:ss.SSS}] [%t] [%level] %msg%n")
                .withConfiguration(config)
                .build();

        FileAppender appender = FileAppender.newBuilder()
                .withFileName(logFilePath)
                .withAppend(true)
                .withName(APPENDER_NAME)
                .withLayout(layout)
                .setConfiguration(config)
                .build();
        appender.start();
        config.addAppender(appender);

        AppenderRef ref = AppenderRef.createAppenderRef(APPENDER_NAME, null, null);
        LoggerConfig loggerConfig = LoggerConfig.createLogger(
                false,
                Level.ALL,
                LOGGER_NAME,
                "true",
                new AppenderRef[]{ref},
                null,
                config,
                null
        );
        loggerConfig.addAppender(appender, null, null);
        config.addLogger(LOGGER_NAME, loggerConfig);
        ctx.updateLoggers();

        LOGGER = LoggerFactory.getLogger(LOGGER_NAME);
    }

    public EntityDataMonitorMod() {
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLogin);
        LOGGER.info("EntityDataMonitor 已启动，日志文件: {}",
                FMLPaths.GAMEDIR.get().resolve("EntityDataMonitor.log").toAbsolutePath());
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        Channel channel = sp.connection.connection.channel();
        if (channel.pipeline().get(HANDLER_NAME) == null) {
            channel.pipeline().addBefore(
                    "packet_handler",
                    HANDLER_NAME,
                    new EntityDataMonitorHandler(
                            sp.getStringUUID(),
                            sp.getName().getString()
                    )
            );
            LOGGER.info("已为玩家 {} 注入监视器", sp.getName().getString());
        }
    }

    private static class EntityDataMonitorHandler extends ChannelOutboundHandlerAdapter {

        private final String playerUUID;
        private final String playerName;

        EntityDataMonitorHandler(String uuid, String name) {
            this.playerUUID = uuid;
            this.playerName = name;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            try {
                if (msg instanceof ClientboundBundlePacket bundle) {
                    for (Packet<?> subPacket : bundle.subPackets()) {
                        if (subPacket instanceof ClientboundSetEntityDataPacket pkt) {
                            logPacket(pkt);
                        }
                    }
                } else if (msg instanceof ClientboundSetEntityDataPacket pkt) {
                    logPacket(pkt);
                }
            } catch (Exception e) {
                LOGGER.error("记录包时出错", e);
            }
            super.write(ctx, msg, promise);
        }

        private void logPacket(ClientboundSetEntityDataPacket pkt) {
            StringBuilder sb = new StringBuilder();
            sb.append("\n[EntityDataMonitor] 玩家=").append(playerName)
                    .append('(').append(playerUUID).append(')')
                    .append(" 实体ID=").append(pkt.id()).append('\n');

            List<SynchedEntityData.DataValue<?>> items = pkt.packedItems();
            if (items == null) {
                sb.append("  !! packedItems 为 NULL !!\n");
            } else {
                sb.append("  DataValue 数量=").append(items.size()).append('\n');
                for (SynchedEntityData.DataValue<?> dv : items) {
                    int serializerId = EntityDataSerializers.getSerializedId(dv.serializer());
                    sb.append("  [accessorId=").append(dv.id()).append(']')
                            .append(" serializerId=").append(serializerId);

                    if (serializerId < 0) {
                        sb.append(" !! 未注册的序列化器 !!");
                    }

                    sb.append(" serializerClass=")
                            .append(dv.serializer().getClass().getName())
                            .append(" value=");

                    try {
                        sb.append(dv.value());
                    } catch (Exception e) {
                        sb.append("<读取 value 时出错: ").append(e.getMessage()).append('>');
                    }
                    sb.append('\n');
                }
            }
            LOGGER.warn(sb.toString());
        }
    }
}