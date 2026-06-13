package com.ricedotwho.dtmap.features.map

import com.ricedotwho.dtmap.events.MapEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents

object Prince {
    var legitPrince: Boolean = true
    var cheaterPrince: Boolean = true

    fun register() {
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register { _, _ ->
            legitPrince = true
            cheaterPrince = true
        }

        MapEvents.ON_LOADED_ROOMS.register {
            updatePrince()
        }

        MapEvents.ON_UPDATED_ROOMS.register {
            updatePrince()
        }
    }

    fun updatePrince() {
        val princeRooms = Scan.rooms.filter { room -> room.data?.prince == true }
        if (princeRooms.isEmpty() && Scan.loadedAllRooms) cheaterPrince = false

        val legitPrinces = princeRooms.count { room -> room.state != Room.State.UNDISCOVERED && room.state != Room.State.UNOPENED }
        if (legitPrinces > 0) return

        val notVisibleRooms = Scan.rooms.count { room ->
            room.type != Room.Type.BLOOD && (room.state == Room.State.UNDISCOVERED || room.state == Room.State.UNOPENED)
        }

        if (Scan.loadedAllRooms && legitPrinces == 0 && notVisibleRooms == 0) legitPrince = false
    }
}