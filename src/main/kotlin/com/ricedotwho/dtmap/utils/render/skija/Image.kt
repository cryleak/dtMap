package com.ricedotwho.dtmap.utils.render.skija

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Locale

class Image @JvmOverloads constructor(
    val identifier: String,
    isSVG: Boolean? = null,
    val stream: InputStream? = null
) {
    val isSVG: Boolean = isSVG ?: identifier.lowercase(Locale.getDefault()).endsWith(".svg")
    private var cachedBytes: ByteArray? = null

    @Throws(IOException::class)
    fun bytes(): ByteArray {
        var bytes = cachedBytes
        if (bytes == null) {
            val input = stream ?: getStream(identifier)
            input.use { bytes = it.readAllBytes() }
            cachedBytes = bytes
        }
        return bytes ?: throw FileNotFoundException(identifier)
    }

    @Throws(IOException::class)
    fun buffer(): ByteBuffer {
        val bytes = bytes()
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.flip()
        return buffer
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Image) return false
        return this.identifier == other.identifier
    }

    override fun hashCode(): Int {
        return identifier.hashCode()
    }

    companion object {
        @Throws(IOException::class, URISyntaxException::class)
        private fun getStream(path: String): InputStream {
            val trimmedPath = path.trim { it <= ' ' }
            val file = File(trimmedPath)
            if (file.exists() && file.isFile) {
                return Files.newInputStream(file.toPath())
            }

            val resource = Image::class.java.getResourceAsStream(trimmedPath)
            if (resource != null) {
                return resource
            }

            throw FileNotFoundException(trimmedPath)
        }
    }
}
