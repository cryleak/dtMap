package com.ricedotwho.dtmap.utils

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.ricedotwho.dtmap.DtMap.mc
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color

/*
 * Original code Copyright (c) 2026, odtheking (https://github.com/odtheking/Odin/blob/main/src/main/kotlin/com/odtheking/odin/utils/render/RenderUtils.kt)
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

object PrimitiveRenderer {

    private val edges = intArrayOf(
        0, 1,  1, 5,  5, 4,  4, 0,
        3, 2,  2, 6,  6, 7,  7, 3,
        0, 3,  1, 2,  5, 6,  4, 7
    )

    fun renderLineBox(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        aabb: AABB,
        color: Int,
        thickness: Float
    ) {
        val x0 = aabb.minX.toFloat()
        val y0 = aabb.minY.toFloat()
        val z0 = aabb.minZ.toFloat()
        val x1 = aabb.maxX.toFloat()
        val y1 = aabb.maxY.toFloat()
        val z1 = aabb.maxZ.toFloat()

        val corners = floatArrayOf(
            x0, y0, z0,
            x1, y0, z0,
            x1, y1, z0,
            x0, y1, z0,
            x0, y0, z1,
            x1, y0, z1,
            x1, y1, z1,
            x0, y1, z1
        )

        for (i in edges.indices step 2) {
            val i0 = edges[i] * 3
            val i1 = edges[i + 1] * 3

            val x0 = corners[i0]
            val y0 = corners[i0 + 1]
            val z0 = corners[i0 + 2]
            val x1 = corners[i1]
            val y1 = corners[i1 + 1]
            val z1 = corners[i1 + 2]

            val dx = x1 - x0
            val dy = y1 - y0
            val dz = z1 - z0

            buffer.addVertex(pose, x0, y0, z0).setColor(color).setNormal(pose, dx, dy, dz).setLineWidth(thickness)
            buffer.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, dx, dy, dz).setLineWidth(thickness)
        }
    }

    fun addChainedFilledBoxVertices(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        aabb: AABB,
        color: Int
    ) {
        val matrix = pose.pose()

        fun vertex(x: Float, y: Float, z: Float) {
            buffer.addVertex(matrix, x, y, z).setColor(color)
        }

        val minX = aabb.minX.toFloat()
        val minY = aabb.minY.toFloat()
        val minZ = aabb.minZ.toFloat()
        val maxX = aabb.maxX.toFloat()
        val maxY = aabb.maxY.toFloat()
        val maxZ = aabb.maxZ.toFloat()

        vertex(minX, minY, minZ)
        vertex(minX, minY, maxZ)
        vertex(minX, maxY, maxZ)
        vertex(minX, maxY, minZ)

        vertex(maxX, minY, maxZ)
        vertex(maxX, minY, minZ)
        vertex(maxX, maxY, minZ)
        vertex(maxX, maxY, maxZ)

        vertex(minX, minY, minZ)
        vertex(minX, maxY, minZ)
        vertex(maxX, maxY, minZ)
        vertex(maxX, minY, minZ)

        vertex(maxX, minY, maxZ)
        vertex(maxX, maxY, maxZ)
        vertex(minX, maxY, maxZ)
        vertex(minX, minY, maxZ)

        vertex(minX, minY, minZ)
        vertex(maxX, minY, minZ)
        vertex(maxX, minY, maxZ)
        vertex(minX, minY, maxZ)

        vertex(minX, maxY, maxZ)
        vertex(maxX, maxY, maxZ)
        vertex(maxX, maxY, minZ)
        vertex(minX, maxY, minZ)
    }

    fun renderVector(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        start: Vec3,
        direction: Vec3,
        startColor: Int,
        endColor: Int,
        thickness: Float
    ) {
        val endX = start.x().toFloat() + direction.x.toFloat()
        val endY = start.y().toFloat() + direction.y.toFloat()
        val endZ = start.z().toFloat() + direction.z.toFloat()

        val nx = direction.x.toFloat()
        val ny = direction.y.toFloat()
        val nz = direction.z.toFloat()

        buffer.addVertex(pose, start.x().toFloat(), start.y().toFloat(), start.z().toFloat())
            .setColor(startColor)
            .setNormal(pose, nx, ny, nz)
            .setLineWidth(thickness)

        buffer.addVertex(pose, endX, endY, endZ)
            .setColor(endColor)
            .setNormal(pose, nx, ny, nz)
            .setLineWidth(thickness)
    }

    fun drawLine(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        start: Vec3,
        end: Vec3,
        startColor: Int,
        endColor: Int,
        thickness: Float
    ) {
        renderVector(pose, buffer, start, end.subtract(start), startColor, endColor, thickness)
    }
}

fun WorldRenderContext.drawLineFromCursor(
    endPos: Vec3,
    color: Color,
    lineWidth: Float
) = matrices().poseScopeWithCamera {
    val startPos = mc.player?.let { player ->
        player.renderPos.add(player.forward.add(0.0, player.eyeHeight.toDouble(), 0.0))
    } ?: return

    val buffer = this.consumers().getBuffer(RenderLayers.LINES_THROUGH_WALLS)
    PrimitiveRenderer.drawLine(it.last(), buffer, startPos, endPos, color.rgb, color.rgb, lineWidth)
}

fun WorldRenderContext.drawBlockOverlay(pos: BlockPos, color: Color, depth: Boolean) {
    val level = mc.level ?: return

    val block = level.getBlockState(pos)
    val shape = block.getShape(level, pos)
    if (shape.isEmpty) return

    val buffer = if (depth) this.consumers().getBuffer(RenderTypes.debugFilledBox())
                 else this.consumers().getBuffer(RenderLayers.QUADS_THROUGH_WALLS)

    val camera = mc.gameRenderer.mainCamera.position()

    val matrices = this.matrices()
    matrices.pushPose()
    matrices.translate(
        pos.x - camera.x,
        pos.y - camera.y,
        pos.z - camera.z
    )

    shape.forAllBoxes { minX, minY, minZ, maxX, maxY, maxZ ->
        PrimitiveRenderer.addChainedFilledBoxVertices(
            matrices.last(),
            buffer,
            AABB(
                minX * 0.999, minY * 0.999, minZ * 0.999,
                maxX * 1.001, maxY * 1.001, maxZ * 1.001,
            ),
            color.rgb
        )
    }

    matrices.popPose()
}

fun WorldRenderContext.drawLineBox(
    aabb: AABB,
    color: Color,
    thickness: Float,
    depth: Boolean
) = matrices().poseScopeWithCamera {
    val buffer = if (depth) this.consumers().getBuffer(RenderTypes.LINES) else this.consumers().getBuffer(RenderLayers.LINES_THROUGH_WALLS)
    PrimitiveRenderer.renderLineBox(this.matrices().last(), buffer, aabb, color.rgb, thickness)
}

fun WorldRenderContext.drawFilled(
    aabb: AABB,
    color: Color,
    depth: Boolean
) = matrices().poseScopeWithCamera {
    val buffer = if (depth) this.consumers().getBuffer(RenderTypes.debugFilledBox()) else this.consumers().getBuffer(RenderLayers.QUADS_THROUGH_WALLS)
    PrimitiveRenderer.addChainedFilledBoxVertices(this.matrices().last(), buffer, aabb, color.rgb)
}

inline fun PoseStack.poseScopeWithCamera(block: (PoseStack) -> Unit) = poseScope {
    val camera = mc.gameRenderer.mainCamera.position()
    it.translate(-camera.x, -camera.y, -camera.z)
    block(this)
}

inline fun PoseStack.poseScope(block: (PoseStack) -> Unit) {
    this.pushPose()
    block(this)
    this.popPose()
}