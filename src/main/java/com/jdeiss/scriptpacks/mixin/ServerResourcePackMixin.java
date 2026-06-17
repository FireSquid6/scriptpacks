package com.jdeiss.scriptpacks.mixin;

import com.jdeiss.scriptpacks.resource.ResourcePackInjector;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(MinecraftServer.class)
public class ServerResourcePackMixin {

    @Inject(method = "getServerResourcePack", at = @At("HEAD"), cancellable = true)
    private void scriptpacks$injectResourcePack(CallbackInfoReturnable<Optional<MinecraftServer.ServerResourcePackInfo>> cir) {
        Optional<MinecraftServer.ServerResourcePackInfo> injected = ResourcePackInjector.getInjectedPack();
        if (injected.isPresent()) {
            cir.setReturnValue(injected);
        }
    }
}
