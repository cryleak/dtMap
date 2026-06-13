package com.ricedotwho.dtmap

import com.mojang.blaze3d.platform.InputConstants
import com.ricedotwho.dtmap.features.BloodRushInstaClear
import com.ricedotwho.dtmap.features.Key
import com.ricedotwho.dtmap.features.Leap
import com.ricedotwho.dtmap.features.PuzzleHud
import com.ricedotwho.dtmap.features.RoomSecrets
import com.ricedotwho.dtmap.features.SecretSpawnTimer
import com.ricedotwho.dtmap.features.SoloClear
import com.ricedotwho.dtmap.features.map.DungeonMap
import com.ricedotwho.dtmap.features.map.RoomData
import com.ricedotwho.dtmap.gui.Hud
import com.ricedotwho.dtmap.gui.ImGuiHandler
import com.ricedotwho.dtmap.gui.Settings
import com.ricedotwho.dtmap.utils.DungeonMessages
import com.ricedotwho.dtmap.utils.IrisCompatibility
import com.ricedotwho.dtmap.utils.Location
import com.ricedotwho.dtmap.utils.LocationObjects
import com.ricedotwho.dtmap.utils.MapImageLoader
import com.ricedotwho.dtmap.utils.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import kotlin.coroutines.EmptyCoroutineContext


object DtMap : ClientModInitializer {
    val LOGGER = LoggerFactory.getLogger("dtmap")
    val mc: Minecraft
        get() = Minecraft.getInstance()
    val scope = CoroutineScope(SupervisorJob() + EmptyCoroutineContext)

    lateinit var keybindShowHud: KeyMapping

    override fun onInitializeClient() {

        Settings
        MapImageLoader
        Settings.validateImageSelection()

        ClientLifecycleEvents.CLIENT_STARTED.register {
            RoomData
        }

        val keybindCategory = KeyMapping.Category(Identifier.fromNamespaceAndPath("dtmap", "dungeons"))

        keybindShowHud = KeyMappingHelper.registerKeyMapping(KeyMapping(
            "key.dtmap.hud",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            keybindCategory
        ))

        ClientCommandRegistrationCallback.EVENT.register(ClientCommandRegistrationCallback { dispatcher, _ ->
            dispatcher.register(literal("dtm")
                .then(literal("hud").executes {
                    Scheduler.scheduleTickEnd {
                        Hud.open()
                    }
                    1
                })
                .executes {
                    Scheduler.scheduleTickEnd {
                        Settings.open()
                    }
                    1
                }
            )
        })

        ImGuiHandler
        Location.register()
        DungeonMap.register()
        DungeonMessages.register()
        SecretSpawnTimer.register()
        Key.register()
        Scheduler.register()
        SoloClear.register()
        BloodRushInstaClear.register()
        PuzzleHud
        RoomSecrets.register()

        LocationObjects.load()
        LocationObjects.register()
        Leap.register()

        IrisCompatibility

        Hud.register()
    }
}
