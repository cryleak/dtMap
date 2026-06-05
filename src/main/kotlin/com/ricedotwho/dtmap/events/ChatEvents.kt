package com.ricedotwho.dtmap.events

import net.fabricmc.fabric.api.event.EventFactory
import net.minecraft.network.chat.Component

object ChatEvents {
    fun interface SystemMessageEvent {
        data class Data(val component: Component, val message: String, val unformatted: String)

        fun receiveSystemMessage(event: Data)
    }

    val SYSTEM_MESSAGE_CLIENT_EVENT = EventFactory.createArrayBacked(SystemMessageEvent::class.java) { listeners ->
        SystemMessageEvent { event ->
            listeners.forEach {
                it.receiveSystemMessage(event)
            }
        }
    }

    val SYSTEM_MESSAGE_EVENT = EventFactory.createArrayBacked(SystemMessageEvent::class.java) { listeners ->
        SystemMessageEvent { event ->
            listeners.forEach {
                it.receiveSystemMessage(event)
            }
        }
    }
}