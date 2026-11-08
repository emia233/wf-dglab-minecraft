package com.civon.wildestfantasy.network;

import com.civon.wildestfantasy.WildestFantasy;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 被控玩家客户端 -> 服务端：上报自己配置的舒适上限与绝对上限。
 *
 * <p>主人的控制面板要显示「M 允许到什么程度」，但那是 M 本地 config 里的值，
 * 服务端只能靠这个包拿。进世界、改配置时各发一次即可。
 */
public record ReportLimitsPayload(int comfortMax, int absoluteMax) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ReportLimitsPayload> TYPE =
            new CustomPacketPayload.Type<>(WildestFantasy.id("report_limits"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReportLimitsPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.comfortMax);
                        buf.writeVarInt(p.absoluteMax);
                    },
                    buf -> new ReportLimitsPayload(buf.readVarInt(), buf.readVarInt())
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
