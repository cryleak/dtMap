package com.ricedotwho.dtmap.utils.render.skija

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Objects

class Font {
    var name: String
    private val resourcePath: String?
    private var cachedBytes: ByteArray?

    constructor(name: String, resourcePath: String) {
        this.name = name
        this.resourcePath = resourcePath
        this.cachedBytes = null
    }

    constructor(name: String, inputStream: InputStream) {
        this.name = name
        this.resourcePath = null
        inputStream.use { stream ->
            this.cachedBytes = stream.readAllBytes()
        }
    }

    @Throws(IOException::class)
    fun bytes(): ByteArray {
        var bytes = cachedBytes
        if (bytes == null) {
            if (resourcePath != null) {
                this.javaClass.getResourceAsStream(resourcePath).use { stream ->
                    if (stream == null) {
                        throw FileNotFoundException(resourcePath)
                    }
                    bytes = stream.readAllBytes()
                    cachedBytes = bytes
                }
            }
        }
        return bytes ?: throw FileNotFoundException(resourcePath ?: name)
    }

    @Throws(IOException::class)
    fun buffer(): ByteBuffer {
        val bytes = bytes()
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.flip()
        return buffer
    }

    override fun hashCode(): Int {
        return Objects.hash(name)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Font) return false
        return this.name == other.name
    }
}
