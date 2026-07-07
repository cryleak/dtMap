package com.ricedotwho.dtmap.gui

import com.ricedotwho.dtmap.DtMap
import com.ricedotwho.dtmap.utils.render.skija.SkijaRenderer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

abstract class SkijaScreen(name: String) : Screen(Component.literal(name)) {
    fun open() {
        SkijaRenderer.invalidateOverlay()
        DtMap.mc.gui.setScreen(this)
    }

    abstract fun renderSkija(mouseX: Double, mouseY: Double, deltaTicks: Float)

    override fun extractBackground(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
    }

    override fun isPauseScreen(): Boolean = false
}
