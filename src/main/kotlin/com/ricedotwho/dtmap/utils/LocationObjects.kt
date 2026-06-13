package com.ricedotwho.dtmap.utils

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.ricedotwho.dtmap.events.LocationEvents
import com.ricedotwho.dtmap.events.MapEvents
import com.ricedotwho.dtmap.features.map.DungeonMap
import com.ricedotwho.dtmap.features.map.Vec2i
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

object LocationObjects {
    private val configPath: Path = FabricLoader.getInstance().configDir.resolve("dtmap/location_objects")
    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .registerTypeAdapter(BlockPos::class.java, BlockPosAdapter()).create()

    abstract class LocationObject {
        val pos: BlockPos
        @Transient var mapped: BlockPos? = null

        constructor(pos: BlockPos) {
            this.pos = pos
        }
    }

    class Storage<T : LocationObject>(val identifier: String, val duplicates: Boolean, val type: KClass<T>) : Iterable<T> {
        internal var values = mutableMapOf<String, MutableList<T>>()
        internal var filteredValues = mutableListOf<T>()
        internal var shouldSave = false

        internal fun filterForLocations(locations: Map<String, (BlockPos) -> BlockPos>) {
            filteredValues.addAll(values.mapNotNull {
                val mapper = locations[it.key] ?: return@mapNotNull null
                it.value.forEach { it.mapped = mapper(it.pos) }
                return@mapNotNull it.value
            }.flatten().toMutableList())
        }

        internal fun setValuesFromMap(map: MutableMap<String, List<LocationObject>>) {
            @Suppress("UNCHECKED_CAST")
            values = map.mapValues { it ->
                it.value.map { it as T }.toMutableList()
            }.toMutableMap()
        }

        fun get(pos: BlockPos): T? =
            filteredValues.firstOrNull { it.mapped != null && pos.x == it.mapped!!.x && pos.y == it.mapped!!.y && pos.z == it.mapped!!.z }

        fun get(pos: BlockPos, location: String): T? =
            values[location]?.firstOrNull { pos.x == it.pos.x && pos.y == it.pos.y && pos.z == it.pos.z }

        fun exists(pos: BlockPos): Boolean =
            get(pos) != null

        fun exists(pos: BlockPos, location: String): Boolean =
            get(pos, location) != null

        fun add(vararg args: Any?): T? {
            val (location, pos) = getLocationName(args.first() as BlockPos) ?: return null
            if (!locations.contains(location)) {
                Chat.send("SEND THIS TO mi0!!! Location not in location list but trying to add waypoint: ($location, ${locations.joinToString(",")})")
                return null
            }
            if (!duplicates && exists(pos, location)) return null

            val obj = type.primaryConstructor!!.call(pos, *args.sliceArray(1 until args.size))
            obj.mapped = args.first() as BlockPos

            if (values[location] == null) values[location] = mutableListOf()

            values[location]!!.add(obj)
            if (locations.contains(location)) filteredValues.add(obj)

            shouldSave = true

            return obj
        }

        fun remove(obj: T) {
            values.forEach { it.value.remove(obj) }
            filteredValues.remove(obj)
            shouldSave = true
        }

        override fun iterator(): Iterator<T> {
            return filteredValues.iterator()
        }
    }

    val registry = mutableListOf<Storage<*>>()

    inline fun <reified T : LocationObject> registerStorage(identifier: String, noDuplicates: Boolean = false): Storage<T> {
        val storage = Storage(identifier, !noDuplicates, T::class)
        registry.add(storage)
        return storage
    }

    val locations = mutableSetOf<String>()
    private var newLocations: MutableMap<String, (BlockPos) -> BlockPos> = mutableMapOf()
    fun updateLocations() {
        registry.forEach { storage -> storage.filterForLocations(newLocations) }
        locations.addAll(newLocations.keys)
        newLocations.clear()
    }

    fun register() {
        LocationEvents.ON_CHANGE_ISLAND.register { island ->
            if (island == Location.Island.Dungeon) return@register

            locations.clear()
            newLocations[island.displayName] = { it }
            updateLocations()
        }

        LocationEvents.ON_ENTER_DUNGEON.register { dungeon ->
            val locationName = "${Location.Island.Dungeon.displayName}-$dungeon"
            newLocations[locationName] = { it }
            updateLocations()
        }

        MapEvents.ON_LOADED_ROOMS.register {
            newLocations.putAll(
                it.associate { room ->
                    Pair("${Location.Island.Dungeon.displayName}-${room.data!!.name}") {
                        room.offset(it)!!
                    }
                }
            )
        }

        var updateLocationCounter = 0
        var saveCounter = 0
        ClientTickEvents.END_CLIENT_TICK.register {
            if (updateLocationCounter++ % 20 == 0 && !newLocations.isEmpty()) {
                updateLocations()
            }

            if (saveCounter++ % 400 == 0 && registry.any { it.shouldSave }) {
                save()
            }
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register {
            save()
        }

        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register { _, _ ->
            registry.forEach { storage -> storage.filteredValues.clear() }
            locations.clear()
            newLocations.clear()
            updateLocationCounter = 0
        }
    }

    fun load() {
        if (!configPath.exists()) {
            configPath.createDirectories()
        }

        registry.forEach {
            val currPath = configPath.resolve("${it.identifier}.json")
            if (!currPath.exists()) return@forEach

            val reader = Files.newBufferedReader(currPath)
            val mapType = TypeToken.getParameterized(
                MutableMap::class.java,
                String::class.java,
                TypeToken.getParameterized(MutableList::class.java, it.type.java).type
            ).type

            it.setValuesFromMap(gson.fromJson(reader, mapType))
        }
    }

    fun save() {
        registry.forEach {
            val currPath = configPath.resolve("${it.identifier}.json")
            if (!currPath.exists()) {
                currPath.createFile()
            }

            val writer = Files.newBufferedWriter(currPath)
            gson.toJson(it.values, writer)
            writer.close()
        }
    }

    private fun getLocationName(pos: BlockPos): Pair<String, BlockPos>? {
        if (!Location.inSkyblock) return null

        if (Location.island != Location.Island.Dungeon) return Pair(Location.island.displayName, pos)

        val dungeonFloorName = "${Location.island.displayName}-${Location.dungeonFloor}"
        val room = DungeonMap.roomIn(Vec2i(pos.x, pos.z)) ?: return Pair(dungeonFloorName, pos)
        if (room.owner?.data == null) return null

        if (!room.owner.inRoom(pos)) return null
        val foundOffset = room.owner.getOffset(pos) ?: return null
        return Pair("${Location.island.displayName}-${room.owner.data!!.name}", foundOffset)
    }
}