package com.ricedotwho.dtmap.gui

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import imgui.ImDrawData
import imgui.ImGui
import imgui.type.ImInt
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines as MinecraftRenderPipelines
import net.minecraft.resources.Identifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Optional
import java.util.OptionalDouble
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class MinecraftImGuiRenderer {
    private var fontTexture: GpuTexture? = null
    private var fontTextureView: GpuTextureView? = null
    private var fontSampler: GpuSampler? = null
    private var vertexBuffer: GpuBuffer? = null
    private var indexBuffer: GpuBuffer? = null
    private var vertexBufferSize = 0L
    private var indexBufferSize = 0L

    fun initialize() {
        uploadFontAtlas()
        ImGui.getIO().backendRendererName = "dtmap_minecraft_gpu"
    }

    fun render(drawData: ImDrawData?) {
        if (drawData == null || !drawData.valid || drawData.totalVtxCount <= 0 || drawData.totalIdxCount <= 0) {
            return
        }

        if (fontTexture == null || fontTextureView == null || fontSampler == null) {
            uploadFontAtlas()
        }

        val framebufferWidth = (drawData.displaySizeX * drawData.framebufferScaleX).toInt()
        val framebufferHeight = (drawData.displaySizeY * drawData.framebufferScaleY).toInt()
        if (framebufferWidth <= 0 || framebufferHeight <= 0) {
            return
        }

        val vertexSize = DefaultVertexFormat.POSITION_TEX_COLOR.vertexSize
        val requiredVertexBytes = drawData.totalVtxCount * vertexSize
        val requiredIndexBytes = drawData.totalIdxCount * ImDrawData.sizeOfImDrawIdx()
        ensureVertexBuffer(requiredVertexBytes.toLong())
        ensureIndexBuffer(requiredIndexBytes.toLong())

        val vertexUpload = ByteBuffer.allocateDirect(requiredVertexBytes).order(ByteOrder.LITTLE_ENDIAN)
        val indexUpload = ByteBuffer.allocateDirect(requiredIndexBytes).order(ByteOrder.LITTLE_ENDIAN)
        copyDrawBuffers(drawData, vertexUpload, indexUpload)

        val target = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        val colorTextureView = target.getColorTextureView() ?: return
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        encoder.writeToBuffer(vertexBuffer!!.slice(0, requiredVertexBytes.toLong()), vertexUpload.flip() as ByteBuffer)
        encoder.writeToBuffer(indexBuffer!!.slice(0, requiredIndexBytes.toLong()), indexUpload.flip() as ByteBuffer)

        encoder.createRenderPass(
            { "dtmap imgui" },
            colorTextureView,
            Optional.empty()
        ).use { pass ->
            pass.setPipeline(IMGUI_PIPELINE)
            pass.bindTexture("Sampler0", fontTextureView!!, fontSampler!!)
            pass.setVertexBuffer(0, vertexBuffer!!.slice(0, requiredVertexBytes.toLong()))
            pass.setIndexBuffer(indexBuffer!!, indexType())

            var globalVertexOffset = 0
            var globalIndexOffset = 0
            for (listIndex in 0 until drawData.cmdListsCount) {
                val commandCount = drawData.getCmdListCmdBufferSize(listIndex)
                for (commandIndex in 0 until commandCount) {
                    val elementCount = drawData.getCmdListCmdBufferElemCount(listIndex, commandIndex)
                    if (elementCount <= 0) {
                        continue
                    }

                    val clip = drawData.getCmdListCmdBufferClipRect(listIndex, commandIndex)
                    val clipMinX = ((clip.x - drawData.displayPosX) * drawData.framebufferScaleX)
                    val clipMinY = ((clip.y - drawData.displayPosY) * drawData.framebufferScaleY)
                    val clipMaxX = ((clip.z - drawData.displayPosX) * drawData.framebufferScaleX)
                    val clipMaxY = ((clip.w - drawData.displayPosY) * drawData.framebufferScaleY)

                    val x = max(0, floor(clipMinX).toInt())
                    val y = max(0, floor(clipMinY).toInt())
                    val width = min(framebufferWidth, ceil(clipMaxX).toInt()) - x
                    val height = min(framebufferHeight, ceil(clipMaxY).toInt()) - y
                    if (width <= 0 || height <= 0) {
                        continue
                    }

                    pass.enableScissor(x, y, width, height)
                    pass.drawIndexed(
                        elementCount,
                        1,
                        globalIndexOffset + drawData.getCmdListCmdBufferIdxOffset(listIndex, commandIndex),
                        globalVertexOffset + drawData.getCmdListCmdBufferVtxOffset(listIndex, commandIndex),
                        0
                    )
                }

                globalVertexOffset += drawData.getCmdListVtxBufferSize(listIndex)
                globalIndexOffset += drawData.getCmdListIdxBufferSize(listIndex)
            }
            pass.disableScissor()
        }
        encoder.submit()
    }

    fun shutdown() {
        vertexBuffer?.close()
        indexBuffer?.close()
        fontTextureView?.close()
        fontTexture?.close()
        fontSampler?.close()
        vertexBuffer = null
        indexBuffer = null
        fontTextureView = null
        fontTexture = null
        fontSampler = null
        vertexBufferSize = 0
        indexBufferSize = 0
    }

    private fun uploadFontAtlas() {
        val width = ImInt()
        val height = ImInt()
        val pixels = ImGui.getIO().fonts.getTexDataAsRGBA32(width, height)
        pixels.position(0)

        val device = RenderSystem.getDevice()
        fontTextureView?.close()
        fontTexture?.close()
        fontSampler?.close()

        fontTexture = device.createTexture(
            "dtmap imgui font atlas",
            GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.RGBA8_UNORM,
            width.get(),
            height.get(),
            1,
            1
        )
        fontTextureView = device.createTextureView(fontTexture!!)
        fontSampler = device.createSampler(
            AddressMode.CLAMP_TO_EDGE,
            AddressMode.CLAMP_TO_EDGE,
            FilterMode.LINEAR,
            FilterMode.LINEAR,
            1,
            OptionalDouble.empty()
        )

        device.createCommandEncoder().also { encoder ->
            encoder.writeToTexture(fontTexture!!, pixels, 0, 0, 0, 0, width.get(), height.get())
            encoder.submit()
        }
        ImGui.getIO().fonts.setTexID(FONT_TEXTURE_ID)
        ImGui.getIO().fonts.clearTexData()
    }

    private fun ensureVertexBuffer(requiredSize: Long) {
        if (vertexBufferSize >= requiredSize && vertexBuffer?.isClosed == false) {
            return
        }
        vertexBuffer?.close()
        vertexBufferSize = growBufferSize(requiredSize)
        vertexBuffer = RenderSystem.getDevice().createBuffer(
            { "dtmap imgui vertices" },
            GpuBuffer.USAGE_VERTEX or GpuBuffer.USAGE_COPY_DST,
            vertexBufferSize
        )
    }

    private fun ensureIndexBuffer(requiredSize: Long) {
        if (indexBufferSize >= requiredSize && indexBuffer?.isClosed == false) {
            return
        }
        indexBuffer?.close()
        indexBufferSize = growBufferSize(requiredSize)
        indexBuffer = RenderSystem.getDevice().createBuffer(
            { "dtmap imgui indices" },
            GpuBuffer.USAGE_INDEX or GpuBuffer.USAGE_COPY_DST,
            indexBufferSize
        )
    }

    private fun copyDrawBuffers(drawData: ImDrawData, vertexUpload: ByteBuffer, indexUpload: ByteBuffer) {
        val displayX = drawData.displayPosX
        val displayY = drawData.displayPosY
        val scaleX = 2f / drawData.displaySizeX
        val scaleY = 2f / drawData.displaySizeY
        val sourceVertexSize = ImDrawData.sizeOfImDrawVert()

        for (listIndex in 0 until drawData.cmdListsCount) {
            val vertices = drawData.getCmdListVtxBufferData(listIndex).order(ByteOrder.LITTLE_ENDIAN)
            val vertexCount = drawData.getCmdListVtxBufferSize(listIndex)
            for (vertexIndex in 0 until vertexCount) {
                val offset = vertexIndex * sourceVertexSize
                val x = vertices.getFloat(offset)
                val y = vertices.getFloat(offset + 4)
                val u = vertices.getFloat(offset + 8)
                val v = vertices.getFloat(offset + 12)
                val color = vertices.getInt(offset + 16)

                vertexUpload.putFloat((x - displayX) * scaleX - 1f)
                vertexUpload.putFloat(1f - (y - displayY) * scaleY)
                vertexUpload.putFloat(0f)
                vertexUpload.putFloat(u)
                vertexUpload.putFloat(v)
                vertexUpload.putInt(color)
            }

            val indices = drawData.getCmdListIdxBufferData(listIndex)
            indices.position(0)
            val indexBytes = drawData.getCmdListIdxBufferSize(listIndex) * ImDrawData.sizeOfImDrawIdx()
            val limitedIndices = indices.slice()
            limitedIndices.limit(indexBytes)
            indexUpload.put(limitedIndices)
        }
    }

    private fun growBufferSize(requiredSize: Long): Long = max(MIN_BUFFER_SIZE, requiredSize * 3 / 2)

    private fun indexType(): IndexType = when (ImDrawData.sizeOfImDrawIdx()) {
        java.lang.Short.BYTES -> IndexType.SHORT
        Integer.BYTES -> IndexType.INT
        else -> error("Unsupported ImDrawIdx size: ${ImDrawData.sizeOfImDrawIdx()}")
    }

    private companion object {
        private val IMGUI_PIPELINE: RenderPipeline = MinecraftRenderPipelines.register(
            RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("dtmap", "pipeline/imgui"))
                .withVertexShader(Identifier.fromNamespaceAndPath("dtmap", "core/imgui"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("dtmap", "core/imgui"))
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
                .withDepthStencilState(Optional.empty())
                .withCull(false)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build()
        )

        private const val FONT_TEXTURE_ID = 1L
        private const val MIN_BUFFER_SIZE = 4096L
    }
}
