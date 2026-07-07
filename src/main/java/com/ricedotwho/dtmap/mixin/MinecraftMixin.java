package com.ricedotwho.dtmap.mixin;

import com.ricedotwho.dtmap.utils.render.skija.SkijaRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Inject(method = "close", at = @At("HEAD"))
    public void closeSkija(CallbackInfo ci) {
        SkijaRenderer.cleanup();
    }
}


