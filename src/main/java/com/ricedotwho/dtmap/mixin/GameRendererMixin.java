package com.ricedotwho.dtmap.mixin;

import com.ricedotwho.dtmap.gui.SkijaScreen;
import com.ricedotwho.dtmap.utils.render.skija.SkijaRenderer;
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
        if (minecraft.gui.screen() instanceof final SkijaScreen screen) {
            int width = minecraft.getWindow().getGuiScaledWidth();
            int height = minecraft.getWindow().getGuiScaledHeight();
            double mouseX = minecraft.mouseHandler.getScaledXPos(minecraft.getWindow());
            double mouseY = minecraft.mouseHandler.getScaledYPos(minecraft.getWindow());

            if (SkijaRenderer.beginOverlayFrame(width, height)) {
                try {
                    screen.renderSkija(mouseX, mouseY, tickCounter.getGameTimeDeltaPartialTick(true));
                } finally {
                    SkijaRenderer.endOverlayFrame();
                }
            }
            SkijaRenderer.compositeOverlay();
        }
    }
}
