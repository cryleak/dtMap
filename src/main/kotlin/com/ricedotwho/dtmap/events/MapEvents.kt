package com.ricedotwho.dtmap.events

import com.ricedotwho.dtmap.features.map.Room
import net.fabricmc.fabric.api.event.EventFactory

object MapEvents {
    fun interface LoadedRooms {
        fun loadedRooms(rooms: List<Room>)
    }

    val ON_LOADED_ROOMS = EventFactory.createArrayBacked(LoadedRooms::class.java) { listeners ->
        LoadedRooms { rooms ->
            listeners.forEach {
                it.loadedRooms(rooms)
            }
        }
    }

    fun interface UpdatedRooms {
        fun updatedRooms(rooms: List<Room.StateUpdated>)
    }

    val ON_UPDATED_ROOMS = EventFactory.createArrayBacked(UpdatedRooms::class.java) { listeners ->
        UpdatedRooms { rooms ->
            listeners.forEach {
                it.updatedRooms(rooms)
            }
        }
    }

    fun interface EnterRoom {
        fun enterRoom(room: Room.Tile?)
    }

    val ON_PLAYER_ENTER_ROOM = EventFactory.createArrayBacked(EnterRoom::class.java) { listeners ->
        EnterRoom { room ->
            listeners.forEach {
                it.enterRoom(room)
            }
        }
    }
}