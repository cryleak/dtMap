package com.ricedotwho.dtmap.utils.render.skija

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.vulkan.VulkanConst
import com.mojang.blaze3d.vulkan.VulkanDevice
import com.mojang.blaze3d.vulkan.VulkanGpuTexture
import com.ricedotwho.dtmap.DtMap
import com.ricedotwho.dtmap.DtMap.mc
import io.github.humbleui.skija.*
import io.github.humbleui.skija.svg.SVGDOM
import io.github.humbleui.types.RRect
import io.github.humbleui.types.Rect
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL13C
import org.lwjgl.opengl.GL14C
import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL20C
import org.lwjgl.opengl.GL30C
import org.lwjgl.opengl.GL31C
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK12
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URISyntaxException
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import io.github.humbleui.skija.Font as SkijaFont
import io.github.humbleui.skija.Image as SkijaImage

object SkijaRenderer {
    private const val TEXT_WIDTH_CACHE_SIZE = 4096
    private const val FILTER_CACHE_SIZE = 256
    private const val OVERLAY_LABEL = "dtMap Skija GUI Overlay"

    val defaultFont: Font

    private val fontManager = FontMgr.getDefault()
    private val colorSpace = ColorSpace.getSRGB()
    private val fonts = HashMap<Font, LoadedFont>()
    private val skijaFonts = HashMap<SkijaFontKey, SkijaFont>()
    private val images = HashMap<Image, LoadedImage>()
    private val alphaStack: Deque<Float> = ArrayDeque()
    private val boundsTrackers: Deque<BoundsTracker> = ArrayDeque()
    private val textWidthCache = object : LinkedHashMap<TextWidthKey, Float>(TEXT_WIDTH_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextWidthKey, Float>?): Boolean {
            return size > TEXT_WIDTH_CACHE_SIZE
        }
    }
    private val dropShadowFilters = object : LinkedHashMap<DropShadowFilterKey, ImageFilter>(FILTER_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DropShadowFilterKey, ImageFilter>?): Boolean {
            val remove = size > FILTER_CACHE_SIZE
            if (remove) {
                eldest?.value?.close()
            }
            return remove
        }
    }
    private val colorFilters = object : LinkedHashMap<Int, ColorFilter>(FILTER_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ColorFilter>?): Boolean {
            val remove = size > FILTER_CACHE_SIZE
            if (remove) {
                eldest?.value?.close()
            }
            return remove
        }
    }
    private var fillPaint: Paint? = Paint()
    private var strokePaint: Paint? = Paint()
    private var shadowPaint: Paint? = Paint()
    private var imagePaint: Paint? = Paint()

    private var backend: SkijaBackend? = null
    private var canvas: Canvas? = null
    private var rootSaveCount = 0
    private var drawing = false
    private var overlayTarget: TextureTarget? = null
    private var overlayValid = false
    private var overlayScreenWidth = 0f
    private var overlayScreenHeight = 0f
    private var currentAlpha = 1f
    private var scissor: Scissor? = null

    init {
        try {
            defaultFont = Font(
                "Default",
                SkijaRenderer::class.java.classLoader.getResourceAsStream("assets/Roboto-Regular.ttf")
                    ?: throw FileNotFoundException("assets/Roboto-Regular.ttf")
            )
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    @JvmStatic
    fun beginFrame(width: Float, height: Float) {
        beginOverlayFrame(width, height)
    }

    @JvmStatic
    fun endFrame() {
        endOverlayFrame()
    }

    @JvmStatic
    fun beginOverlayFrame(width: Float, height: Float): Boolean {
        check(!drawing) { "[SkijaRenderer] Already drawing but called beginOverlayFrame" }
        RenderSystem.assertOnRenderThread()

        val mainTarget: RenderTarget = mc.gameRenderer.mainRenderTarget()
        val framebuffer = ensureOverlayTarget(mainTarget)
        val colorTexture = framebuffer.getColorTexture()
            ?: error("dtMap Skija renderer cannot draw without an overlay color texture")
        val frameBackend = ensureBackend(colorTexture)
        val frameSurface = frameBackend.prepare(framebuffer, colorTexture)
        val frameCanvas = frameSurface.canvas
        frameCanvas.clear(0x00000000)
        canvas = frameCanvas
        rootSaveCount = frameCanvas.save()

        val pixelRatio = when {
            width > 0f -> framebuffer.width.toFloat() / width
            mc.window.screenWidth > 0 -> mc.window.width.toFloat() / mc.window.screenWidth.toFloat()
            else -> 1f
        }
        frameCanvas.scale(pixelRatio, pixelRatio)

        drawing = true
        overlayValid = false
        overlayScreenWidth = width
        overlayScreenHeight = height
        currentAlpha = 1f
        alphaStack.clear()
        scissor = null

        return true
    }

    @JvmStatic
    fun endOverlayFrame() {
        check(drawing) { "[SkijaRenderer] Not drawing but called endOverlayFrame" }

        val frameBackend = backend ?: error("Skija backend is not available")
        try {
            canvas?.restoreToCount(rootSaveCount)
            frameBackend.finish()
        } finally {
            canvas = null
            drawing = false
            overlayValid = true
            currentAlpha = 1f
            alphaStack.clear()
            scissor = null
        }
    }

    @JvmStatic
    fun compositeOverlay(): Boolean {
        RenderSystem.assertOnRenderThread()
        if (!overlayValid) return false

        val framebuffer: RenderTarget = mc.gameRenderer.mainRenderTarget()
        val overlay = overlayTarget ?: return false
        if (overlay.width != framebuffer.width || overlay.height != framebuffer.height) {
            invalidateOverlay()
            return false
        }

        return backend?.composite(overlay, framebuffer) ?: false
    }

    @JvmStatic
    fun invalidateOverlay() {
        overlayValid = false
    }

    @JvmStatic
    fun isOverlayRebuildRequired(width: Float, height: Float): Boolean {
        RenderSystem.assertOnRenderThread()
        val framebuffer: RenderTarget = mc.gameRenderer.mainRenderTarget()
        val mainTexture = framebuffer.getColorTexture() ?: return true
        val overlay = overlayTarget ?: return true
        val overlayTexture = overlay.getColorTexture() ?: return true

        return !overlayValid ||
            overlay.width != framebuffer.width ||
            overlay.height != framebuffer.height ||
            overlayScreenWidth != width ||
            overlayScreenHeight != height ||
            backendKind(overlayTexture) != backendKind(mainTexture)
    }

    private fun ensureOverlayTarget(mainTarget: RenderTarget): TextureTarget {
        val mainTexture = mainTarget.getColorTexture()
            ?: error("dtMap Skija renderer cannot allocate an overlay without a main color texture")
        val expectedBackend = backendKind(mainTexture)
        val existing = overlayTarget
        val existingTexture = existing?.getColorTexture()

        if (
            existing != null &&
            existingTexture != null &&
            existing.width == mainTarget.width &&
            existing.height == mainTarget.height &&
            backendKind(existingTexture) == expectedBackend
        ) {
            return existing
        }

        destroyOverlayTarget()
        return TextureTarget(OVERLAY_LABEL, mainTarget.width, mainTarget.height, false, GpuFormat.RGBA8_UNORM)
            .also {
                overlayTarget = it
                overlayValid = false
            }
    }

    private fun destroyOverlayTarget() {
        overlayValid = false
        overlayScreenWidth = 0f
        overlayScreenHeight = 0f
        backend?.close()
        backend = null
        overlayTarget?.destroyBuffers()
        overlayTarget = null
    }

    private fun ensureBackend(texture: GpuTexture): SkijaBackend {
        val current = backend
        if (current != null && current.supports(texture)) {
            return current
        }

        current?.close()
        val next = when (texture) {
            is VulkanGpuTexture -> VulkanSkijaBackend()
            is GlTexture -> OpenGlSkijaBackend()
            else -> error("dtMap Skija renderer does not support Minecraft texture backend ${texture.javaClass.name}")
        }
        backend = next
        return next
    }

    private fun backendKind(texture: GpuTexture): BackendKind {
        return when (texture) {
            is VulkanGpuTexture -> BackendKind.VULKAN
            is GlTexture -> BackendKind.OPENGL
            else -> BackendKind.UNKNOWN
        }
    }

    @JvmStatic
    fun cleanup() {
        destroyOverlayTarget()
        textWidthCache.clear()
        skijaFonts.values.forEach(SkijaFont::close)
        skijaFonts.clear()
        dropShadowFilters.values.forEach(ImageFilter::close)
        dropShadowFilters.clear()
        colorFilters.values.forEach(ColorFilter::close)
        colorFilters.clear()
        fillPaint?.close()
        fillPaint = null
        strokePaint?.close()
        strokePaint = null
        shadowPaint?.close()
        shadowPaint = null
        imagePaint?.close()
        imagePaint = null
        images.values.forEach(LoadedImage::close)
        images.clear()
        fonts.values.forEach(LoadedFont::close)
        fonts.clear()
    }

    fun pushBoundsTracker(tracker: BoundsTracker?) {
        if (tracker != null) {
            boundsTrackers.push(tracker)
        }
    }

    fun popBoundsTracker() {
        if (!boundsTrackers.isEmpty()) {
            boundsTrackers.pop()
        }
    }

    private fun recordBounds(x: Float, y: Float, width: Float, height: Float, kind: BoundsKind = BoundsKind.CONTENT) {
        if (boundsTrackers.isEmpty()) return
        if (width <= 0f || height <= 0f) return
        boundsTrackers.peek().record(x, y, width, height, kind)
    }

    fun save() {
        canvas().save()
        alphaStack.push(currentAlpha)
    }

    fun restore() {
        canvas().restore()
        currentAlpha = if (alphaStack.isEmpty()) 1f else alphaStack.pop()
    }

    fun push() {
        save()
    }

    fun pop() {
        restore()
    }

    fun scale(x: Float, y: Float) {
        canvas().scale(x, y)
    }

    fun translate(x: Float, y: Float) {
        canvas().translate(x, y)
    }

    fun rotate(amount: Float) {
        canvas().rotate(amount)
    }

    fun globalAlpha(amount: Float) {
        currentAlpha = amount.coerceIn(0f, 1f)
    }

    fun scissor(x: Float, y: Float, w: Float, h: Float) {
        resetScissor()
        pushScissor(x, y, w, h)
    }

    fun resetScissor() {
        val c = canvas()
        while (scissor != null) {
            c.restore()
            scissor = scissor?.previous
        }
    }

    fun pushScissor(x: Float, y: Float, w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        val c = canvas()
        c.save()
        c.clipRect(Rect.makeXYWH(x, y, w, h), ClipMode.INTERSECT, true)
        scissor = Scissor(scissor)
    }

    fun popScissor() {
        if (scissor == null) return
        canvas().restore()
        scissor = scissor?.previous
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int) {
        val minX = min(x1, x2) - thickness / 2f
        val minY = min(y1, y2) - thickness / 2f
        val width = abs(x1 - x2) + thickness
        val height = abs(y1 - y2) + thickness
        recordBounds(minX, minY, width, height)
        withPaint(color, PaintMode.STROKE, thickness) { paint ->
            paint.setStrokeCap(PaintStrokeCap.ROUND)
            canvas().drawLine(x1, y1, x2, y2, paint)
        }
    }

    fun polygon(xPoints: FloatArray, yPoints: FloatArray, nPoints: Int, color: Int) {
        if (nPoints < 3) return

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val points = FloatArray(nPoints * 2)
        for (i in 0..<nPoints) {
            val x = xPoints[i]
            val y = yPoints[i]
            points[i * 2] = x
            points[i * 2 + 1] = y
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }
        recordBounds(minX, minY, maxX - minX, maxY - minY)

        withPaint(color) { canvas().drawPolygon(points, it) }
    }

    fun drawHalfRoundedRect(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float, roundTop: Boolean) {
        if (w <= 0f || h <= 0f) return
        recordBounds(x, y, w, h)
        val clamped = radius.coerceAtMost(min(w * 0.5f, h * 0.5f))
        val radii = if (roundTop) {
            floatArrayOf(clamped, clamped, clamped, clamped, 0f, 0f, 0f, 0f)
        } else {
            floatArrayOf(0f, 0f, 0f, 0f, clamped, clamped, clamped, clamped)
        }
        withPaint(color) { canvas().drawRRect(RRect.makeComplexXYWH(x, y, w, h, radii), it) }
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float) {
        if (w <= 0f || h <= 0f) return
        recordBounds(x, y, w, h)
        withPaint(color) { paint ->
            canvas().drawRRect(RRect.makeXYWH(x, y, w, h + 0.5f, radius), paint)
        }
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        if (w <= 0f || h <= 0f) return
        recordBounds(x, y, w, h)
        withPaint(color) { paint ->
            canvas().drawRect(Rect.makeXYWH(x, y, w, h + 0.5f), paint)
        }
    }

    fun hollowRect(x: Float, y: Float, w: Float, h: Float, thickness: Float, color: Int, radius: Float) {
        if (w <= 0f || h <= 0f) return
        recordBounds(x, y, w, h)
        withPaint(color, PaintMode.STROKE, thickness) { paint ->
            paint.setStrokeJoin(PaintStrokeJoin.ROUND)
            canvas().drawRRect(RRect.makeXYWH(x, y, w, h, radius), paint)
        }
    }

    fun gradientHollowRect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        thickness: Float,
        color1: Int,
        color2: Int,
        gradient: Gradient,
        radius: Float
    ) {
        if (w <= 0f || h <= 0f) return
        recordBounds(x, y, w, h)
        withGradientPaint(color1, color2, x, y, w, h, gradient, PaintMode.STROKE, thickness) { paint ->
            paint.setStrokeJoin(PaintStrokeJoin.ROUND)
            canvas().drawRRect(RRect.makeXYWH(x, y, w, h, radius), paint)
        }
    }

    fun gradientRect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        color1: Int,
        color2: Int,
        gradient: Gradient,
        radius: Float
    ) {
        if (w <= 0f || h <= 0f) return
        recordBounds(x, y, w, h)
        withGradientPaint(color1, color2, x, y, w, h, gradient) { paint ->
            canvas().drawRRect(RRect.makeXYWH(x, y, w, h, radius), paint)
        }
    }

    fun gradientRectHorizontalCaps(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        color1: Int,
        color2: Int,
        radius: Float,
        roundLeft: Boolean,
        roundRight: Boolean
    ) {
        if (w <= 0f || h <= 0f) return
        recordBounds(x, y, w, h)
        val clamped = min(radius, min(w * 0.5f, h * 0.5f))
        val radii = floatArrayOf(
            if (roundLeft) clamped else 0f,
            if (roundLeft) clamped else 0f,
            if (roundRight) clamped else 0f,
            if (roundRight) clamped else 0f,
            if (roundRight) clamped else 0f,
            if (roundRight) clamped else 0f,
            if (roundLeft) clamped else 0f,
            if (roundLeft) clamped else 0f
        )
        withGradientPaint(color1, color2, x, y, w, h, Gradient.LeftToRight) { paint ->
            canvas().drawRRect(RRect.makeComplexXYWH(x, y, w, h, radii), paint)
        }
    }

    fun dropShadow(x: Float, y: Float, width: Float, height: Float, blur: Float, spread: Float, radius: Float) {
        if (width <= 0f || height <= 0f) return
        recordBounds(
            x - spread - blur,
            y - spread - blur,
            width + 2 * (spread + blur),
            height + 2 * (spread + blur),
            BoundsKind.SHADOW
        )

        val shadowColor = applyGlobalAlpha(0x7D000000)
        val filter = dropShadowFilter(blur, shadowColor)
        val paint = reusableShadowPaint()
        paint.reset()
        try {
            paint.setAntiAlias(true)
                .setColor(shadowColor)
                .setImageFilter(filter)
            canvas().drawRRect(
                RRect.makeXYWH(
                    x - spread,
                    y - spread,
                    width + spread * 2f,
                    height + spread * 2f,
                    radius + spread
                ),
                paint
            )
        } finally {
            paint.reset()
        }
    }

    fun circle(x: Float, y: Float, radius: Float, color: Int) {
        if (radius <= 0f) return
        recordBounds(x - radius, y - radius, radius * 2f, radius * 2f)
        withPaint(color) { paint ->
            canvas().drawCircle(x, y, radius, paint)
        }
    }

    @JvmOverloads
    fun text(text: String, x: Float, y: Float, size: Float, color: Int, font: Font = defaultFont) {
        try {
            val measuredWidth = textWidth(text, size, font)
            recordBounds(x, y, measuredWidth, size)
            withSkijaFont(font, size) { skFont ->
                withPaint(color) { paint ->
                    canvas().drawString(text, x, y - skFont.metrics.ascent, skFont, paint)
                }
            }
        } catch (e: IOException) {
            DtMap.LOGGER.error(e.toString())
        }
    }

    fun textShadow(text: String, x: Float, y: Float, size: Float, color: Int, font: Font) {
        try {
            val measuredWidth = textWidth(text, size, font)
            recordBounds(x - 1f, y - 1f, measuredWidth + 4f, size + 4f)
            text(text, x + 3f, y + 3f, size, 0xFF000000.toInt(), font)
            text(text, x, y, size, color, font)
        } catch (e: IOException) {
            DtMap.LOGGER.error(e.toString())
        }
    }

    fun textWidth(text: String, size: Float, font: Font): Float {
        return try {
            val key = TextWidthKey(text, size, font)
            textWidthCache[key] ?: withSkijaFont(font, size) { skFont ->
                skFont.measureTextWidth(text).also { textWidthCache[key] = it }
            }
        } catch (e: IOException) {
            DtMap.LOGGER.error(e.toString())
            0f
        }
    }

    fun drawWrappedString(
        text: String,
        x: Float,
        y: Float,
        w: Float,
        size: Float,
        color: Int,
        font: Font,
        lineHeight: Float
    ) {
        try {
            val lines = wrapText(text, w, size, font)
            val actualLineHeight = size * lineHeight
            val height = if (lines.isEmpty()) 0f else actualLineHeight * lines.size
            recordBounds(x, y, min(w, lines.maxOfOrNull { textWidth(it, size, font) } ?: 0f), height)
            lines.forEachIndexed { index, line ->
                text(line, x, y + index * actualLineHeight, size, color, font)
            }
        } catch (e: IOException) {
            DtMap.LOGGER.error(e.toString())
        }
    }

    fun wrappedTextBounds(text: String, w: Float, size: Float, font: Font, lineHeight: Float): FloatArray? {
        return try {
            val lines = wrapText(text, w, size, font)
            val actualLineHeight = size * lineHeight
            floatArrayOf(
                0f,
                0f,
                lines.maxOfOrNull { textWidth(it, size, font) } ?: 0f,
                if (lines.isEmpty()) 0f else actualLineHeight * lines.size
            )
        } catch (e: IOException) {
            DtMap.LOGGER.error(e.toString())
            null
        }
    }

    fun image(image: Image, x: Float, y: Float, w: Float, h: Float, radius: Float) {
        if (w <= 0f || h <= 0f) return
        recordBounds(x, y, w, h)
        drawImage(image, x, y, w, h, radius, null)
    }

    fun image(image: Image, x: Float, y: Float, w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        recordBounds(x, y, w, h)
        drawImage(image, x, y, w, h, 0f, null)
    }

    fun image(image: Image, x: Float, y: Float, w: Float, h: Float, color: Int) {
        if (w <= 0f || h <= 0f) return
        recordBounds(x, y, w, h)
        drawImage(image, x, y, w, h, 0f, color)
    }

    @Throws(IOException::class, URISyntaxException::class)
    fun createImage(resourcePath: String): Image? {
        val image = images.keys.firstOrNull { it.identifier == resourcePath } ?: Image(resourcePath)
        return createImage(image)
    }

    @Throws(IOException::class, URISyntaxException::class)
    fun createImage(image: Image): Image {
        val loaded = images.computeIfAbsent(image) { loadImage(it) }
        loaded.count++
        return image
    }

    fun deleteImage(image: Image?) {
        if (image == null) return
        val loaded = images[image] ?: return
        loaded.count--
        if (loaded.count <= 0) {
            loaded.close()
            images.remove(image)
        }
    }

    fun red(rgba: Int): Byte {
        return ((rgba shr 16) and 0xFF).toByte()
    }

    fun green(rgba: Int): Byte {
        return ((rgba shr 8) and 0xFF).toByte()
    }

    fun blue(rgba: Int): Byte {
        return (rgba and 0xFF).toByte()
    }

    fun alpha(rgba: Int): Byte {
        return ((rgba shr 24) and 0xFF).toByte()
    }

    private interface SkijaBackend : AutoCloseable {
        fun supports(texture: GpuTexture): Boolean
        fun prepare(framebuffer: RenderTarget, texture: GpuTexture): Surface
        fun finish()
        fun composite(overlay: RenderTarget, output: RenderTarget): Boolean
    }

    private class VulkanSkijaBackend : SkijaBackend {
        private var directContext: DirectContext? = null
        private var surface: Surface? = null
        private var renderTarget: BackendRenderTarget? = null
        private var surfaceTexture: VulkanGpuTexture? = null
        private var frameSurface: Surface? = null
        private var overlaySnapshot: SkijaImage? = null
        private var compositeSurface: Surface? = null
        private var compositeRenderTarget: BackendRenderTarget? = null
        private var compositeTexture: VulkanGpuTexture? = null

        override fun supports(texture: GpuTexture): Boolean {
            return texture is VulkanGpuTexture
        }

        override fun prepare(framebuffer: RenderTarget, texture: GpuTexture): Surface {
            check(texture is VulkanGpuTexture) {
                "dtMap Skija Vulkan backend received ${texture.javaClass.name}"
            }

            // Minecraft submits its queued render commands after GameRenderer.render returns.
            // Skija draws before that blit, so force the queued main-target work to execute first.
            RenderSystem.getDevice().createCommandEncoder().submit()

            val context = ensureContext()
            ensureSurface(context, texture)
            val prepared = surface ?: error("Skija Vulkan surface was not created")
            frameSurface = prepared
            return prepared
        }

        override fun finish() {
            val prepared = frameSurface
            directContext?.flushAndSubmit(prepared, false)
            overlaySnapshot?.close()
            overlaySnapshot = prepared?.makeImageSnapshot()
            frameSurface = null
        }

        override fun composite(overlay: RenderTarget, output: RenderTarget): Boolean {
            val overlayTexture = overlay.getColorTexture() as? VulkanGpuTexture ?: return false
            val outputTexture = output.getColorTexture() as? VulkanGpuTexture ?: return false
            val current = surfaceTexture ?: return false
            val snapshot = overlaySnapshot ?: return false
            if (current.vkImage() != overlayTexture.vkImage()) return false

            RenderSystem.getDevice().createCommandEncoder().submit()

            val context = ensureContext()
            val outputSurface = ensureCompositeSurface(context, outputTexture)
            outputSurface.canvas.drawImageRect(
                snapshot,
                Rect.makeWH(output.width.toFloat(), output.height.toFloat())
            )
            directContext?.flushAndSubmit(outputSurface, false)
            return true
        }

        override fun close() {
            releaseSurface()
            releaseCompositeSurface()
            directContext?.close()
            directContext = null
        }

        private fun ensureContext(): DirectContext {
            directContext?.let { return it }

            val backend = RenderSystem.getDevice().backend
            check(backend is VulkanDevice) {
                "dtMap Skija Vulkan backend requires Minecraft's Vulkan renderer"
            }

            val instance = backend.instance().vkInstance()
            val device = backend.vkDevice()
            val physicalDevice = device.physicalDevice
            val queue = backend.graphicsQueue().vkQueue()
            val functionProvider = VK.getFunctionProvider()

            val context = DirectContext.makeVulkan(
                instance.address(),
                physicalDevice.address(),
                device.address(),
                queue.address(),
                backend.graphicsQueue().queueFamilyIndex(),
                functionProvider.getFunctionAddress("vkGetInstanceProcAddr"),
                functionProvider.getFunctionAddress("vkGetDeviceProcAddr"),
                VK12.VK_API_VERSION_1_2
            )
            directContext = context
            return context
        }

        private fun ensureSurface(context: DirectContext, texture: VulkanGpuTexture) {
            val width = texture.getWidth(0)
            val height = texture.getHeight(0)
            val current = surfaceTexture
            if (
                surface != null &&
                renderTarget != null &&
                current != null &&
                current.vkImage() == texture.vkImage() &&
                surface?.width == width &&
                surface?.height == height
            ) {
                return
            }

            releaseSurface()

            val target = makeRenderTarget(texture)
            renderTarget = target
            surface = Surface.wrapBackendRenderTarget(
                context,
                target,
                SurfaceOrigin.BOTTOM_LEFT,
                ColorType.RGBA_8888,
                colorSpace
            )
            surfaceTexture = texture
        }

        private fun makeRenderTarget(texture: VulkanGpuTexture): BackendRenderTarget {
            val width = texture.getWidth(0)
            val height = texture.getHeight(0)
            return BackendRenderTarget.makeVulkan(
                width,
                height,
                texture.vkImage(),
                VK10.VK_IMAGE_TILING_OPTIMAL,
                VK10.VK_IMAGE_LAYOUT_GENERAL,
                VulkanConst.toVk(GpuFormat.RGBA8_UNORM),
                VulkanConst.textureUsageToVk(texture.usage(), texture.format),
                1,
                texture.mipLevels
            )
        }

        private fun ensureCompositeSurface(context: DirectContext, texture: VulkanGpuTexture): Surface {
            val width = texture.getWidth(0)
            val height = texture.getHeight(0)
            val current = compositeTexture
            if (
                compositeSurface != null &&
                compositeRenderTarget != null &&
                current != null &&
                current.vkImage() == texture.vkImage() &&
                compositeSurface?.width == width &&
                compositeSurface?.height == height
            ) {
                return compositeSurface ?: error("Skija Vulkan composite surface was not created")
            }

            releaseCompositeSurface()

            val target = makeRenderTarget(texture)
            compositeRenderTarget = target
            compositeSurface = Surface.wrapBackendRenderTarget(
                context,
                target,
                SurfaceOrigin.BOTTOM_LEFT,
                ColorType.RGBA_8888,
                colorSpace
            )
            compositeTexture = texture
            return compositeSurface ?: error("Skija Vulkan composite surface was not created")
        }

        private fun releaseCompositeSurface() {
            compositeSurface?.close()
            compositeSurface = null
            compositeRenderTarget?.close()
            compositeRenderTarget = null
            compositeTexture = null
        }

        private fun releaseSurface() {
            frameSurface = null
            overlaySnapshot?.close()
            overlaySnapshot = null
            surface?.close()
            surface = null
            renderTarget?.close()
            renderTarget = null
            surfaceTexture = null
        }
    }

    private class OpenGlSkijaBackend : SkijaBackend {
        private var directContext: DirectContext? = null
        private var surface: Surface? = null
        private var renderTarget: BackendRenderTarget? = null
        private var surfaceTextureId = 0
        private var framebufferId = 0
        private var frameSurface: Surface? = null
        private var frameState: OpenGlStateSnapshot? = null
        private var overlaySnapshot: SkijaImage? = null
        private var compositeSurface: Surface? = null
        private var compositeRenderTarget: BackendRenderTarget? = null
        private var compositeFramebufferId = 0
        private var compositeTextureId = 0

        override fun supports(texture: GpuTexture): Boolean {
            return texture is GlTexture
        }

        override fun prepare(framebuffer: RenderTarget, texture: GpuTexture): Surface {
            check(texture is GlTexture) {
                "dtMap Skija OpenGL backend received ${texture.javaClass.name}"
            }

            val state = OpenGlStateSnapshot.capture()
            return try {
                val context = ensureContext()
                context.resetGLAll()
                ensureSurface(context, framebuffer, texture)
                val prepared = surface ?: error("Skija OpenGL surface was not created")
                frameState = state
                frameSurface = prepared
                prepared
            } catch (t: Throwable) {
                state.restore()
                throw t
            }
        }

        override fun finish() {
            try {
                val prepared = frameSurface
                directContext?.flushAndSubmit(prepared, false)
                overlaySnapshot?.close()
                overlaySnapshot = prepared?.makeImageSnapshot()
                directContext?.resetGLAll()
            } finally {
                frameSurface = null
                frameState?.restore()
                frameState = null
            }
        }

        override fun composite(overlay: RenderTarget, output: RenderTarget): Boolean {
            val overlayTexture = overlay.getColorTexture() as? GlTexture ?: return false
            val outputTexture = output.getColorTexture() as? GlTexture ?: return false
            val snapshot = overlaySnapshot ?: return false
            if (surfaceTextureId != overlayTexture.glId()) return false

            val state = OpenGlStateSnapshot.capture()
            val context = ensureContext()
            return try {
                context.resetGLAll()
                val targetSurface = ensureCompositeSurface(context, output, outputTexture)
                targetSurface.canvas.drawImageRect(
                    snapshot,
                    Rect.makeWH(output.width.toFloat(), output.height.toFloat())
                )
                directContext?.flushAndSubmit(targetSurface, false)
                directContext?.resetGLAll()
                true
            } finally {
                state.restore()
            }
        }

        override fun close() {
            releaseSurface()
            releaseCompositeSurface()
            directContext?.close()
            directContext = null
        }

        private fun ensureContext(): DirectContext {
            directContext?.let { return it }
            val context = DirectContext.makeGL()
            directContext = context
            return context
        }

        private fun ensureSurface(context: DirectContext, framebuffer: RenderTarget, texture: GlTexture) {
            val width = texture.getWidth(0)
            val height = texture.getHeight(0)
            if (
                surface != null &&
                renderTarget != null &&
                surfaceTextureId == texture.glId() &&
                surface?.width == width &&
                surface?.height == height
            ) {
                return
            }

            releaseSurface()
            framebufferId = createFramebuffer(texture)
            surfaceTextureId = texture.glId()

            val target = BackendRenderTarget.makeGL(
                width,
                height,
                0,
                if (framebuffer.useDepth) 0 else 0,
                framebufferId,
                FramebufferFormat.GR_GL_RGBA8
            )
            renderTarget = target
            surface = Surface.wrapBackendRenderTarget(
                context,
                target,
                SurfaceOrigin.BOTTOM_LEFT,
                ColorType.RGBA_8888,
                colorSpace
            )
        }

        private fun createFramebuffer(texture: GlTexture): Int {
            val previousDrawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING)
            val previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING)
            val fbo = GL30C.glGenFramebuffers()
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
            GL30C.glFramebufferTexture2D(
                GL30C.GL_FRAMEBUFFER,
                GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D,
                texture.glId(),
                texture.fboMipLevel()
            )
            GL11C.glDrawBuffer(GL30C.GL_COLOR_ATTACHMENT0)
            GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0)
            val status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer)
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFramebuffer)
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                GL30C.glDeleteFramebuffers(fbo)
                error("dtMap Skija OpenGL backend could not wrap Minecraft framebuffer: GL status 0x${status.toString(16)}")
            }
            return fbo
        }

        private fun ensureCompositeSurface(context: DirectContext, framebuffer: RenderTarget, texture: GlTexture): Surface {
            val width = texture.getWidth(0)
            val height = texture.getHeight(0)
            if (
                compositeSurface != null &&
                compositeRenderTarget != null &&
                compositeTextureId == texture.glId() &&
                compositeSurface?.width == width &&
                compositeSurface?.height == height
            ) {
                return compositeSurface ?: error("Skija OpenGL composite surface was not created")
            }

            releaseCompositeSurface()
            compositeFramebufferId = createFramebuffer(texture)
            compositeTextureId = texture.glId()

            val target = BackendRenderTarget.makeGL(
                width,
                height,
                0,
                if (framebuffer.useDepth) 0 else 0,
                compositeFramebufferId,
                FramebufferFormat.GR_GL_RGBA8
            )
            compositeRenderTarget = target
            compositeSurface = Surface.wrapBackendRenderTarget(
                context,
                target,
                SurfaceOrigin.BOTTOM_LEFT,
                ColorType.RGBA_8888,
                colorSpace
            )
            return compositeSurface ?: error("Skija OpenGL composite surface was not created")
        }

        private fun releaseCompositeSurface() {
            compositeSurface?.close()
            compositeSurface = null
            compositeRenderTarget?.close()
            compositeRenderTarget = null
            if (compositeFramebufferId != 0) {
                GL30C.glDeleteFramebuffers(compositeFramebufferId)
                compositeFramebufferId = 0
            }
            compositeTextureId = 0
        }

        private fun releaseSurface() {
            frameSurface = null
            overlaySnapshot?.close()
            overlaySnapshot = null
            surface?.close()
            surface = null
            renderTarget?.close()
            renderTarget = null
            if (framebufferId != 0) {
                GL30C.glDeleteFramebuffers(framebufferId)
                framebufferId = 0
            }
            surfaceTextureId = 0
        }
    }

    private class OpenGlStateSnapshot(
        private val drawFramebuffer: Int,
        private val readFramebuffer: Int,
        private val viewport: IntArray,
        private val scissorBox: IntArray,
        private val currentProgram: Int,
        private val activeTexture: Int,
        private val texture2d: Int,
        private val vertexArray: Int,
        private val arrayBuffer: Int,
        private val elementArrayBuffer: Int,
        private val pixelPackBuffer: Int,
        private val pixelUnpackBuffer: Int,
        private val uniformBuffer: Int,
        private val blend: Boolean,
        private val depthTest: Boolean,
        private val cullFace: Boolean,
        private val scissorTest: Boolean,
        private val stencilTest: Boolean,
        private val multisample: Boolean,
        private val polygonOffsetFill: Boolean,
        private val depthMask: Boolean,
        private val colorMask: BooleanArray,
        private val blendSrcRgb: Int,
        private val blendDstRgb: Int,
        private val blendSrcAlpha: Int,
        private val blendDstAlpha: Int,
        private val blendEquationRgb: Int,
        private val blendEquationAlpha: Int
    ) {
        fun restore() {
            setEnabled(GL11C.GL_BLEND, blend)
            setEnabled(GL11C.GL_DEPTH_TEST, depthTest)
            setEnabled(GL11C.GL_CULL_FACE, cullFace)
            setEnabled(GL11C.GL_SCISSOR_TEST, scissorTest)
            setEnabled(GL11C.GL_STENCIL_TEST, stencilTest)
            setEnabled(GL13C.GL_MULTISAMPLE, multisample)
            setEnabled(GL11C.GL_POLYGON_OFFSET_FILL, polygonOffsetFill)

            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer)
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer)
            GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            GL11C.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
            GL11C.glDepthMask(depthMask)
            GL11C.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3])
            GL14C.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha)
            GL20C.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha)
            GL20C.glUseProgram(currentProgram)
            GL13C.glActiveTexture(activeTexture)
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture2d)
            GL30C.glBindVertexArray(vertexArray)
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, arrayBuffer)
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, elementArrayBuffer)
            GL15C.glBindBuffer(GL21_PIXEL_PACK_BUFFER, pixelPackBuffer)
            GL15C.glBindBuffer(GL21_PIXEL_UNPACK_BUFFER, pixelUnpackBuffer)
            GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, uniformBuffer)
        }

        companion object {
            private const val GL21_PIXEL_PACK_BUFFER = 35051
            private const val GL21_PIXEL_UNPACK_BUFFER = 35052
            private const val GL_PIXEL_PACK_BUFFER_BINDING = 35053
            private const val GL_PIXEL_UNPACK_BUFFER_BINDING = 35055

            fun capture(): OpenGlStateSnapshot {
                MemoryStack.stackPush().use { stack ->
                    val viewport = IntArray(4)
                    val viewportBuffer = stack.mallocInt(4)
                    GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewportBuffer)
                    viewportBuffer.get(viewport)

                    val scissor = IntArray(4)
                    val scissorBuffer = stack.mallocInt(4)
                    GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, scissorBuffer)
                    scissorBuffer.get(scissor)

                    val colorMask = BooleanArray(4)
                    val colorMaskBuffer = stack.malloc(4)
                    GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, colorMaskBuffer)
                    for (i in colorMask.indices) {
                        colorMask[i] = colorMaskBuffer.get(i).toInt() != 0
                    }

                    return OpenGlStateSnapshot(
                        GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),
                        GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),
                        viewport,
                        scissor,
                        GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM),
                        GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE),
                        GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D),
                        GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING),
                        GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING),
                        GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING),
                        GL11C.glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING),
                        GL11C.glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING),
                        GL11C.glGetInteger(GL31C.GL_UNIFORM_BUFFER_BINDING),
                        GL11C.glIsEnabled(GL11C.GL_BLEND),
                        GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
                        GL11C.glIsEnabled(GL11C.GL_CULL_FACE),
                        GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST),
                        GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST),
                        GL11C.glIsEnabled(GL13C.GL_MULTISAMPLE),
                        GL11C.glIsEnabled(GL11C.GL_POLYGON_OFFSET_FILL),
                        GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK),
                        colorMask,
                        GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB),
                        GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB),
                        GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA),
                        GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA),
                        GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB),
                        GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA)
                    )
                }
            }

            private fun setEnabled(cap: Int, enabled: Boolean) {
                if (enabled) {
                    GL11C.glEnable(cap)
                } else {
                    GL11C.glDisable(cap)
                }
            }
        }
    }

    private fun canvas(): Canvas {
        check(drawing) { "[SkijaRenderer] Drawing outside beginFrame/endFrame" }
        return canvas ?: error("Skija canvas is not available")
    }

    private inline fun withPaint(
        color: Int,
        mode: PaintMode = PaintMode.FILL,
        strokeWidth: Float = 1f,
        draw: (Paint) -> Unit
    ) {
        val paint = if (mode == PaintMode.FILL) reusableFillPaint() else reusableStrokePaint()
        paint.reset()
        try {
            paint.setAntiAlias(true)
                .setMode(mode)
                .setColor(applyGlobalAlpha(color))
            if (mode != PaintMode.FILL) {
                paint.setStrokeWidth(strokeWidth)
            }
            draw(paint)
        } finally {
            paint.reset()
        }
    }

    private inline fun withGradientPaint(
        color1: Int,
        color2: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        direction: Gradient,
        mode: PaintMode = PaintMode.FILL,
        strokeWidth: Float = 1f,
        draw: (Paint) -> Unit
    ) {
        val (x2, y2) = when (direction) {
            Gradient.LeftToRight -> x + w to y
            Gradient.TopToBottom -> x to y + h
        }
        val shader = Shader.makeLinearGradient(x, y, x2, y2, intArrayOf(applyGlobalAlpha(color1), applyGlobalAlpha(color2)))
        val paint = Paint()
        try {
            paint.setAntiAlias(true)
                .setMode(mode)
                .setShader(shader)
            if (mode != PaintMode.FILL) {
                paint.setStrokeWidth(strokeWidth)
            }
            draw(paint)
        } finally {
            paint.close()
            shader.close()
        }
    }

    private fun applyGlobalAlpha(color: Int): Int {
        val alpha = (((color ushr 24) and 0xFF) * currentAlpha).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private inline fun <T> withSkijaFont(font: Font, size: Float, draw: (SkijaFont) -> T): T {
        return draw(getSkijaFont(font, size))
    }

    @Throws(IOException::class)
    private fun getSkijaFont(font: Font, size: Float): SkijaFont {
        val key = SkijaFontKey(font, size)
        skijaFonts[key]?.let { return it }

        val typeface = getTypeface(font)
        val skFont = if (typeface != null) SkijaFont(typeface, size) else SkijaFont().setSize(size)
        skFont.setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS)
            .setHinting(FontHinting.FULL)
            .setSubpixel(true)
        skijaFonts[key] = skFont
        return skFont
    }

    @Throws(IOException::class)
    private fun getTypeface(font: Font): Typeface? {
        fonts[font]?.let { return it.typeface }

        val data = Data.makeFromBytes(font.bytes())
        val typeface = fontManager.makeFromData(data)
        if (typeface == null) {
            data.close()
            return null
        }
        fonts[font] = LoadedFont(data, typeface)
        return typeface
    }

    @Throws(IOException::class)
    private fun wrapText(text: String, width: Float, size: Float, font: Font): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        val paragraphs = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        for (paragraph in paragraphs) {
            if (paragraph.isBlank()) {
                lines += ""
                continue
            }

            var current = ""
            val words = paragraph.split(Regex("\\s+")).filter(String::isNotBlank)
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (textWidth(candidate, size, font) <= width || current.isEmpty()) {
                    if (current.isEmpty() && textWidth(candidate, size, font) > width) {
                        lines += breakLongWord(candidate, width, size, font)
                        current = ""
                    } else {
                        current = candidate
                    }
                } else {
                    lines += current
                    current = word
                }
            }

            if (current.isNotEmpty()) {
                lines += current
            }
        }
        return lines
    }

    @Throws(IOException::class)
    private fun breakLongWord(word: String, width: Float, size: Float, font: Font): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            while (end > start + 1 && textWidth(word.substring(start, end), size, font) > width) {
                end--
            }
            lines += word.substring(start, end)
            start = end
        }
        return lines
    }

    private fun drawImage(image: Image, x: Float, y: Float, w: Float, h: Float, radius: Float, tint: Int?) {
        val loaded = images[image] ?: throw IllegalStateException("Image (${image.identifier}) doesn't exist")
        val c = canvas()
        c.save()
        try {
            if (radius > 0f) {
                c.clipRRect(RRect.makeXYWH(x, y, w, h + 0.5f, radius), ClipMode.INTERSECT, true)
            } else {
                c.clipRect(Rect.makeXYWH(x, y, w, h + 0.5f), ClipMode.INTERSECT, true)
            }

            if (tint != null) {
                val filter = colorFilter(applyGlobalAlpha(tint))
                val paint = reusableImagePaint()
                paint.reset()
                try {
                    paint.setColorFilter(filter)
                    c.saveLayer(Rect.makeXYWH(x, y, w, h), paint)
                    loaded.draw(c, x, y, w, h)
                    c.restore()
                } finally {
                    paint.reset()
                }
            } else {
                loaded.draw(c, x, y, w, h)
            }
        } finally {
            c.restore()
        }
    }

    private fun reusableFillPaint(): Paint {
        return fillPaint ?: Paint().also { fillPaint = it }
    }

    private fun reusableStrokePaint(): Paint {
        return strokePaint ?: Paint().also { strokePaint = it }
    }

    private fun reusableShadowPaint(): Paint {
        return shadowPaint ?: Paint().also { shadowPaint = it }
    }

    private fun reusableImagePaint(): Paint {
        return imagePaint ?: Paint().also { imagePaint = it }
    }

    private fun dropShadowFilter(blur: Float, color: Int): ImageFilter {
        val key = DropShadowFilterKey(blur, color)
        return dropShadowFilters[key] ?: ImageFilter.makeDropShadowOnly(0f, 0f, blur, blur, color)
            .also { dropShadowFilters[key] = it }
    }

    private fun colorFilter(color: Int): ColorFilter {
        return colorFilters[color] ?: ColorFilter.makeBlend(color, BlendMode.SRC_IN)
            .also { colorFilters[color] = it }
    }

    private fun loadImage(image: Image): LoadedImage {
        return if (image.isSVG) {
            val data = Data.makeFromBytes(image.bytes())
            try {
                LoadedImage(0, null, SVGDOM(data), data)
            } catch (e: Throwable) {
                data.close()
                throw e
            }
        } else {
            val raster = SkijaImage.makeFromEncoded(image.bytes())
            LoadedImage(0, raster, null, null)
        }
    }

    enum class BoundsKind {
        CONTENT,
        SHADOW
    }

    private enum class BackendKind {
        OPENGL,
        VULKAN,
        UNKNOWN
    }

    interface BoundsTracker {
        fun record(x: Float, y: Float, width: Float, height: Float, kind: BoundsKind)
    }

    private class Scissor(val previous: Scissor?)

    private data class SkijaFontKey(val font: Font, val size: Float)

    private data class TextWidthKey(val text: String, val size: Float, val font: Font)

    private data class DropShadowFilterKey(val blur: Float, val color: Int)

    private class LoadedFont(val data: Data, val typeface: Typeface) {
        fun close() {
            typeface.close()
            data.close()
        }
    }

    private class LoadedImage(
        var count: Int,
        val raster: SkijaImage?,
        val svg: SVGDOM?,
        val data: Data?
    ) {
        fun draw(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
            if (raster != null) {
                canvas.drawImageRect(raster, Rect.makeXYWH(x, y, w, h))
                return
            }

            if (svg != null) {
                canvas.save()
                try {
                    canvas.translate(x, y)
                    svg.setContainerSize(w, h)
                    svg.render(canvas)
                } finally {
                    canvas.restore()
                }
            }
        }

        fun close() {
            raster?.close()
            svg?.close()
            data?.close()
        }
    }
}
