package com.ricedotwho.dtmap.events

import net.fabricmc.fabric.api.event.EventFactory
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.world.entity.Entity
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.util.Optional

object RenderEvents {
    fun interface ShouldRenderEvent {
        class Data(val entity: Entity, val frustum: Frustum, val x: Double, val y: Double, val z: Double, var returned: Optional<Boolean> = Optional.empty()) {
            fun setReturn(r: Boolean) {
                this.returned = Optional.of(r)
            }
        }
        fun shouldRender(event: Data)
    }

    val SHOULD_RENDER_EVENT = EventFactory.createArrayBacked(ShouldRenderEvent::class.java) { listeners ->
        ShouldRenderEvent { event ->
            listeners.forEach {
                it.shouldRender(event)
            }
        }
    }

    fun triggerShouldRender(entity: Entity, frustum: Frustum, x: Double, y: Double, z: Double, cir: CallbackInfoReturnable<Boolean>) {
        val event = ShouldRenderEvent.Data(entity, frustum, x, y, z)
        SHOULD_RENDER_EVENT.invoker().shouldRender(event)
        if (!event.returned.isEmpty) {
            cir.returnValue = event.returned.get()
        }
    }
}