package com.ricedotwho.dtmap.utils

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.ricedotwho.dtmap.features.map.Room
import java.lang.reflect.Type

class RoomShapeAdapter : JsonSerializer<Room.Shape>, JsonDeserializer<Room.Shape> {
    override fun serialize(src: Room.Shape, typeOfSrc: Type, context: JsonSerializationContext): JsonElement =
        JsonPrimitive(src.str)

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Room.Shape {
        return if (json.isJsonPrimitive && json.asJsonPrimitive.isString) {
            Room.Shape.fromStr(json.asString) ?: Room.Shape.UNKNOWN
        } else {
            Room.Shape.UNKNOWN
        }
    }
}
