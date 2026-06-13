package com.ricedotwho.dtmap.utils

import com.mojang.blaze3d.opengl.DirectStateAccess
import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import java.lang.reflect.Field
import java.lang.reflect.Method


class GlDeviceHelper {
    var backendField: Field? = null
    var directStateAccessMethod: Method? = null
    val getFboMethod: Method? = null

    fun getFbo(colorTex: GpuTexture?, depthTex: GlTexture): Int {
        try {
            val gpuDevice = RenderSystem.getDevice()

            if (backendField == null) {
                backendField = gpuDevice.javaClass.getDeclaredField("backend")
                backendField?.setAccessible(true)
            }
            val device = backendField?.get(gpuDevice)

            if (directStateAccessMethod == null) {
                directStateAccessMethod = device?.javaClass?.getDeclaredMethod("directStateAccess")
                directStateAccessMethod?.setAccessible(true)
            }
            val dsa: DirectStateAccess = directStateAccessMethod?.invoke(device) as DirectStateAccess? ?: return 0
            return depthTex.getFbo(dsa, colorTex)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}