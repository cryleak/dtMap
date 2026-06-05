package com.ricedotwho.dtmap.features.map

data class Vec2i(val x: Int, val z: Int) {
    fun add(other: Vec2i): Vec2i = Vec2i(x + other.x, z + other.z)
    fun add(x0: Int, z0: Int): Vec2i = Vec2i(x + x0, z + z0)

    fun multiply(number: Int): Vec2i = Vec2i(x * number, z * number)
    fun multiply(number: Double): Vec2i = Vec2i((x * number).toInt(), (z * number).toInt())

    fun divide(number: Int): Vec2i = Vec2i(x / number, z / number)
    fun divide(number: Double): Vec2i = Vec2i((x / number).toInt(), (z / number).toInt())

    fun equals(other: Vec2i): Boolean = x == other.x && z == other.z

    fun mapIndex(): Int = z * 128 + x
    fun roomListIndex(): Int = x * 6 + z
    fun index(): Int =
        ((x + 201) / 32) * 6 + (z + 201) / 32

    fun roomTilePos(): Vec2i = add(Vec2i(201, 201)).divide(32)

    override fun hashCode(): Int {
        // the values we deal with in here are quite low so this should be good enough.
        return x shr 16 + z
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Vec2i
        return x == other.x && z == other.z
    }

    override fun toString(): String {
        return "Vec2i($x, $z)"
    }
}
