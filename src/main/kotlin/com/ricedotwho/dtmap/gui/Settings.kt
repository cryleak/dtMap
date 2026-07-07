package com.ricedotwho.dtmap.gui

import com.ricedotwho.dtmap.utils.MapImageLoader
import com.ricedotwho.dtmap.utils.render.skija.Gradient
import com.ricedotwho.dtmap.utils.render.skija.SkijaRenderer
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW
import org.reflections.Reflections
import org.reflections.scanners.Scanners
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.kotlinProperty

object Settings : SkijaScreen("DtMapSettings") {
    private val configPath: Path = FabricLoader.getInstance().configDir.resolve("dtmap/tabs")

    enum class Type {
        TInteger,
        TFloat,
        TBoolean,
        TText,
        TImmutableText,
        TColor,
        TButton;

        companion object {
            fun fromKType(prop: KProperty<*>, typ: KType): Type {
                val classifier = typ.classifier
                return when {
                    classifier == Int::class -> TInteger
                    classifier == Float::class -> TFloat
                    classifier == Boolean::class -> TBoolean
                    classifier == String::class -> if (prop is KMutableProperty<*>) TText else TImmutableText
                    classifier == Color::class -> TColor
                    classifier is KClass<*> && Function::class.java.isAssignableFrom(classifier.java) -> TButton
                    else -> throw IllegalArgumentException("invalid type of setting: $typ")
                }
            }
        }
    }

    data class SettingImpl(val annotation: Setting, val type: Type, val owner: Any, val prop: KProperty<*>) {
        fun update() {
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> get(): T {
            val casted = prop as KProperty<T>
            return casted.getter.call(owner)
        }

        @Suppress("UNCHECKED_CAST")
        fun set(b: Any) {
            val casted = prop as KMutableProperty<Any>
            casted.setter.call(owner, b)
        }
    }

    data class TabImpl(val name: String, val settings: List<SettingImpl>, val obj: Any, val clazz: KClass<*>)

    var tabs: List<TabImpl>

    private enum class HitKind {
        Tab,
        Boolean,
        Slider,
        Dropdown,
        DropdownOption,
        Text,
        Button,
        ColorSwatch,
        ColorPickerSv,
        ColorPickerHue,
        ColorChannel
    }

    private data class HitBox(
        val kind: HitKind,
        val setting: SettingImpl?,
        val tabIndex: Int,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val index: Int = -1
    ) {
        fun contains(mouseX: Double, mouseY: Double): Boolean =
            mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height
    }

    private var activeTab = 0
    private val hitBoxes = mutableListOf<HitBox>()
    private val scrollOffsets = mutableMapOf<String, Float>()
    private var openedDropdownId: String? = null
    private var openedColorPickerId: String? = null
    private var focusedText: SettingImpl? = null
    private var dragging: HitBox? = null
    private var contentX = 0f
    private var contentY = 0f
    private var contentW = 0f
    private var contentH = 0f
    private var activeContentHeight = 0f

    init {
        val reflections = Reflections("com.ricedotwho.dtmap.config")

        tabs = reflections[Scanners.TypesAnnotated.with(Tab::class.java).asClass<Any>()].sortedBy { it.simpleName }.mapNotNull { tab ->
            val instance = tab.kotlin.objectInstance ?: throw IllegalArgumentException("You can't have tabs that are not objects: ${tab.name}")

            TabImpl(
                tab.getAnnotation(Tab::class.java).tabName,
                instance::class.java.declaredFields.mapNotNull { javaProp ->
                    val prop = javaProp.kotlinProperty ?: return@mapNotNull null
                    val annotation = prop.findAnnotation<Setting>() ?: return@mapNotNull null
                    SettingImpl(annotation, Type.fromKType(prop, prop.returnType), instance, prop)
                },
                instance,
                tab.kotlin
            )
        }

        loadData()
        tabs.forEach { it.settings.forEach { setting -> setting.update() } }
    }

    fun saveData() {
        tabs.forEach { tab ->
            val currTabFile = configPath.resolve("${tab.name}.txt")
            if (!currTabFile.exists()) {
                currTabFile.createParentDirectories()
                currTabFile.createFile()
            }

            Files.newBufferedWriter(currTabFile).use { writer ->
                tab.settings.forEach { setting ->
                    when (setting.type) {
                        Type.TInteger -> writer.write("${setting.prop.name}:${setting.get<Int>()}\n")
                        Type.TFloat -> writer.write("${setting.prop.name}:${setting.get<Float>()}\n")
                        Type.TBoolean -> writer.write("${setting.prop.name}:${setting.get<Boolean>()}\n")
                        Type.TText -> writer.write("${setting.prop.name}:${setting.get<String>()}\n")
                        Type.TImmutableText -> {}
                        Type.TColor -> writer.write("${setting.prop.name}:${setting.get<Color>().rgb}\n")
                        Type.TButton -> {}
                    }
                }
            }
        }
    }

    fun loadData() {
        tabs.forEach { tab ->
            val currTabFile = configPath.resolve("${tab.name}.txt")
            if (!currTabFile.exists()) return@forEach

            Files.newBufferedReader(currTabFile).use { reader ->
                while (true) {
                    try {
                        val line = reader.readLine() ?: break
                        val s = line.split(":", limit = 2).map { it }
                        val setting = tab.settings.find { it.prop.name == s[0] } ?: continue
                        if (setting.type == Type.TImmutableText) continue

                        val value = when (setting.type) {
                            Type.TInteger -> s[1].toInt()
                            Type.TFloat -> s[1].toFloat()
                            Type.TBoolean -> s[1].toBoolean()
                            Type.TText -> s[1]
                            Type.TImmutableText -> throw IllegalStateException("should never try to set immutable text")
                            Type.TColor -> Color(s[1].toInt(), true)
                            Type.TButton -> throw IllegalStateException("should never try to set button")
                        }
                        setting.set(value)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun validateImageSelection() {
        tabs.forEach { tab ->
            tab.settings.forEach { setting ->
                if (setting.prop.name == "imageSelection" && setting.type == Type.TText) {
                    val currentSelection = setting.get<String>()
                    if (currentSelection.isEmpty() || !MapImageLoader.imageExists(currentSelection)) {
                        setting.set("No image")
                    }
                }
            }
        }
    }

    override fun renderSkija(mouseX: Double, mouseY: Double, deltaTicks: Float) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        hitBoxes.clear()

        SkijaRenderer.rect(0f, 0f, w, h, BG)
        SkijaRenderer.rect(0f, 0f, w, HEADER_H, PANEL)
        SkijaRenderer.text("dtMap", 18f, 14f, 24f, TEXT)
        SkijaRenderer.text("Settings", 94f, 20f, 13f, MUTED)

        renderTabs(mouseX, mouseY)
        renderActiveTab(mouseX, mouseY)
    }

    private fun renderTabs(mouseX: Double, mouseY: Double) {
        val tabW = 132f
        SkijaRenderer.rect(0f, HEADER_H, tabW, height - HEADER_H, SIDE)
        tabs.forEachIndexed { index, tab ->
            val y = HEADER_H + 12f + index * 38f
            val active = index == activeTab
            val hovered = HitBox(HitKind.Tab, null, index, 10f, y, tabW - 20f, 30f).contains(mouseX, mouseY)
            val color = when {
                active -> ACCENT
                hovered -> CONTROL_HOVER
                else -> CONTROL
            }
            SkijaRenderer.rect(10f, y, tabW - 20f, 30f, color, 6f)
            SkijaRenderer.text(tab.name, 22f, y + 8f, 13f, if (active) 0xFF111111.toInt() else TEXT)
            hitBoxes += HitBox(HitKind.Tab, null, index, 10f, y, tabW - 20f, 30f)
        }
    }

    private fun renderActiveTab(mouseX: Double, mouseY: Double) {
        val tab = tabs.getOrNull(activeTab) ?: return
        contentX = 154f
        contentY = HEADER_H + 18f
        contentW = (width - contentX - 20f).coerceAtLeast(200f)
        contentH = (height - contentY - 18f).coerceAtLeast(80f)
        val scroll = scrollOffsets[tab.name] ?: 0f
        var y = contentY - scroll

        SkijaRenderer.text(tab.name, contentX, HEADER_H + 15f, 20f, TEXT)
        SkijaRenderer.text("${tab.settings.size} settings", contentX + 88f, HEADER_H + 20f, 12f, MUTED)

        SkijaRenderer.pushScissor(contentX - 4f, contentY + 26f, contentW + 8f, contentH - 26f)
        y += 38f
        tab.settings.forEach { setting ->
            if (setting.annotation.lineBefore) {
                renderSeparator(y)
                y += 13f
            }

            y = renderSetting(setting, y, mouseX, mouseY)

            if (setting.annotation.lineAfter) {
                y += 5f
                renderSeparator(y)
                y += 13f
            }
        }
        SkijaRenderer.popScissor()

        activeContentHeight = y - contentY + scroll
        val maxScroll = max(0f, activeContentHeight - contentH + 24f)
        if (scroll > maxScroll) scrollOffsets[tab.name] = maxScroll
        renderScrollbar(scroll, maxScroll)
    }

    private fun renderSetting(setting: SettingImpl, y: Float, mouseX: Double, mouseY: Double): Float {
        if (y > contentY - 48f && y < contentY + contentH + 48f) {
            when (setting.type) {
                Type.TBoolean -> renderBoolean(setting, y, mouseX, mouseY)
                Type.TInteger -> renderInteger(setting, y, mouseX, mouseY)
                Type.TFloat -> renderFloat(setting, y, mouseX, mouseY)
                Type.TText -> renderText(setting, y, mouseX, mouseY)
                Type.TImmutableText -> renderImmutableText(setting, y)
                Type.TColor -> renderColor(setting, y, mouseX, mouseY)
                Type.TButton -> renderButton(setting, y, mouseX, mouseY)
            }
        } else {
            addOffscreenHitBoxes(setting, y)
        }

        var nextY = y + rowHeight(setting)
        if (isDropdownOpen(setting)) {
            nextY += dropdownOptions(setting).size * OPTION_H + 5f
        }
        if (isColorPickerOpen(setting)) {
            nextY += COLOR_PICKER_H + 8f
        }
        return nextY
    }

    private fun renderBoolean(setting: SettingImpl, y: Float, mouseX: Double, mouseY: Double) {
        renderLabel(setting, y)
        val x = contentX + contentW - 44f
        val active = setting.get<Boolean>()
        val hovered = contains(x, y + 6f, 38f, 22f, mouseX, mouseY)
        SkijaRenderer.rect(x, y + 6f, 38f, 22f, if (active) ACCENT else if (hovered) CONTROL_HOVER else CONTROL, 11f)
        SkijaRenderer.circle(x + if (active) 27f else 11f, y + 17f, 8f, if (active) 0xFF151515.toInt() else MUTED)
        hitBoxes += HitBox(HitKind.Boolean, setting, activeTab, x, y + 4f, 42f, 26f)
    }

    private fun renderInteger(setting: SettingImpl, y: Float, mouseX: Double, mouseY: Double) {
        if (setting.annotation.combo.isNotEmpty()) {
            renderDropdown(setting, setting.annotation.combo.toList(), setting.get<Int>().coerceIn(0, setting.annotation.combo.lastIndex), y, mouseX, mouseY)
        } else {
            val value = setting.get<Int>()
            val (min, max) = numberRange(setting, value.toFloat())
            renderSlider(setting, value.toFloat(), min, max, y, mouseX, mouseY) { it.roundToInt() }
        }
    }

    private fun renderFloat(setting: SettingImpl, y: Float, mouseX: Double, mouseY: Double) {
        val value = setting.get<Float>()
        val (min, max) = numberRange(setting, value)
        renderSlider(setting, value, min, max, y, mouseX, mouseY) { it }
    }

    private fun renderText(setting: SettingImpl, y: Float, mouseX: Double, mouseY: Double) {
        if (setting.prop.name == "imageSelection") {
            val options = MapImageLoader.getImageNames().toList()
            val current = options.indexOf(setting.get<String>()).coerceAtLeast(0)
            renderDropdown(setting, options, current, y, mouseX, mouseY)
            return
        }

        renderLabel(setting, y)
        val x = controlX()
        val w = controlW()
        val focused = focusedText == setting
        SkijaRenderer.rect(x, y, w, CONTROL_H, if (focused) CONTROL_HOVER else CONTROL, 6f)
        SkijaRenderer.hollowRect(x, y, w, CONTROL_H, 1.2f, if (focused) ACCENT else BORDER, 6f)
        val text = setting.get<String>()
        val shown = trimToWidth(text, w - 20f, 13f)
        SkijaRenderer.text(shown, x + 10f, y + 10f, 13f, TEXT)
        if (focused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            val cursorX = (x + 10f + SkijaRenderer.textWidth(shown, 13f, SkijaRenderer.defaultFont)).coerceAtMost(x + w - 8f)
            SkijaRenderer.rect(cursorX, y + 8f, 1f, 18f, TEXT)
        }
        hitBoxes += HitBox(HitKind.Text, setting, activeTab, x, y, w, CONTROL_H)
    }

    private fun renderImmutableText(setting: SettingImpl, y: Float) {
        val label = if (setting.annotation.dontRenderName) setting.get<String>() else setting.annotation.name
        SkijaRenderer.text(label, contentX, y + 7f, 14f, MUTED)
        if (!setting.annotation.dontRenderName && setting.get<String>().isNotBlank()) {
            SkijaRenderer.text(setting.get(), controlX(), y + 7f, 13f, MUTED)
        }
    }

    private fun renderColor(setting: SettingImpl, y: Float, mouseX: Double, mouseY: Double) {
        renderLabel(setting, y)
        val color = setting.get<Color>()
        val x = controlX()
        val channelW = (controlW() - 58f) / 4f
        val swatchColor = Color(color.red, color.green, color.blue, color.alpha).rgb
        SkijaRenderer.rect(x, y + 3f, 42f, 28f, swatchColor, 5f)
        SkijaRenderer.hollowRect(x, y + 3f, 42f, 28f, 1f, BORDER, 5f)
        hitBoxes += HitBox(HitKind.ColorSwatch, setting, activeTab, x, y + 3f, 42f, 28f)

        val values = intArrayOf(color.red, color.green, color.blue, color.alpha)
        val labels = arrayOf("R", "G", "B", "A")
        for (i in 0..3) {
            val barX = x + 54f + i * channelW
            val barY = y + 5f
            val hovered = contains(barX, barY, channelW - 8f, 24f, mouseX, mouseY)
            SkijaRenderer.rect(barX, barY, channelW - 8f, 24f, if (hovered) CONTROL_HOVER else CONTROL, 5f)
            val fill = ((channelW - 8f) * (values[i] / 255f)).coerceIn(0f, channelW - 8f)
            SkijaRenderer.rect(barX, barY, fill, 24f, channelColor(i), 5f)
            SkijaRenderer.text("${labels[i]} ${values[i]}", barX + 6f, barY + 7f, 10.5f, TEXT)
            hitBoxes += HitBox(HitKind.ColorChannel, setting, activeTab, barX, barY, channelW - 8f, 24f, i)
        }

        if (isColorPickerOpen(setting)) {
            renderColorPicker(setting, y + 38f, mouseX, mouseY)
        }
    }

    private fun renderColorPicker(setting: SettingImpl, y: Float, mouseX: Double, mouseY: Double) {
        val color = setting.get<Color>()
        val hsb = Color.RGBtoHSB(color.red, color.green, color.blue, null)
        val x = controlX()
        val w = controlW()
        val pickerW = (w - 22f).coerceAtMost(COLOR_PICKER_W).coerceAtLeast(180f)
        val svSize = (pickerW - 34f).coerceAtMost(142f).coerceAtLeast(112f)
        val hueX = x + svSize + 12f
        val panelH = svSize + 22f

        SkijaRenderer.rect(x, y, pickerW, panelH, PANEL_ALT, 6f)
        SkijaRenderer.hollowRect(x, y, pickerW, panelH, 1f, BORDER, 6f)

        val svX = x + 8f
        val svY = y + 8f
        val hueColor = Color.getHSBColor(hsb[0], 1f, 1f).rgb or 0xFF000000.toInt()
        val columns = svSize.toInt().coerceAtLeast(1)
        for (column in 0 until columns) {
            val saturation = column.toFloat() / (columns - 1).coerceAtLeast(1)
            val columnColor = mixColor(0xFFFFFFFF.toInt(), hueColor, saturation)
            SkijaRenderer.rect(svX + column, svY, 1f, svSize, columnColor)
        }
        SkijaRenderer.gradientRect(svX, svY, svSize, svSize, 0x00000000, 0xFF000000.toInt(), Gradient.TopToBottom, 0f)
        SkijaRenderer.hollowRect(svX, svY, svSize, svSize, 1f, BORDER, 3f)

        val markerX = svX + hsb[1] * svSize
        val markerY = svY + (1f - hsb[2]) * svSize
        SkijaRenderer.circle(markerX, markerY, 5f, 0xFFFFFFFF.toInt())
        SkijaRenderer.hollowRect(markerX - 5f, markerY - 5f, 10f, 10f, 1f, 0xFF000000.toInt(), 5f)

        val hueH = svSize
        val hueSteps = hueH.toInt().coerceAtLeast(1)
        for (step in 0 until hueSteps) {
            val hue = step.toFloat() / (hueSteps - 1).coerceAtLeast(1)
            SkijaRenderer.rect(hueX, svY + step, 12f, 1f, Color.getHSBColor(hue, 1f, 1f).rgb or 0xFF000000.toInt())
        }
        SkijaRenderer.hollowRect(hueX, svY, 12f, hueH, 1f, BORDER, 3f)
        val hueMarkerY = svY + hsb[0] * hueH
        SkijaRenderer.hollowRect(hueX - 2f, hueMarkerY - 2f, 16f, 4f, 1.5f, 0xFFFFFFFF.toInt(), 2f)

        val previewX = hueX + 22f
        val previewW = (x + pickerW - previewX - 8f).coerceAtLeast(20f)
        SkijaRenderer.rect(previewX, svY, previewW, 28f, color.rgb, 4f)
        SkijaRenderer.hollowRect(previewX, svY, previewW, 28f, 1f, BORDER, 4f)
        SkijaRenderer.text("#%02X%02X%02X".format(color.red, color.green, color.blue), previewX, svY + 38f, 11f, MUTED)

        hitBoxes += HitBox(HitKind.ColorPickerSv, setting, activeTab, svX, svY, svSize, svSize)
        hitBoxes += HitBox(HitKind.ColorPickerHue, setting, activeTab, hueX, svY, 12f, hueH)
    }

    private fun renderButton(setting: SettingImpl, y: Float, mouseX: Double, mouseY: Double) {
        val name = setting.annotation.name
        val x = if (setting.annotation.dontRenderName) contentX else controlX()
        val w = if (setting.annotation.dontRenderName) contentW else controlW()
        if (!setting.annotation.dontRenderName) renderLabel(setting, y)
        val hovered = contains(x, y, w, CONTROL_H, mouseX, mouseY)
        SkijaRenderer.rect(x, y, w, CONTROL_H, if (hovered) ACCENT_HOVER else CONTROL, 6f)
        SkijaRenderer.text(name, x + 10f, y + 10f, 13f, if (hovered) 0xFF101010.toInt() else TEXT)
        hitBoxes += HitBox(HitKind.Button, setting, activeTab, x, y, w, CONTROL_H)
    }

    private fun renderDropdown(
        setting: SettingImpl,
        options: List<String>,
        selectedIndex: Int,
        y: Float,
        mouseX: Double,
        mouseY: Double
    ) {
        renderLabel(setting, y)
        val x = controlX()
        val w = controlW()
        val hovered = contains(x, y, w, CONTROL_H, mouseX, mouseY)
        SkijaRenderer.rect(x, y, w, CONTROL_H, if (hovered) CONTROL_HOVER else CONTROL, 6f)
        val label = options.getOrNull(selectedIndex) ?: "No image"
        SkijaRenderer.text(trimToWidth(label, w - 34f, 13f), x + 10f, y + 10f, 13f, TEXT)
        SkijaRenderer.text(if (isDropdownOpen(setting)) "v" else ">", x + w - 22f, y + 10f, 13f, MUTED)
        hitBoxes += HitBox(HitKind.Dropdown, setting, activeTab, x, y, w, CONTROL_H)

        if (!isDropdownOpen(setting)) return

        var optionY = y + CONTROL_H + 4f
        options.forEachIndexed { index, option ->
            val optionHovered = contains(x, optionY, w, OPTION_H, mouseX, mouseY)
            val selected = index == selectedIndex
            SkijaRenderer.rect(x, optionY, w, OPTION_H, when {
                selected -> ACCENT
                optionHovered -> CONTROL_HOVER
                else -> PANEL_ALT
            }, 4f)
            SkijaRenderer.text(
                trimToWidth(option, w - 20f, 12.5f),
                x + 10f,
                optionY + 7f,
                12.5f,
                if (selected) 0xFF111111.toInt() else TEXT
            )
            hitBoxes += HitBox(HitKind.DropdownOption, setting, activeTab, x, optionY, w, OPTION_H, index)
            optionY += OPTION_H
        }
    }

    private fun renderSlider(
        setting: SettingImpl,
        value: Float,
        min: Float,
        max: Float,
        y: Float,
        mouseX: Double,
        mouseY: Double,
        convert: (Float) -> Any
    ) {
        renderLabel(setting, y)
        val x = controlX()
        val w = controlW()
        val trackY = y + 15f
        val fraction = ((value - min) / (max - min).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        val hovered = contains(x, y, w, CONTROL_H, mouseX, mouseY)
        SkijaRenderer.rect(x, y, w, CONTROL_H, if (hovered) CONTROL_HOVER else CONTROL, 6f)
        SkijaRenderer.rect(x + 10f, trackY, w - 80f, 4f, TRACK, 2f)
        SkijaRenderer.rect(x + 10f, trackY, (w - 80f) * fraction, 4f, ACCENT, 2f)
        SkijaRenderer.circle(x + 10f + (w - 80f) * fraction, trackY + 2f, 6f, ACCENT_HOVER)
        val label = when (setting.type) {
            Type.TFloat -> String.format("%.2f", value)
            else -> convert(value).toString()
        }
        SkijaRenderer.text(label, x + w - 58f, y + 10f, 12f, TEXT)
        hitBoxes += HitBox(HitKind.Slider, setting, activeTab, x + 10f, y, w - 80f, CONTROL_H)
    }

    private fun renderLabel(setting: SettingImpl, y: Float) {
        if (setting.annotation.dontRenderName) return
        SkijaRenderer.text(setting.annotation.name, contentX, y + 9f, 13f, TEXT)
    }

    private fun renderSeparator(y: Float) {
        SkijaRenderer.rect(contentX, y, contentW, 1f, BORDER)
    }

    private fun renderScrollbar(scroll: Float, maxScroll: Float) {
        if (maxScroll <= 0f) return
        val x = width - 8f
        val trackH = contentH - 28f
        val thumbH = max(32f, trackH * (contentH / activeContentHeight.coerceAtLeast(contentH)))
        val thumbY = contentY + 28f + (trackH - thumbH) * (scroll / maxScroll)
        SkijaRenderer.rect(x, contentY + 28f, 3f, trackH, TRACK, 2f)
        SkijaRenderer.rect(x, thumbY, 3f, thumbH, MUTED, 2f)
    }

    private fun addOffscreenHitBoxes(setting: SettingImpl, y: Float) {
        if (isDropdownOpen(setting)) {
            val x = controlX()
            val w = controlW()
            dropdownOptions(setting).forEachIndexed { index, _ ->
                hitBoxes += HitBox(HitKind.DropdownOption, setting, activeTab, x, y + CONTROL_H + 4f + index * OPTION_H, w, OPTION_H, index)
            }
        }
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_1) {
            focusedText = null
            openedDropdownId = null
            openedColorPickerId = null
            return super.mouseClicked(click, doubled)
        }

        val hit = hitBoxes.asReversed().firstOrNull { it.contains(click.x(), click.y()) }
        if (hit == null) {
            focusedText = null
            openedDropdownId = null
            openedColorPickerId = null
            return true
        }

        when (hit.kind) {
            HitKind.Tab -> {
                activeTab = hit.tabIndex
                focusedText = null
                openedDropdownId = null
                openedColorPickerId = null
            }
            HitKind.Boolean -> {
                openedColorPickerId = null
                hit.setting?.set(!(hit.setting.get<Boolean>()))
            }
            HitKind.Slider -> {
                openedColorPickerId = null
                dragging = hit
                updateSlider(hit, click.x())
            }
            HitKind.Dropdown -> {
                focusedText = null
                openedColorPickerId = null
                openedDropdownId = if (isDropdownOpen(hit.setting)) null else settingId(hit.setting)
            }
            HitKind.DropdownOption -> {
                openedColorPickerId = null
                applyDropdownOption(hit)
                openedDropdownId = null
            }
            HitKind.Text -> {
                focusedText = hit.setting
                openedDropdownId = null
                openedColorPickerId = null
            }
            HitKind.Button -> {
                openedColorPickerId = null
                hit.setting?.get<() -> Unit>()?.invoke()
            }
            HitKind.ColorSwatch -> {
                focusedText = null
                openedDropdownId = null
                openedColorPickerId = if (isColorPickerOpen(hit.setting)) null else settingId(hit.setting)
            }
            HitKind.ColorPickerSv -> {
                dragging = hit
                updateColorPickerSv(hit, click.x(), click.y())
            }
            HitKind.ColorPickerHue -> {
                dragging = hit
                updateColorPickerHue(hit, click.y())
            }
            HitKind.ColorChannel -> {
                dragging = hit
                updateColorChannel(hit, click.x())
            }
        }
        return true
    }

    override fun mouseDragged(click: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
        val activeDrag = dragging ?: return super.mouseDragged(click, offsetX, offsetY)
        when (activeDrag.kind) {
            HitKind.Slider -> updateSlider(activeDrag, click.x())
            HitKind.ColorPickerSv -> updateColorPickerSv(activeDrag, click.x(), click.y())
            HitKind.ColorPickerHue -> updateColorPickerHue(activeDrag, click.y())
            HitKind.ColorChannel -> updateColorChannel(activeDrag, click.x())
            else -> {}
        }
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        dragging = null
        return super.mouseReleased(click)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (!contains(contentX, contentY, contentW, contentH, mouseX, mouseY)) return false
        val tab = tabs.getOrNull(activeTab) ?: return false
        val current = scrollOffsets[tab.name] ?: 0f
        val maxScroll = max(0f, activeContentHeight - contentH + 24f)
        scrollOffsets[tab.name] = (current - verticalAmount.toFloat() * 24f).coerceIn(0f, maxScroll)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val focused = focusedText
        if (openedDropdownId != null && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            openedDropdownId = null
            return true
        }
        if (focused != null) {
            when (event.key()) {
                GLFW.GLFW_KEY_BACKSPACE -> {
                    val current = focused.get<String>()
                    if (current.isNotEmpty()) focused.set(current.dropLast(1))
                    return true
                }
                GLFW.GLFW_KEY_DELETE -> {
                    focused.set("")
                    return true
                }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_ESCAPE -> {
                    focusedText = null
                    return true
                }
            }
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val focused = focusedText ?: return false
        if (!event.isAllowedChatCharacter()) return false
        focused.set(focused.get<String>() + event.codepointAsString())
        return true
    }

    override fun onClose() {
        dragging = null
        focusedText = null
        openedDropdownId = null
        openedColorPickerId = null
        saveData()
        super.onClose()
    }

    private fun updateSlider(hit: HitBox, mouseX: Double) {
        val setting = hit.setting ?: return
        val fraction = ((mouseX.toFloat() - hit.x) / hit.width).coerceIn(0f, 1f)
        when (setting.type) {
            Type.TInteger -> {
                val current = setting.get<Int>()
                val (min, max) = numberRange(setting, current.toFloat())
                setting.set((min + (max - min) * fraction).roundToInt())
            }
            Type.TFloat -> {
                val current = setting.get<Float>()
                val (min, max) = numberRange(setting, current)
                setting.set(min + (max - min) * fraction)
            }
            else -> {}
        }
    }

    private fun updateColorChannel(hit: HitBox, mouseX: Double) {
        val setting = hit.setting ?: return
        val color = setting.get<Color>()
        val values = intArrayOf(color.red, color.green, color.blue, color.alpha)
        values[hit.index] = (((mouseX.toFloat() - hit.x) / hit.width).coerceIn(0f, 1f) * 255f).roundToInt()
        setting.set(Color(values[0], values[1], values[2], values[3]))
    }

    private fun updateColorPickerSv(hit: HitBox, mouseX: Double, mouseY: Double) {
        val setting = hit.setting ?: return
        val color = setting.get<Color>()
        val hsb = Color.RGBtoHSB(color.red, color.green, color.blue, null)
        val saturation = ((mouseX.toFloat() - hit.x) / hit.width).coerceIn(0f, 1f)
        val brightness = (1f - ((mouseY.toFloat() - hit.y) / hit.height)).coerceIn(0f, 1f)
        val rgb = Color.getHSBColor(hsb[0], saturation, brightness)
        setting.set(Color(rgb.red, rgb.green, rgb.blue, color.alpha))
    }

    private fun updateColorPickerHue(hit: HitBox, mouseY: Double) {
        val setting = hit.setting ?: return
        val color = setting.get<Color>()
        val hsb = Color.RGBtoHSB(color.red, color.green, color.blue, null)
        val hue = ((mouseY.toFloat() - hit.y) / hit.height).coerceIn(0f, 1f)
        val rgb = Color.getHSBColor(hue, hsb[1], hsb[2])
        setting.set(Color(rgb.red, rgb.green, rgb.blue, color.alpha))
    }

    private fun applyDropdownOption(hit: HitBox) {
        val setting = hit.setting ?: return
        if (setting.type == Type.TInteger) {
            setting.set(hit.index)
            return
        }
        if (setting.prop.name == "imageSelection") {
            dropdownOptions(setting).getOrNull(hit.index)?.let { setting.set(it) }
        }
    }

    private fun rowHeight(setting: SettingImpl): Float = when (setting.type) {
        Type.TColor -> 38f
        Type.TImmutableText -> 30f
        else -> 40f
    }

    private fun dropdownOptions(setting: SettingImpl): List<String> = when {
        setting.type == Type.TInteger -> setting.annotation.combo.toList()
        setting.prop.name == "imageSelection" -> MapImageLoader.getImageNames().toList()
        else -> emptyList()
    }

    private fun numberRange(setting: SettingImpl, current: Float): Pair<Float, Float> {
        val min = setting.annotation.min.toFloat()
        val max = setting.annotation.max.toFloat()
        if (max > min) return min to max
        if (setting.prop.name.contains("alpha", ignoreCase = true)) return 0f to 255f
        return 0f to max(1f, current * 2f)
    }

    private fun isDropdownOpen(setting: SettingImpl?): Boolean =
        setting != null && openedDropdownId == settingId(setting)

    private fun isColorPickerOpen(setting: SettingImpl?): Boolean =
        setting != null && openedColorPickerId == settingId(setting)

    private fun settingId(setting: SettingImpl?): String? =
        setting?.let { "${tabs.getOrNull(activeTab)?.name}:${it.prop.name}" }

    private fun controlX(): Float = contentX + (contentW * 0.42f).coerceAtLeast(150f)

    private fun controlW(): Float = (contentX + contentW - controlX()).coerceAtLeast(140f)

    private fun contains(x: Float, y: Float, w: Float, h: Float, mouseX: Double, mouseY: Double): Boolean =
        mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h

    private fun trimToWidth(value: String, width: Float, size: Float): String {
        if (SkijaRenderer.textWidth(value, size, SkijaRenderer.defaultFont) <= width) return value
        var result = value
        while (result.isNotEmpty() && SkijaRenderer.textWidth("$result...", size, SkijaRenderer.defaultFont) > width) {
            result = result.dropLast(1)
        }
        return "$result..."
    }

    private fun mixColor(from: Int, to: Int, fraction: Float): Int {
        val amount = fraction.coerceIn(0f, 1f)
        val a = (((from ushr 24) and 0xFF) + (((to ushr 24) and 0xFF) - ((from ushr 24) and 0xFF)) * amount).roundToInt()
        val r = (((from ushr 16) and 0xFF) + (((to ushr 16) and 0xFF) - ((from ushr 16) and 0xFF)) * amount).roundToInt()
        val g = (((from ushr 8) and 0xFF) + (((to ushr 8) and 0xFF) - ((from ushr 8) and 0xFF)) * amount).roundToInt()
        val b = ((from and 0xFF) + ((to and 0xFF) - (from and 0xFF)) * amount).roundToInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun channelColor(index: Int): Int = when (index) {
        0 -> 0xFFE74C3C.toInt()
        1 -> 0xFF27AE60.toInt()
        2 -> 0xFF3498DB.toInt()
        else -> 0xFFBFC6D0.toInt()
    }

    private const val HEADER_H = 50f
    private const val CONTROL_H = 34f
    private const val OPTION_H = 27f
    private const val COLOR_PICKER_W = 236f
    private const val COLOR_PICKER_H = 172f
    private const val BG = 0xF00F1115.toInt()
    private const val PANEL = 0xFF171A20.toInt()
    private const val PANEL_ALT = 0xFF20242B.toInt()
    private const val SIDE = 0xFF12151B.toInt()
    private const val CONTROL = 0xFF252A32.toInt()
    private const val CONTROL_HOVER = 0xFF303641.toInt()
    private const val TRACK = 0xFF111318.toInt()
    private const val BORDER = 0xFF3A414D.toInt()
    private const val TEXT = 0xFFF2F4F7.toInt()
    private const val MUTED = 0xFF9AA3AF.toInt()
    private const val ACCENT = 0xFFFFB86B.toInt()
    private const val ACCENT_HOVER = 0xFFFFC982.toInt()
}
