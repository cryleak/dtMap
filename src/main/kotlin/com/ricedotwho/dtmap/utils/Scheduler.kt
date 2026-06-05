package com.ricedotwho.dtmap.utils

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

object Scheduler {
    private var actions = mutableListOf<() -> Unit>()

    fun scheduleTickEnd(action: () -> Unit) =
        actions.add(action)

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register {
            actions.forEach { it() }
            actions.clear()
        }
    }
}