package com.ricedotwho.dtmap.features.map

import com.ricedotwho.dtmap.config.C1Map
import com.ricedotwho.dtmap.features.SoloClear
import com.ricedotwho.dtmap.utils.darker
import net.minecraft.client.gui.GuiGraphics
import java.awt.Color

class Door private constructor(val pos: Vec2i, var type: Type, val rooms: MutableList<Room.Tile>, nothing: Boolean = false) {
    enum class Type {
        BLOOD, NORMAL, WITHER;
    }

    inline val placement: Pair<Float, Float>
        get() {
            val x = (pos.x + 185) shr 4
            val z = (pos.z + 185) shr 4
            val xEven = x % 2
            val zEven = z % 2
            val thicknessBasedOffset = (16f - C1Map.doorThicknessF) / 2f
            val xOffset = (x shr 1) * (20f) + xEven * 16f + (xEven xor 1) * thicknessBasedOffset
            val yOffset = (z shr 1) * (20f) + zEven * 16f + (zEven xor 1) * thicknessBasedOffset

            return Pair(xOffset, yOffset)
        }

    var locked = type == Type.WITHER || type == Type.BLOOD
    var seen
        get() = rooms.any { it.owner!!.state != Room.State.UNDISCOVERED && it.owner.state != Room.State.UNOPENED }
        private set(_) {}


    constructor(pos: Vec2i, type: Type, rooms: MutableList<Room.Tile>) : this(pos, type, rooms, false) {
        rooms.forEach { it.owner!!.doors.add(this) }
    }

    private fun size(): Vec2i {
        val xOffset = ((pos.x + 185) shr 4) % 2
        val zOffset = ((pos.z + 185) shr 4) % 2
        return Vec2i(
            (xOffset xor 1) * C1Map.doorThicknessF.toInt() + xOffset * 4,
            (zOffset xor 1) * C1Map.doorThicknessF.toInt() + zOffset * 4
        )
    }

    fun render(context: GuiGraphics) {
        val size = size()
        if (size != Vec2i(0, 0)) {
            val matrices = context.pose()
            matrices.pushMatrix()
            matrices.translate(placement.first, placement.second)

            context.fill(0, 0, size.x, size.z, color().rgb)

            matrices.popMatrix()
        }
    }

    private fun color(): Color {
        val getCheatingColor = fun (): Color {
            return when (type) {
                Type.BLOOD -> C1Map.bloodDoorColor
                Type.WITHER -> {
                    if (locked) {
                        C1Map.witherDoorColor
                    } else {
                        if (rooms.firstOrNull { it.owner!!.type != Room.Type.NORMAL }?.owner?.type == Room.Type.FAIRY) {
                            C1Map.fairyDoorColor
                        } else {
                            C1Map.normalDoorColor
                        }
                    }
                }
                Type.NORMAL -> if (C1Map.doorGay) C1Map.normalDoorColor else
                    when (rooms.firstOrNull { it.owner!!.type != Room.Type.NORMAL && it.owner.type != Room.Type.FAIRY }?.owner?.type) {
                        Room.Type.ENTRANCE -> C1Map.entranceDoorColor
                        Room.Type.BLOOD -> C1Map.bloodDoorColor
                        Room.Type.CHAMPION -> C1Map.championDoorColor
                        Room.Type.FAIRY -> C1Map.fairyDoorColor // this doesn't matter because we scan if the rooms are fairy or not.
                        Room.Type.NORMAL -> C1Map.normalDoorColor
                        Room.Type.PUZZLE -> C1Map.puzzleDoorColor
                        Room.Type.RARE -> C1Map.rareDoorColor
                        Room.Type.TRAP -> C1Map.trapDoorColor
                        Room.Type.UNKNOWN -> C1Map.normalDoorColor
                        null -> C1Map.normalDoorColor
                    }
            }
        }

        val noInfoColor = rooms.any { it.owner!!.state == Room.State.UNDISCOVERED || it.owner.state == Room.State.UNOPENED }
        val legit = SoloClear.legit(C1Map.legitMode)
        if (legit && noInfoColor) {
            return when (type) {
                Type.BLOOD -> if (locked) C1Map.bloodDoorColor.darker(C1Map.darkenMultiplier) else C1Map.bloodDoorColor
                Type.WITHER -> if (locked) C1Map.witherDoorColor.darker(C1Map.darkenMultiplier) else C1Map.witherDoorColor
                Type.NORMAL -> C1Map.unopenedDoorColor
            }
        }

        val color = getCheatingColor()

        if (noInfoColor) {
            return color.darker(C1Map.darkenMultiplier)
        }

        return color
    }
}