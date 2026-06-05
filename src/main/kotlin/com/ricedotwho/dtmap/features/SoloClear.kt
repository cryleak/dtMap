package com.ricedotwho.dtmap.features

import com.ricedotwho.dtmap.DtMap
import com.ricedotwho.dtmap.DtMap.mc
import com.ricedotwho.dtmap.config.C2Esp
import com.ricedotwho.dtmap.config.C3Other
import com.ricedotwho.dtmap.events.MapEvents
import com.ricedotwho.dtmap.features.map.Door
import com.ricedotwho.dtmap.features.map.DoorEsp
import com.ricedotwho.dtmap.features.map.DungeonMap
import com.ricedotwho.dtmap.features.map.Room
import com.ricedotwho.dtmap.features.map.Scan
import com.ricedotwho.dtmap.features.map.Scoreboard
import com.ricedotwho.dtmap.features.map.SpecialColumn
import com.ricedotwho.dtmap.utils.Location
import com.ricedotwho.dtmap.utils.clickSlot
import com.ricedotwho.dtmap.utils.drawFilled
import com.ricedotwho.dtmap.utils.drawLineBox
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.world.phys.AABB
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

object SoloClear {
    fun isSoloClearing(): Boolean {
        if (fuckCurrentMap) return false
        if (C3Other.soloClearHudInsight && DtMap.keybindShowHud.isDown) return false
        if ((C3Other.soloClearing == 1 && Scoreboard.dungeonTeammatesNoSelf.isEmpty()) || C3Other.soloClearing == 2) return true
        return false
    }

    fun hack(bool: Boolean) =
        !isSoloClearing() && bool

    fun legit(bool: Boolean) =
        isSoloClearing() || bool

    fun register() {
        HudRenderCallback.EVENT.register(HudCallback)
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register { _, _ ->
            lastClickedIn = null
            lastClosed = 0L
            fuckCurrentMap = false
        }
        MapEvents.ON_PLAYER_ENTER_ROOM.register(EnterRoom)
    }

    var lastClickedIn: Int? = null

    var lastClosed = 0L
    var fuckCurrentMap = false
    val HudCallback = HudRenderCallback { _, _ ->
        if (!C3Other.soloClearingInstaJoin || !isSoloClearing() || Location.island != Location.Island.Dungeon) return@HudRenderCallback

        val player = mc.player ?: return@HudRenderCallback

        val screen = mc.screen as? AbstractContainerScreen<*> ?: return@HudRenderCallback
        if (screen.menu.containerId == lastClickedIn) return@HudRenderCallback

        if (screen.title.string != "Undersized party!") {
            return@HudRenderCallback
        }

        val slot = screen.menu.getSlot(13)
        if (slot.item.hoverName.string != "Undersized party!") {
            return@HudRenderCallback
        }

        player.clickSlot(screen.menu.containerId, slot.index)
        lastClickedIn = screen.menu.containerId
        player.closeContainer()
        fuckCurrentMap = true
        lastClosed = System.currentTimeMillis()
    }

    fun shouldCloseIncomingWindows(): Boolean =
        System.currentTimeMillis() - lastClosed < 2000

    fun openedWindow(packet: ClientboundOpenScreenPacket, ci: CallbackInfo): Boolean {
        if (!shouldCloseIncomingWindows()) return false

        val title = packet.title.string
        if (title != "Creating instance....") return false

        Minecraft.getInstance().screen = null
        mc.connection!!.send(ServerboundContainerClosePacket(packet.containerId))
        ci.cancel()
        return true
    }

    val EnterRoom = MapEvents.EnterRoom { room ->
        if (!C3Other.soloClearingInstantRoomUpdate || !isSoloClearing()) return@EnterRoom

        room?.owner?.let {
            if (it.type == Room.Type.ENTRANCE) return@EnterRoom

            val updatedRooms = mutableListOf<Room.StateUpdated>()
            if (it.state == Room.State.UNDISCOVERED || it.state == Room.State.UNOPENED) {

                val oldState = it.state
                it.state = Room.State.DISCOVERED

                updatedRooms.add(Room.StateUpdated(it, oldState, it.state))

                it.doors.forEach { door ->
                     door.rooms.forEach roomsForEach@ { room ->
                        if (it == room.owner) {
                            return@roomsForEach
                        }

                        if (room.owner!!.state == Room.State.UNDISCOVERED) {
                            room.owner.state = Room.State.UNOPENED
                            room.owner.entryTile = room.pos.add(201, 201).divide(32)

                            updatedRooms.add(Room.StateUpdated(it, Room.State.UNDISCOVERED, room.owner.state))
                        }
                    }
                }

                SpecialColumn.updateSpecialColumn()
            }

            MapEvents.ON_UPDATED_ROOMS.invoker().updatedRooms(updatedRooms)
        }
    }

    fun renderDoorEsp(context: WorldRenderContext) {
        val roomPlayerIn = DungeonMap.roomPlayerInNoBoundsCheck()

        Scan.doors.forEach { door ->
            if (door.type == Door.Type.BLOOD || door.type == Door.Type.NORMAL || !door.locked) {
                return@forEach
            }

            if (C2Esp.doorEsp != 1 && !door.seen) {
                return@forEach
            }

            if (door.rooms.none { it.owner == roomPlayerIn?.owner }) return@forEach

            val colors = if (door.locked && door.type == Door.Type.WITHER && DoorEsp.witherKeys > 0) arrayOf(C2Esp.doorOpenableColor, C2Esp.doorOpenableColorFilled)
            else arrayOf(C2Esp.doorEspColor, C2Esp.doorEspColorFilled)

            val box = AABB(
                door.pos.x - 1.0, 69.0, door.pos.z - 1.0,
                door.pos.x + 2.0, 73.0, door.pos.z + 2.0
            )

            if (colors[0].alpha > 0) context.drawLineBox(box, colors[0], C2Esp.doorEspWidth, false)
            if (colors[1].alpha > 0) context.drawFilled(box, colors[1], false)
        }
    }
}