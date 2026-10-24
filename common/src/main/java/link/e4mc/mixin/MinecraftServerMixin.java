package link.e4mc.mixin;

import link.e4mc.Config;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Client-only mixin, so it only touches the integrated (LAN) server, never a dedicated one.
@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
    @Inject(method = "usesAuthentication", at = @At("HEAD"), cancellable = true)
    private void offlineMode(CallbackInfoReturnable<Boolean> cir) {
        if (Config.INSTANCE.offlineMode.value()) {
            cir.setReturnValue(false);
        }
    }
}
