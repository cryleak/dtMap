package com.ricedotwho.dtmap.features

import com.ricedotwho.dtmap.DtMap.mc
import com.ricedotwho.dtmap.events.MapEvents
import com.ricedotwho.dtmap.features.map.DungeonMap
import com.ricedotwho.dtmap.gui.Hud
import com.ricedotwho.dtmap.gui.Hud.Condition
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.awt.Color

object RoomSecrets : Hud.Component("room-secrets", 0.2, 0.6, Hud.Type.Dungeon, 1.5f,
    staticRenderConditions = mutableListOf(Condition.Boss, Condition.Clear, Condition.Alt, Condition.HideOverlaySecrets),
    allowedStaticRenderConditions = mutableListOf(Condition.Boss, Condition.F7Boss, Condition.Clear, Condition.BeforeMort, Condition.Alt, Condition.HideOverlaySecrets)) {
    val secretsRegex = Regex(".+§7(\\d+)/\\d+ Secrets")
    var currentRoomSecrets: Int? = null

    fun register() {
        MapEvents.ON_PLAYER_ENTER_ROOM.register { room ->
            if (room == null) {
                currentRoomSecrets = null
            }

            room?.owner?.let {
                currentRoomSecrets = null
            }
        }

        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register { _, _ ->
            currentRoomSecrets = null
        }
    }

    fun onOverlay(packet: ClientboundSystemChatPacket, ci: CallbackInfo) {
        secretsRegex.find(packet.content.string)?.let { found ->
            currentRoomSecrets = found.groups[1]?.value?.toIntOrNull()
            if (staticRenderConditions.contains(Condition.HideOverlaySecrets)) {
                currentRoomSecrets?.let {
                    val first = found.groups[1]!!.range.first
                    ci.cancel()
                    val content = packet.content.string.substring(0 until first - 2).trimEnd()
                    mc.connection!!.handleSystemChat(ClientboundSystemChatPacket(Component.literal(content), true))
                }
            }
        }
    }

    override fun render(context: GuiGraphicsExtractor) {
        val currentRoomSecrets = currentRoomSecrets ?: return
        val currentRoom = DungeonMap.roomPlayerIn() ?: return

        val secretsInRoom = currentRoom.owner!!.data!!.secrets
        val percent = currentRoomSecrets.toFloat() / secretsInRoom.toFloat()
        val color = when {
            percent < 0.5f -> Color.RED
            percent < 1f -> Color.YELLOW
            else -> Color.GREEN
        }

        context.text(mc.font, "${currentRoomSecrets}/${secretsInRoom}", 0, 0, color.rgb, true)
    }

    override fun example(context: GuiGraphicsExtractor) {
        context.text(mc.font, "2/4", 0, 0, Color.YELLOW.rgb, true)
    }

    override fun bounds(): Pair<Double, Double> =
        Pair(mc.font.width("2/4").toDouble(), mc.font.lineHeight.toDouble())
}