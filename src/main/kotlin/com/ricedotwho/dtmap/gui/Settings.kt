package com.ricedotwho.dtmap.gui

import com.ricedotwho.dtmap.utils.MapImageLoader
import imgui.ImGui
import imgui.ImGuiIO
import imgui.flag.ImGuiInputTextFlags
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImInt
import imgui.type.ImString
import net.fabricmc.loader.api.FabricLoader
import org.reflections.Reflections
import org.reflections.scanners.Scanners
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.kotlinProperty

object Settings : ImGuiHandler.RenderInterface("DtMapSettings") {
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
                return when (typ.classifier) {
                    Int::class -> TInteger
                    Float::class -> TFloat
                    Boolean::class -> TBoolean
                    String::class -> if (prop is KMutableProperty<*>) TText else TImmutableText
                    Color::class -> TColor
                    Function::class -> TButton
                    else -> throw IllegalArgumentException("invalid type of setting: $typ")
                }
            }
        }
    }

    data class SettingImpl(val annotation: Setting, val type: Type, val owner: Any, val prop: KProperty<*>) {
        var additional: Any? = null

        fun update() =
            if (type == Type.TText) additional =  ImString(get<String>()) else { }

        fun <T> get(): T {
            val casted = prop as KProperty<T>
            return casted.getter.call(owner)
        }

        fun set(b: Any) {
            val casted = prop as KMutableProperty<Any>
            casted.setter.call(owner, b)
        }
    }
    data class TabImpl(val name: String, val settings: List<SettingImpl>, val obj: Any, val clazz: KClass<*>)

    var tabs: List<TabImpl>

    init {
        val reflections = Reflections("com.ricedotwho.dtmap.config")

        tabs = reflections[Scanners.TypesAnnotated.with(Tab::class.java).asClass<Any>()].mapNotNull { tab ->
            val instance = tab.kotlin.objectInstance ?: throw IllegalArgumentException("You can't have tabs that are not objects: ${tab.name}")

            TabImpl(
                tab.getAnnotation(Tab::class.java).tabName,
                instance::class.java.declaredFields.mapNotNull { javaProp ->
                    val prop = javaProp.kotlinProperty ?: return@mapNotNull null
                    if (prop !is KProperty) return@mapNotNull null
                    val annotation = prop.findAnnotation<Setting>() ?: return@mapNotNull null
                    SettingImpl(annotation, Type.fromKType(prop, prop.returnType), instance, prop)
                },
                instance,
                tab.kotlin
            )
        }

        loadData()
        tabs.forEach { it.settings.forEach { it.update() } }
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

    /**
     * Validate that the selected image still exists. Called after loading settings and after ImageManager initialization.
     */
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

    override fun render(io: ImGuiIO) {
        val viewPort = ImGui.getMainViewport()
        ImGui.setNextWindowPos(viewPort.pos)
        ImGui.setNextWindowSize(viewPort.size)
        ImGui.setNextWindowViewport(viewPort.id)

        val flags = ImGuiWindowFlags.NoTitleBar or ImGuiWindowFlags.NoResize or ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoCollapse
        if (ImGui.begin("DtMapGui", flags)) {
            if (ImGui.beginTabBar("bar")) {
                tabs.forEach { tab ->
                    if (ImGui.beginTabItem(tab.name)) {
                        tab.settings.forEach { setting ->
                            if (setting.annotation.sameLineBefore) {
                                ImGui.sameLine()
                            }

                            if (setting.annotation.lineBefore) {
                                ImGui.separator()
                            }

                            val name = if (setting.annotation.dontRenderName) {
                                "##${setting.prop.name}"
                            } else if (setting.annotation.renderNameLeft) {
                                ImGui.text(setting.annotation.name)
                                ImGui.sameLine()
                                "##${setting.prop.name}"
                            } else {
                                "${setting.annotation.name}##${setting.prop.name}"
                            }

                            when (setting.type) {
                                Type.TBoolean -> if (ImGui.checkbox(name, setting.get<Boolean>())) {
                                    setting.set(!setting.get<Boolean>())
                                }

                                Type.TInteger -> {
                                    val value = setting.get<Int>()
                                    if (setting.annotation.combo.isNotEmpty()) {
                                        val imInt = ImInt(value)
                                        if (ImGui.combo(name, imInt, setting.annotation.combo)) {
                                            setting.set(imInt.get())
                                        }
                                    } else {
                                        val i = IntArray(1) { value }
                                        if (ImGui.sliderInt(name, i, setting.annotation.min.toInt(), setting.annotation.max.toInt())) {
                                            setting.set(i[0])
                                        }
                                    }
                                }

                                Type.TFloat -> {
                                    val value = setting.get<Float>()
                                    val i = FloatArray(1) { value }
                                    if (ImGui.sliderFloat(name, i, setting.annotation.min.toFloat(), setting.annotation.max.toFloat())) {
                                        setting.set(i[0])
                                    }
                                }

                                Type.TColor -> {
                                    val value = setting.get<Color>()
                                    val c = value.getRGBComponents(null)
                                    if (ImGui.colorEdit4(name, c)) {
                                        setting.set(Color(c[0].coerceIn(0f, 1f), c[1].coerceIn(0f, 1f), c[2].coerceIn(0f, 1f), c[3].coerceIn(0f, 1f)))
                                    }
                                }

                                Type.TText -> {
                                    //special case for the image selection setting thing
                                    if (setting.prop.name == "imageSelection") {
                                        val imageNames = MapImageLoader.getImageNames()
                                        val currentSelection = setting.get<String>()
                                        val currentIndex = imageNames.indexOf(currentSelection).coerceAtLeast(0)
                                        val imInt = ImInt(currentIndex)

                                        if (ImGui.combo(name, imInt, imageNames, imageNames.size)) {
                                            val selectedIndex = imInt.get()
                                            if (selectedIndex >= 0 && selectedIndex < imageNames.size) {
                                                setting.set(imageNames[selectedIndex])
                                            }
                                        }
                                    } else {
                                        //regular string setting input
                                        val im = setting.additional as ImString
                                        if (ImGui.inputText(name, im, ImGuiInputTextFlags.CallbackResize)) {
                                            setting.set(im.get())
                                        }
                                    }
                                }

                                Type.TButton -> {
                                    if (ImGui.button(name)) {
                                        val action = setting.get<() -> Unit>()
                                        action()
                                    }
                                }

                                Type.TImmutableText -> {
                                    ImGui.text(setting.get<String>())
                                }
                            }

                            if (setting.annotation.sameLineAfter) {
                                ImGui.sameLine()
                            }

                            if (setting.annotation.lineAfter) {
                                ImGui.separator()
                            }
                        }

                        ImGui.endTabItem()
                    }
                }

                ImGui.endTabBar()
            }
        }

        ImGui.end()
    }

    override fun onClose() {
        saveData()
        super.onClose()
    }

    override fun isPauseScreen(): Boolean =
        false
}