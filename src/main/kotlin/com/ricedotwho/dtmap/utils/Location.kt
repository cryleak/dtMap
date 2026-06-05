package com.ricedotwho.dtmap.utils

import com.ricedotwho.dtmap.events.LocationEvents
import com.ricedotwho.dtmap.features.map.Scoreboard
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.hypixel.modapi.HypixelModAPI
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket
import kotlin.jvm.optionals.getOrNull


object Location {
    var inSkyblock = false
    var island = Island.Unknown
    inline val dungeonFloor: Scoreboard.Floor
        get() = Scoreboard.floor

    fun register() {
        ClientPlayConnectionEvents.DISCONNECT.register(Disconnect)
        HypixelModAPI.getInstance().createHandler(ClientboundLocationPacket::class.java) { packet ->
            packet.serverType.getOrNull()?.let {
                inSkyblock = it.name() == "SKYBLOCK"
                if (!inSkyblock) {
                    island = Island.Unknown
                    return@let
                }

                island = Island.entries.find { it.mode == packet.mode.getOrNull() } ?: Island.Unknown
                if (island != Island.Unknown) LocationEvents.ON_CHANGE_ISLAND.invoker().changeIsland(island)
            }
        }
    }

    val Disconnect = ClientPlayConnectionEvents.Disconnect { handler, client ->
        inSkyblock = false
        island = Island.Unknown
    }

    enum class Island(val displayName: String, val mode: String) {
        Galatea("Galatea", "foraging_2"),
        PrivateIsland("Private Island", "dynamic"),
        Garden("Garden", "garden"),
        SpiderDen("Spider's Den", "combat_1"),
        CrimsonIsle("Crimson Isle", "crimson_isle"),
        TheEnd("The End", "combat_3"),
        GoldMine("Gold Mine", "mining_1"),
        DeepCaverns("Deep Caverns", "mining_2"),
        DwarvenMines("Dwarven Mines", "mining_3"),
        GlaciteMineshaft("Mineshaft", "mineshaft"),
        CrystalHollows("Crystal Hollows", "crystal_hollows"),
        FarmingIsland("The Farming Islands", "farming_1"),
        ThePark("The Park", "foraging_1"),
        Dungeon("Catacombs", "dungeon"),
        DungeonHub("Dungeon Hub", "dungeon_hub"),
        Hub("Hub", "hub"),
        DarkAuction("Dark Auction", "dark_auction"),
        JerryWorkshop("Jerry's Workshop", "winter"),
        Kuudra("Kuudra", "kuudra"),
        Rift("The Rift", "rift"),
        BackwaterBayou("Backwater Bayou", "fishing_1"),
        Unknown("(Unknown)", "");
    }
}