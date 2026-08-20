package dev.bifyp.schematicgatherer.bot;

import dev.bifyp.schematicgatherer.mixin.ConnectionAccessor;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Фальшивое соединение для бота — порт FakeClientConnection из Carpet.
 * Пакеты никуда не уходят, канал — EmbeddedChannel, чтобы isOpen() был true.
 */
public final class FakeClientConnection extends Connection {

    public FakeClientConnection(PacketFlow flow) {
        super(flow);
        // Интерфейс ConnectionAccessor подмешивается в Connection только в рантайме,
        // поэтому для javac приводим через Object (класс final, прямой каст запрещён).
        ((ConnectionAccessor) (Object) this).schematicgatherer$setChannel(new EmbeddedChannel());
    }

    @Override
    public void setReadOnly() {}

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {}

    @Override
    public void handleDisconnection() {}

    @Override
    public void setListenerForServerboundHandshake(PacketListener listener) {}

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {}
}
