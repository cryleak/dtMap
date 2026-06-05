package com.ricedotwho.dtmap.mixin;

import com.ricedotwho.dtmap.events.ChatEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientPacketListener.class, priority = Integer.MAX_VALUE)
public class ClientPacketListenerPriorityMixin {
    @Inject(method = "handleSystemChat(Lnet/minecraft/network/protocol/game/ClientboundSystemChatPacket;)V", at = @At("HEAD"))
    void handleSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().packetProcessor().isSameThread()) return;

        var component = packet.content();
        var message = component.getString();
        var unformatted = ChatFormatting.stripFormatting(message);
        ChatEvents.INSTANCE.getSYSTEM_MESSAGE_CLIENT_EVENT().invoker().receiveSystemMessage(new ChatEvents.SystemMessageEvent.Data(component, message, unformatted));
    }
}
