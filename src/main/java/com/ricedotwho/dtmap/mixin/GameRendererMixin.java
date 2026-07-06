package com.ricedotwho.dtmap.mixin;

import com.ricedotwho.dtmap.gui.ImGuiHandler;
import imgui.ImGui;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "render", at = @At("RETURN"))
    private void render(DeltaTracker tickCounter, boolean tick, CallbackInfo ci) {
        if (minecraft.gui.screen() instanceof final ImGuiHandler.RenderInterface renderInterface) {
            ImGuiHandler.INSTANCE.start();
            renderInterface.render(ImGui.getIO());
            ImGuiHandler.INSTANCE.end();
        }
    }
}
