package com.ricedotwho.dtmap.utils

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import net.minecraft.core.BlockPos
import java.lang.reflect.Type

class BlockPosAdapter : JsonSerializer<BlockPos>, JsonDeserializer<BlockPos> {

    override fun serialize(src: BlockPos, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        return JsonPrimitive("${src.x}, ${src.y}, ${src.z}")
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): BlockPos {
        return if (json.isJsonObject) {
            BlockPos(json.asJsonObject["x"].asInt, json.asJsonObject["y"].asInt, json.asJsonObject["z"].asInt)
        } else if (json.isJsonPrimitive && json.asJsonPrimitive.isString) {
            val arr = json.asString.split(", ")
            BlockPos(
                arr.getOrNull(0)?.toIntOrNull() ?: 0,
                arr.getOrNull(1)?.toIntOrNull() ?: 0,
                arr.getOrNull(2)?.toIntOrNull() ?: 0
            )
        } else {
            BlockPos(0, 0, 0)
        }
    }
}