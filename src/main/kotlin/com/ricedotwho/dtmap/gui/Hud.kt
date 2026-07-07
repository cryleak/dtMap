package com.ricedotwho.dtmap.gui

import com.ricedotwho.dtmap.DtMap
import com.ricedotwho.dtmap.DtMap.mc
import com.ricedotwho.dtmap.features.map.Scoreboard
import com.ricedotwho.dtmap.utils.DungeonMessages
import com.ricedotwho.dtmap.utils.Location
import com.ricedotwho.dtmap.utils.render.skija.SkijaRenderer
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import java.awt.Color
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.math.sign


// mostly copied over from BladeMasterGabe's practical-config
object Hud : SkijaScreen("DtMapHud") {
    private val components = mutableListOf<Component>()

    enum class Condition(val displayName: String, val predicate: () -> Boolean) {
        Always("Always", { true }),
        Boss("In Dungeons Boss", { DungeonMessages.inBoss }),
        F7Boss("In F7 Boss", { DungeonMessages.inBoss && Scoreboard.floor.number == 7 }),
        Clear("In Clear", { Location.island == Location.Island.Dungeon && (DungeonMessages.seenDungeonStart || Scoreboard.stats.elapsedTime != "0s") && !DungeonMessages.inBoss }),
        BeforeMort("Before Dungeon Start", { Location.island == Location.Island.Dungeon && !DungeonMessages.seenDungeonStart }),
        Alt("Hud Insight", { DtMap.keybindShowHud.isDown }),
        HideOverlaySecrets("Hide Action Bar Secrets", { false })
    }

    enum class Type {
        Dungeon,
        Other
    }

    abstract class Component {
        internal val staticRenderConditions: MutableList<Condition>
        internal val allowedStaticRenderConditions: MutableList<Condition>

        internal val dungeonClasses: MutableList<Scoreboard.DungeonClass>

        internal val identifier: String
        internal var x: Double
        internal var y: Double
        internal val type: Type
        internal var scale: Float

        constructor(
            identifier: String,
            x: Double, y: Double,
            type: Type,
            scale: Float = 1.0f,
            staticRenderConditions: MutableList<Condition> = mutableListOf(Condition.Boss, Condition.Clear, Condition.Alt),
            allowedStaticRenderConditions: MutableList<Condition> = when (type) {
                Type.Dungeon -> mutableListOf(Condition.Boss, Condition.F7Boss, Condition.Clear, Condition.BeforeMort, Condition.Alt)
                Type.Other -> mutableListOf(Condition.Always, Condition.Alt)
            },
            dungeonClasses: MutableList<Scoreboard.DungeonClass> = if (type == Type.Dungeon) Scoreboard.DungeonClass.entries.subList(0, 5).toMutableList() else mutableListOf()
        ) {
            this.identifier = identifier
            this.x = x
            this.y = y
            this.type = type
            this.scale = scale
            this.staticRenderConditions = staticRenderConditions
            this.allowedStaticRenderConditions = allowedStaticRenderConditions
            this.dungeonClasses = dungeonClasses

            components.add(this)
        }

        internal fun position(context: GuiGraphicsExtractor): Pair<Int, Int> =
            position(context.guiWidth(), context.guiHeight())

        internal fun position(width: Int, height: Int) =
            Pair((x * width).toInt(), (y * height).toInt())

        internal fun shouldRender(): Boolean {
            val playerClass = Scoreboard.dungeonPlayer?.clazz ?: Scoreboard.DungeonClass.Unknown
            if (
                type == Type.Dungeon &&
                playerClass != Scoreboard.DungeonClass.Unknown &&
                dungeonClasses.none { it == Scoreboard.dungeonPlayer?.clazz }
            ) {
                return staticRenderConditions.find { it == Condition.BeforeMort }?.predicate() ?: false
            }

            return staticRenderConditions.any { it.predicate() }
        }

        abstract fun render(context: GuiGraphicsExtractor)
        abstract fun example(context: GuiGraphicsExtractor)
        abstract fun bounds(): Pair<Double, Double>

        internal fun offsetBounds(context: GuiGraphicsExtractor): Pair<Int, Int> =
            offsetBounds(context.guiWidth(), context.guiHeight())

        internal open fun offsetBounds(width: Int, height: Int): Pair<Int, Int> =
            Pair(0, 0)

        internal fun internalBounds(): Pair<Double, Double> {
            val bounds = bounds()
            return Pair(bounds.first * scale, bounds.second * scale)
        }

        internal fun internalRender(context: GuiGraphicsExtractor, example: Boolean) {
            if (mc.gui.hud.tabList.visible || (!example && !shouldRender())) return

            val pose = context.pose()

            pose.pushMatrix()
            pose.translate(x.toFloat() * context.guiWidth(), y.toFloat() * context.guiHeight())
            pose.scale(scale)

            if (example) example(context)
            else render(context)

            pose.popMatrix()
        }
    }

    private val configPath: Path = FabricLoader.getInstance().configDir.resolve("dtmap/hud")
    private fun save(component: Component) {
        if (!configPath.exists()) configPath.createDirectories()
        val file = configPath.resolve(component.identifier)
        val writer = file.bufferedWriter()
        writer.write("x:${component.x}\n")
        writer.write("y:${component.y}\n")
        writer.write("scale:${component.scale}\n")
        writer.write("conditions:${component.staticRenderConditions.joinToString(",") { it.name }}\n")

        if (component.dungeonClasses.isNotEmpty()) writer.write("dungeon_classes:${component.dungeonClasses.joinToString(",") { it.name }}\n")

        writer.close()
    }

    private fun load(component: Component) {
        val file = configPath.resolve(component.identifier)
        if (!file.exists()) return
        val reader = file.bufferedReader()
        reader.readLines().forEach {
            val spl = it.split(":")
            if (spl.size != 2) return@forEach
            when (spl[0]) {
                "x" -> component.x = spl[1].toDouble()
                "y" -> component.y = spl[1].toDouble()
                "scale" -> component.scale = spl[1].toFloat()
                "conditions" -> {
                    component.staticRenderConditions.clear()
                    component.staticRenderConditions.addAll(spl[1].split(",").mapNotNull { modifier -> Condition.entries.find { it.name == modifier } })
                }
                "dungeon_classes" -> {
                    component.dungeonClasses.clear()
                    component.dungeonClasses.addAll(spl[1].split(",").mapNotNull { klass -> Scoreboard.DungeonClass.entries.find { it.name == klass }})
                }
            }
        }
        reader.close()
    }

    fun register() {
        components.forEach(::load)
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("dtmap", "hud"), Renderer)
    }

    private var selected: Component? = null
    private var openedOptions: Component? = null
    private var isDragging = false

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double
    ): Boolean {
        if (selected != null) {
            selected!!.scale = (selected!!.scale + sign(verticalAmount).toFloat() * 0.02f).coerceIn(0.1f, 8.0f)
            return true
        }
        return false
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val x = click.x()
        val y = click.y()

        if (clickedInOpenedOptions(x, y)) {
            handleOptionsClick(x, y)
            return true
        }

        val clicked = components.find {
            val bounds = it.internalBounds()
            var (posX, posY) = it.position(minecraft.window.guiScaledWidth, minecraft.window.guiScaledHeight)
            val offset = it.offsetBounds(minecraft.window.guiScaledWidth, minecraft.window.guiScaledHeight)
            posX += offset.first
            posY += offset.second

            posX <= x && posX + bounds.first >= x && posY <= y && posY + bounds.second >= y
        }

        selected = clicked
        if (selected == null || selected != openedOptions) openedOptions = null

        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_2) {
            openedOptions = if (clicked == openedOptions) null else clicked
            return true
        }

        if (selected != null && click.button() == GLFW.GLFW_MOUSE_BUTTON_1) isDragging = true

        return selected != null
    }

    override fun mouseDragged(click: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
        if (isDragging && selected != null) {
            val window = minecraft.window
            selected!!.x += offsetX / window.guiScaledWidth
            selected!!.y += offsetY / window.guiScaledHeight
        }

        return isDragging
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        isDragging = false
        return true
    }

    private val Renderer = HudElement { context, _ ->
        components.forEach { it.internalRender(context, false) }
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
        val width = context.guiWidth()
        val height = context.guiHeight()

        context.verticalLine(width / 2, 0, height, -0xffbbbc)
        context.horizontalLine(0, width, height / 2, -0xffbbbc)

        components.forEach { it.internalRender(context, true) }

        if (selected != null) {
            val bounds = selected!!.internalBounds()
            val (posX, posY) = selected!!.position(context)
            val offset = selected!!.offsetBounds(context)
            context.outline(posX + offset.first, posY + offset.second, bounds.first.toInt(), bounds.second.toInt(), Color.RED.rgb)
        }
    }

    override fun isPauseScreen(): Boolean =
        false

    override fun onClose() {
        components.forEach(::save)
        selected = null
        openedOptions = null
        super.onClose()
    }

    private enum class OptionKind {
        Condition,
        DungeonClass
    }

    private data class OptionRow(
        val kind: OptionKind,
        val condition: Condition?,
        val dungeonClass: Scoreboard.DungeonClass?,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    ) {
        fun contains(mouseX: Double, mouseY: Double): Boolean =
            mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height
    }

    private val optionRows = mutableListOf<OptionRow>()

    private fun openedOptionsSize(opened: Component): Pair<Float, Float> =
        Pair(246f, if (opened.type == Type.Dungeon) 318f else 190f)

    private fun clickedInOpenedOptions(x: Double, y: Double): Boolean {
        if (openedOptions == null) return false

        val (posX, posY) = selectedWindowPosition()
        val (sizeX, sizeY) = openedOptionsSize(openedOptions!!)
        return x >= posX && x <= posX + sizeX && y >= posY && y <= posY + sizeY
    }

    private fun selectedWindowPosition(): Pair<Float, Float> {
        val opened = openedOptions ?: return Pair(0f, 0f)

        val screenW = minecraft.window.guiScaledWidth.toFloat()
        val screenH = minecraft.window.guiScaledHeight.toFloat()
        val (boundsX, boundsY) = opened.offsetBounds(screenW.toInt(), screenH.toInt())
        val pos = opened.position(screenW.toInt(), screenH.toInt())
        val (sizeX, sizeY) = openedOptionsSize(opened)

        var x = pos.first + boundsX.toFloat() - sizeX - 8f
        var y = pos.second + boundsY.toFloat()

        if (y < 6f) y = 6f
        if (y + sizeY > screenH - 6f) y = screenH - sizeY - 6f

        if (x < 6f) {
            val (boundsSizeX, _) = opened.internalBounds()
            x = pos.first + boundsX.toFloat() + boundsSizeX.toFloat() + 8f
        }

        x = x.coerceIn(6f, (screenW - sizeX - 6f).coerceAtLeast(6f))
        return Pair(x, y.coerceIn(6f, (screenH - sizeY - 6f).coerceAtLeast(6f)))
    }

    override fun renderSkija(mouseX: Double, mouseY: Double, deltaTicks: Float) {
        val opened = openedOptions ?: return

        val (x, y) = selectedWindowPosition()
        val (sizeX, sizeY) = openedOptionsSize(opened)
        optionRows.clear()

        SkijaRenderer.dropShadow(x, y, sizeX, sizeY, 10f, 1f, 7f)
        SkijaRenderer.rect(x, y, sizeX, sizeY, PANEL, 7f)
        SkijaRenderer.hollowRect(x, y, sizeX, sizeY, 1f, BORDER, 7f)
        SkijaRenderer.text(opened.identifier, x + 12f, y + 11f, 14f, TEXT)
        SkijaRenderer.text("Scale ${String.format("%.2f", opened.scale)}", x + 12f, y + 31f, 11f, MUTED)

        var rowY = y + 55f
        SkijaRenderer.text("Render conditions", x + 12f, rowY, 11.5f, MUTED)
        rowY += 18f
        opened.allowedStaticRenderConditions.forEach { condition ->
            renderOptionRow(
                OptionRow(OptionKind.Condition, condition, null, x + 10f, rowY, sizeX - 20f, 24f),
                opened.staticRenderConditions.any { it == condition },
                condition.displayName,
                mouseX,
                mouseY
            )
            rowY += 26f
        }

        if (opened.type == Type.Dungeon) {
            rowY += 8f
            SkijaRenderer.text("Classes", x + 12f, rowY, 11.5f, MUTED)
            rowY += 18f
            Scoreboard.DungeonClass.entries.subList(0, 5).forEach { klass ->
                renderOptionRow(
                    OptionRow(OptionKind.DungeonClass, null, klass, x + 10f, rowY, sizeX - 20f, 24f),
                    opened.dungeonClasses.any { it == klass },
                    klass.name,
                    mouseX,
                    mouseY
                )
                rowY += 26f
            }
        }
    }

    private fun renderOptionRow(row: OptionRow, active: Boolean, label: String, mouseX: Double, mouseY: Double) {
        val hovered = row.contains(mouseX, mouseY)
        SkijaRenderer.rect(row.x, row.y, row.width, row.height, if (hovered) CONTROL_HOVER else CONTROL, 5f)
        SkijaRenderer.hollowRect(row.x + 6f, row.y + 5f, 14f, 14f, 1f, if (active) ACCENT else MUTED, 3f)
        if (active) {
            SkijaRenderer.rect(row.x + 9f, row.y + 8f, 8f, 8f, ACCENT, 2f)
        }
        SkijaRenderer.text(label, row.x + 28f, row.y + 7f, 12f, TEXT)
        optionRows += row
    }

    private fun handleOptionsClick(mouseX: Double, mouseY: Double) {
        val opened = openedOptions ?: return
        val row = optionRows.firstOrNull { it.contains(mouseX, mouseY) } ?: return
        when (row.kind) {
            OptionKind.Condition -> {
                val condition = row.condition ?: return
                if (opened.staticRenderConditions.any { it == condition }) {
                    opened.staticRenderConditions.remove(condition)
                } else {
                    opened.staticRenderConditions.add(condition)
                }
            }
            OptionKind.DungeonClass -> {
                val klass = row.dungeonClass ?: return
                if (opened.dungeonClasses.any { it == klass }) {
                    opened.dungeonClasses.remove(klass)
                } else {
                    opened.dungeonClasses.add(klass)
                }
            }
        }
    }

    private const val PANEL = 0xF0171A20.toInt()
    private const val CONTROL = 0xFF252A32.toInt()
    private const val CONTROL_HOVER = 0xFF303641.toInt()
    private const val BORDER = 0xFF3A414D.toInt()
    private const val TEXT = 0xFFF2F4F7.toInt()
    private const val MUTED = 0xFF9AA3AF.toInt()
    private const val ACCENT = 0xFFFFB86B.toInt()
    }
