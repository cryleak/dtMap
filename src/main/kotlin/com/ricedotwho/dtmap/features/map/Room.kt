package com.ricedotwho.dtmap.features.map

import com.ricedotwho.dtmap.DtMap.mc
import com.ricedotwho.dtmap.config.C1Map
import com.ricedotwho.dtmap.config.C3Other
import com.ricedotwho.dtmap.features.SoloClear
import com.ricedotwho.dtmap.features.map.Scan.roomsList
import com.ricedotwho.dtmap.utils.darker
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import java.awt.Color

class Room(var type: Type, var shape: Shape, var data: RoomData? = null, var height: Int? = null, var floorHeight: Int? = null) {
    var tiles: MutableList<Tile> = mutableListOf()
    var places: MutableList<Vec2i> = mutableListOf()
    var state: State = State.UNDISCOVERED
    var clayPos: BlockPos? = null
    var rotation: Rotation = Rotation.NONE
    val doors = mutableSetOf<Door>()

    // TODO: this doesn't allow for a room to have more than 1 entry tile, which will happen when 2 rooms lead to the same room at different places

    // legit property - entryTile is kept so that we know which tile to display when the legit mode is on.
    var entryTile: Vec2i? = null

    // legit property - isKnown1x1 it's only set once it's clear for a legit player that this is a 1x1.
    var isKnown1x1 = false

    // legit property - specialTile is true if the room is a part of the special column, it's extracted off the map item
    var specialTile = false

    var rushRoom = false
    var mimic = false

    constructor(data: RoomData, height: Int, floorHeight: Int) : this(data.type, data.shape, data, height, floorHeight)

    enum class State {
        GREEN, CLEARED, FAILED, DISCOVERED, UNOPENED, UNDISCOVERED
    }

    enum class Rotation(val pos: Vec2i) {
        NORTH(Vec2i(15, 15)),
        SOUTH(Vec2i(-15, -15)),
        WEST(Vec2i(15, -15)),
        EAST(Vec2i(-15, 15)),
        NONE(Vec2i(0, 0));
    }

    enum class Type {
        BLOOD, CHAMPION, ENTRANCE, FAIRY, NORMAL, PUZZLE, RARE, TRAP, UNKNOWN;
    }

    enum class Shape(val str: String, val tileCount: Int) {
        UNKNOWN("Unknown", 0),
        SL("L", 3),
        S1x1("1x1", 1),
        S2x1("1x2", 2),
        S3x1("1x3", 3),
        S4x1("1x4", 4),
        S2x2("2x2", 4);

        companion object {
            fun fromStr(str: String): Shape? =
                entries.find { it.str == str }
        }
    }

    class Tile(val owner: Room?, val pos: Vec2i) {
        val placement = run {
            val x = (pos.x + 185) shr 5
            val z = (pos.z + 185) shr 5
            val xOffset = x * (16 + 4)
            val yOffset = z * (16 + 4)
            return@run Vec2i(xOffset, yOffset)
        }

        inline val listIndex: Int
            get() = (pos.x + 185) / 32 * 6 + (pos.z + 185) / 32
    }

    data class StateUpdated(val room: Room, val old: State, val new: State)

    fun updateState(placement: Vec2i, color: Int): StateUpdated? {
        // TODO: this won't work if golden oasis is secreted before its cleared, potentially the secret tracker thing can do 3/1 -> golden oasis and marks it green
        // Golden Oasis has 2 extra secrets and is marked green on 1/1 but turns
        // back to white once you get 2/1 and 3/1.
        if (state == State.GREEN && data?.name == "Golden Oasis") return null

        val oldState = state
        state = when (color) {
            0 -> {
                if (C3Other.soloClearingInstantRoomUpdate && SoloClear.isSoloClearing() && (state == State.UNOPENED || state == State.UNDISCOVERED)) {
                    return null
                }

                State.UNDISCOVERED
            }
            34 -> State.CLEARED

            18 -> when (type) {
                Type.BLOOD -> {
                    Scan.blood = this
                    State.DISCOVERED
                }
                Type.PUZZLE -> State.FAILED
                else -> state
            }

            30 -> when (type) {
                Type.ENTRANCE -> State.DISCOVERED
                else -> State.GREEN
            }

            85, 119 -> {
                entryTile = placement
                specialTile = placement.x == SpecialColumn.column

                if (C3Other.soloClearingInstantRoomUpdate && SoloClear.isSoloClearing() && state != State.UNDISCOVERED) return null

                State.UNOPENED
            }
            else -> State.DISCOVERED
        }

        if (state == oldState) return null
        return StateUpdated(this, oldState, state)
    }

    private fun color(): Array<Color> {
        val legit = SoloClear.legit(C1Map.legitMode)
        if (legit && state == State.UNOPENED) {
            return if (isKnown1x1 && doors.count { it.seen } == 1) SpecialColumn.roomColorGuess(this)
            else if (type == Type.BLOOD) arrayOf(C1Map.bloodRoomColor.darker(C1Map.darkenMultiplier))
            else arrayOf(C1Map.unopenedRoomColor)
        }

        val color = if (mimic && !legit) C1Map.mimicRoomColor else when (type) {
            Type.BLOOD -> C1Map.bloodRoomColor
            Type.NORMAL -> C1Map.normalRoomColor
            Type.PUZZLE -> C1Map.puzzleRoomColor
            Type.CHAMPION -> C1Map.championRoomColor
            Type.TRAP -> C1Map.trapRoomColor
            Type.ENTRANCE -> C1Map.entranceRoomColor
            Type.FAIRY -> C1Map.fairyRoomColor
            Type.RARE -> C1Map.rareRoomColor
            // not sure if this is a good color for this
            Type.UNKNOWN -> return arrayOf(C1Map.unopenedRoomColor)
        }

        if (state == State.UNDISCOVERED || state == State.UNOPENED) {
            return arrayOf(color.darker(C1Map.darkenMultiplier))
        }

        return arrayOf(color)
    }

    fun render(context: GuiGraphics) {
        val legitMode = SoloClear.legit(C1Map.legitMode)
        if (legitMode && state == State.UNDISCOVERED) return

        val matrices = context.pose()

        if (legitMode && state == State.UNOPENED) {
            val entryTile = entryTile ?: return

            matrices.pushMatrix()
            matrices.translate(entryTile.x.toFloat() * 20, entryTile.z.toFloat() * 20)

            val color = color()
            when (color.size) {
                1 -> context.fill(0, 0, 16, 16, color[0].rgb)
                2 -> {
                    context.fill(0, 0, 16, 8, color[0].rgb)
                    context.fill(0, 8, 16, 16, color[1].rgb)
                }
                3 -> {
                    context.fill(0, 0, 16, 5, color[0].rgb)
                    context.fill(0, 0, 5, 10, color[0].rgb)
                    context.fill(10, 5, 16, 16, color[1].rgb)
                    context.fill(0, 10, 16, 16, color[1].rgb)
                    context.fill(5, 5, 11, 11, color[2].rgb)
                }
            }

            matrices.popMatrix()

            return
        }

        val topLeft = tiles.minBy { tile ->
            tile.placement.x * 1000 + tile.placement.z
        }.placement

        val bottomRight = tiles.maxBy { tile ->
            val placement = tile.placement
            tile.placement.x * 1000 + placement.z
        }.placement

        val color = color()
        if (shape == Shape.SL && tiles.size > 2) {
            when (rotation) {
                Rotation.SOUTH -> {
                    context.fill(topLeft.x, topLeft.z, topLeft.x + 16, bottomRight.z + 16, color[0].rgb)
                    context.fill(topLeft.x, bottomRight.z, bottomRight.x + 16, bottomRight.z + 16, color[0].rgb)
                }
                Rotation.WEST -> {
                    context.fill(topLeft.x, topLeft.z, topLeft.x + 36, topLeft.z + 16, color[0].rgb)
                    context.fill(topLeft.x, topLeft.z, topLeft.x + 16, topLeft.z + 36, color[0].rgb)
                }
                Rotation.NORTH -> {
                    context.fill(topLeft.x, topLeft.z, bottomRight.x + 16, topLeft.z + 16, color[0].rgb)
                    context.fill(bottomRight.x, topLeft.z, bottomRight.x + 16, bottomRight.z + 16, color[0].rgb)
                }
                Rotation.EAST -> {
                    context.fill(topLeft.x, topLeft.z, topLeft.x + 16, topLeft.z + 36, color[0].rgb)
                    context.fill(topLeft.x - 20, bottomRight.z, topLeft.x + 16, bottomRight.z + 16, color[0].rgb)
                }
                else -> {}
            }
        } else {
            context.fill(topLeft.x, topLeft.z, bottomRight.x + 16, bottomRight.z + 16, color[0].rgb)
        }

        if (C1Map.roomAdditionsPrince && data?.prince == true && !Scoreboard.stats.princeKilled) {
            matrices.pushMatrix()
            matrices.translate(bottomRight.x.toFloat() + 9f, bottomRight.z.toFloat() + 10f)
            matrices.scale(0.7f)
            context.blit(RenderPipelines.GUI_TEXTURED, princeMarker, 0, 0, 0f, 0f, 10, 10, 10, 10)
            matrices.popMatrix()
        }
    }

    fun renderName(context: GuiGraphics, textFactor: Float) {
        val matrices = context.pose()
        val fontHeight = mc.font.lineHeight

        if ((!C3Other.soloClearingOnlyShowCheckmarks || !SoloClear.isSoloClearing()) && (!SoloClear.legit(C1Map.legitMode) || (state != State.UNDISCOVERED && state != State.UNOPENED)) && type != Type.FAIRY) {
            data?.name?.let { name ->
                val splitName = name.split(" ")
                val defaultHeight =
                    8 - fontHeight / (2 * textFactor) - ((splitName.size - 1) / 2.0 * (fontHeight / textFactor)).toInt()

                val placement = textPlacement()
                splitName.withIndex().forEach { (index, value) ->
                    matrices.pushMatrix()
                    matrices.translate(
                        placement.x + 8f,
                        placement.z + index * (fontHeight / textFactor) + defaultHeight
                    )
                    matrices.scale(C1Map.textScaling)

                    val color = when (state) {
                        State.GREEN -> Color.GREEN.rgb
                        State.CLEARED -> Color(255, 255, 255).rgb
                        State.DISCOVERED -> Color(100, 100, 100).rgb
                        State.FAILED -> Color(255, 0, 0).rgb
                        else -> Color(255, 255, 255).rgb
                    }

                    context.drawCenteredString(
                        mc.font,
                        value,
                        0,
                        0,
                        color
                    )

                    matrices.popMatrix()
                }

                return
            }
        }

        val resource = when (state) {
            State.GREEN -> MapRenderer.greenCheck
            State.CLEARED -> MapRenderer.whiteCheck
            State.FAILED -> MapRenderer.cross
            State.UNOPENED -> {
                if (!C1Map.undiscoveredQuestionMarks || (isKnown1x1 && doors.size == 1) || type == Type.BLOOD) {
                    return
                }
                MapRenderer.questionMark
            }
            else -> return
        }

        if (resource != MapRenderer.questionMark && type == Type.FAIRY) return

        val placement = if (state == State.UNOPENED) {
            entryTile?.multiply(20) ?: return
        } else textPlacement()

        context.blit(
            RenderPipelines.GUI_TEXTURED,
            resource,
            placement.x + 2, placement.z + 2,
            0f, 0f,
            12, 12,
            12, 12,
            0xFFFFFFFF.toInt()
        )
    }

    fun roomTile(pos: Vec2i): Tile? {
        if (tiles.any { pos == it.pos }) return null

        val tile = Tile(this, pos)
        tiles.add(tile)
        places.add(pos.add(Vec2i(185, 185)).divide(32))
        roomsList[tile.listIndex] = tile
        return tile
    }

    fun offset(blockPos: BlockPos): BlockPos? {
        if (clayPos == null) return null
        return blockPos.rotateAroundNorth(rotation).offset(clayPos!!.x, 0, clayPos!!.z)
    }

    fun getOffset(blockPos: BlockPos): BlockPos? {
        if (clayPos == null) return null
        return blockPos.subtract(clayPos!!.atY(0)).rotateToNorth(rotation)
    }

    fun offset(pos: Vec3): Vec3? {
        if (clayPos == null) return null
        return pos.rotateAroundNorth(rotation).offset(clayPos!!.x.toDouble(), 0.0, clayPos!!.z.toDouble())
    }

    fun getOffset(pos: Vec3): Vec3? {
        if (clayPos == null) return null
        return pos.subtract(clayPos!!.atY(0)).rotateToNorth(rotation)
    }

    fun inRoom(pos: Vec3i): Boolean {
        if (height != null && pos.y > height!!) return false
        val roomPos = Vec2i(pos.x, pos.z).roomTilePos()
        return tiles.any { it.pos.roomTilePos() == roomPos }
    }

    fun topLeftTilePlacement(): Vec2i =
        tiles.minBy { it.pos.x * 1000 + it.pos.z }.placement

    fun textPlacement(): Vec2i {
        if (rotation == Rotation.NONE || !C1Map.textCenter) return topLeftTilePlacement()
        val placements = tiles.map { it.placement }

        return if (shape == Shape.SL && tiles.size > 2) {
            val topLeft = tiles.minWith { a, b -> a.pos.x * 1000 + a.pos.z - b.pos.x * 1000 - b.pos.z }.placement
            when (rotation) {
                Rotation.EAST, Rotation.SOUTH -> topLeft.add(10, 20)
                else -> topLeft.add(10, 0)
            }
        } else Vec2i((placements.minOf { it.x } + placements.maxOf { it.x }) / 2, (placements.minOf { it.z } + placements.maxOf { it.z }) / 2)
    }

    companion object {
        private val princeMarker = Identifier.fromNamespaceAndPath("dtmap", "map/prince_crown_ziyno.png")
    }
}

fun BlockPos.rotateToNorth(rotation: Room.Rotation): BlockPos =
    when (rotation) {
        Room.Rotation.NORTH -> BlockPos(-this.x, this.y, -this.z)
        Room.Rotation.WEST ->  BlockPos(this.z, this.y, -this.x)
        Room.Rotation.SOUTH -> BlockPos(this.x, this.y, this.z)
        Room.Rotation.EAST ->  BlockPos(-this.z, this.y, this.x)
        else -> this
    }

fun BlockPos.rotateAroundNorth(rotation: Room.Rotation): BlockPos =
    when (rotation) {
        Room.Rotation.NORTH -> BlockPos(-this.x, this.y, -this.z)
        Room.Rotation.WEST ->  BlockPos(-this.z, this.y, this.x)
        Room.Rotation.SOUTH -> BlockPos(this.x, this.y, this.z)
        Room.Rotation.EAST ->  BlockPos(this.z, this.y, -this.x)
        else -> this
    }

fun Vec3.rotateToNorth(rotation: Room.Rotation): Vec3 =
    when (rotation) {
        Room.Rotation.NORTH -> Vec3(-this.x, this.y, -this.z)
        Room.Rotation.WEST ->  Vec3(this.z, this.y, -this.x)
        Room.Rotation.SOUTH -> Vec3(this.x, this.y, this.z)
        Room.Rotation.EAST ->  Vec3(-this.z, this.y, this.x)
        else -> this
    }

fun Vec3.rotateAroundNorth(rotation: Room.Rotation): Vec3 =
    when (rotation) {
        Room.Rotation.NORTH -> Vec3(-this.x, this.y, -this.z)
        Room.Rotation.WEST ->  Vec3(-this.z, this.y, this.x)
        Room.Rotation.SOUTH -> Vec3(this.x, this.y, this.z)
        Room.Rotation.EAST ->  Vec3(this.z, this.y, -this.x)
        else -> this
    }

fun Vec3.offset(x: Double, y: Double, z: Double): Vec3 =
    Vec3(this.x + x, this.y + y, this.z + z)

fun Vec3.subtract(blockPos: BlockPos): Vec3 =
    Vec3(x - blockPos.x, y - blockPos.y, z - blockPos.z)
