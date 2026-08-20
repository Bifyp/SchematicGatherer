package dev.bifyp.schematicgatherer.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Даёт FakeClientConnection выставить channel (EmbeddedChannel), как это делает Carpet. */
@Mixin(Connection.class)
public interface ConnectionAccessor {

    @Accessor("channel")
    void schematicgatherer$setChannel(Channel channel);
}
