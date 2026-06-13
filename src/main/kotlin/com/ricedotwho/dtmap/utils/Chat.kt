package com.ricedotwho.dtmap.utils

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style


object Chat {
    const val PREFIX = "[dtMap] "

    fun send(message: String, overlay: Boolean = false) {
        val player = Minecraft.getInstance().player ?: return
        val text: Component = Component.literal(PREFIX).append(Component.literal(message))
        if (overlay) {
            player.sendOverlayMessage(text)
        } else {
            player.sendSystemMessage(text)
        }
    }
}