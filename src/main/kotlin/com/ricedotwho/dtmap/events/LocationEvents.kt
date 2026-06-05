package com.ricedotwho.dtmap.events

import com.ricedotwho.dtmap.features.map.Scoreboard
import com.ricedotwho.dtmap.utils.Location
import net.fabricmc.fabric.api.event.EventFactory

object LocationEvents {
    fun interface ChangeIsland {
        fun changeIsland(island: Location.Island)
    }

    val ON_CHANGE_ISLAND = EventFactory.createArrayBacked(ChangeIsland::class.java) { listeners ->
        ChangeIsland { island ->
            listeners.forEach {
                it.changeIsland(island)
            }
        }
    }

    fun interface OnEnterDungeon {
        fun enterDungeon(floor: Scoreboard.Floor)
    }

    val ON_ENTER_DUNGEON = EventFactory.createArrayBacked(OnEnterDungeon::class.java) { listeners ->
        OnEnterDungeon { floor ->
            listeners.forEach {
                it.enterDungeon(floor)
            }
        }
    }
}