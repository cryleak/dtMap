package com.ricedotwho.dtmap.utils

import com.ricedotwho.dtmap.DtMap.mc
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.max

fun Color.opacity(multiplier: Float): Color =
    Color(this.red, this.green, this.blue, (this.alpha * multiplier).toInt())

fun Color.darker(multiplier: Float): Color =
    Color(
        max((this.red * multiplier).toInt(), 0),
        max((this.green * multiplier).toInt(), 0),
        max((this.blue * multiplier).toInt(), 0),
        this.alpha
    )

inline val Entity.renderPos: Vec3
    get() =
        Vec3(this.renderX, this.renderY, this.renderZ)

inline val Entity.renderX: Double
    get() =
        xo + (x - xo) * mc.deltaTracker.getGameTimeDeltaPartialTick(true)

inline val Entity.renderY: Double
    get() =
        yo + (y - yo) * mc.deltaTracker.getGameTimeDeltaPartialTick(true)

inline val Entity.renderZ: Double
    get() =
        zo + (z - zo) * mc.deltaTracker.getGameTimeDeltaPartialTick(true)

fun Any?.equalsAny(vararg options: Any?): Boolean =
    options.any { this == it }

fun Player.clickSlot(containerId: Int, slotIndex: Int, button: Int = 0, clickType: ContainerInput = ContainerInput.PICKUP) {
    mc.gameMode?.handleContainerInput(containerId, slotIndex, button, clickType, this)
}

private val romanMap = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
private val numberRegex = Regex("^[0-9]+$")
fun romanToInt(s: String): Int {
    return if (s == "") 0
    else if (s.matches(numberRegex)) s.toInt()
    else {
        var result = 0
        for (i in 0 until s.length - 1) {
            val current = romanMap[s[i]] ?: 0
            val next = romanMap[s[i + 1]] ?: 0
            result += if (current < next) -current else current
        }
        result + (romanMap[s.last()] ?: 0)
    }
}

fun BlockPos.toAABB(): AABB =
    AABB(this)
