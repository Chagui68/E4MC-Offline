package link.e4mc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import link.e4mc.DialtoneConnectionExtensions;
import link.e4mc.Mirror;
import link.e4mc.OfflineAuth;
import link.e4mc.SmugglersInetSocketAddress;
import link.e4mc.dialtone.DialtoneAddress;
import link.e4mc.dialtone.DialtoneAmbientSession;
import link.e4mc.dialtone.DialtoneChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.crypto.Cipher;
import java.net.InetAddress;
import java.net.InetSocketAddress;

@Mixin(Connection.class)
public abstract class ConnectionMixin implements DialtoneConnectionExtensions, OfflineAuth.State {

    @Shadow private Channel channel;
    @Shadow private volatile PacketListener packetListener;
    @Shadow public abstract boolean isMemoryConnection();
    @Unique
    private static DialtoneAddress e4mc$smuggledDialtoneAddress = null;
    @Unique
    private volatile boolean e4mc$loggedIn = false;
    @Unique
    private volatile long e4mc$lockedSince = 0;

    @Override
    public boolean e4mc$isLoggedIn() {
        return e4mc$loggedIn;
    }

    @Override
    public void e4mc$setLoggedIn() {
        e4mc$loggedIn = true;
    }

    @Override
    public long e4mc$lockedSince(long now) {
        if (e4mc$lockedSince == 0) {
            e4mc$lockedSince = now;
        }
        return e4mc$lockedSince;
    }

    // Offline mode: drop every gameplay packet from a player that has not logged in yet.
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void gateUnauthenticated(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (e4mc$loggedIn || !(packetListener instanceof ServerGamePacketListenerImpl listener)) {
            return;
        }
        if (!OfflineAuth.needsLogin(listener.player.getGameProfile(), isMemoryConnection())) {
            e4mc$loggedIn = true;
            return;
        }
        long now = System.currentTimeMillis();
        boolean firstPacket = e4mc$lockedSince == 0;
        long lockedFor = now - e4mc$lockedSince(now);
        var server = listener.player.getServer();
        if (lockedFor > OfflineAuth.LOGIN_TIMEOUT_MS) {
            ci.cancel();
            server.execute(() -> listener.disconnect(Mirror.literal("You did not log in in time.")));
        } else if (!OfflineAuth.isAllowedWhileLocked(packet)) {
            ci.cancel();
            if (firstPacket || packet instanceof ServerboundChatPacket || packet instanceof ServerboundChatCommandPacket) {
                server.execute(() -> listener.player.sendSystemMessage(OfflineAuth.prompt(listener.player.getGameProfile().getName())));
            }
        } else if (firstPacket) {
            server.execute(() -> listener.player.sendSystemMessage(OfflineAuth.prompt(listener.player.getGameProfile().getName())));
        }
    }

    @Override
    public byte[] e4mc$exportKeyingMaterial(byte[] label, byte[] context, int length) {
        if (channel instanceof DialtoneChannel dialtoneChannel) {
            return dialtoneChannel.exportKeyingMaterial(label, context, length);
        }
        return null;
    }

    @Override
    public String e4mc$connInfo() {
        if (channel instanceof DialtoneChannel dialtoneChannel) {
            return dialtoneChannel.debugInfo();
        }
        return channel.getClass().getCanonicalName();
    }

    @Inject(method = "/^(connect|method_52271|m_290025_)$/", at = @At("HEAD"), require = 0)
    private static void hijackStart(InetSocketAddress inetSocketAddress, @Coerce Object obj, Connection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress = new DialtoneAddress(smuggledAddress.ticket);
        }
    }

    @Surrogate
    private static void hijackStart(InetSocketAddress inetSocketAddress, boolean bl, Connection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress = new DialtoneAddress(smuggledAddress.ticket);
        }
    }

    @Inject(method = "name=/^(connectToServer|method_10753|m_178300_)$/ desc=/^\\(Ljava\\/net\\/InetSocketAddress;Z\\)L.+;$/", at = @At("HEAD"), require = 0)
    private static void hijackStartAlt(InetSocketAddress inetSocketAddress, boolean bl, CallbackInfoReturnable<Connection> cir) {
        if (inetSocketAddress instanceof SmugglersInetSocketAddress smuggledAddress) {
            e4mc$smuggledDialtoneAddress = new DialtoneAddress(smuggledAddress.ticket);
        }
    }

    @ModifyArg(method = "/^(connect|method_52271|m_290025_|connectToServer|method_10753|m_178300_)$/", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;"))
    private static Class hijackChannel(Class clazz) {
        if (e4mc$smuggledDialtoneAddress != null) {
            return DialtoneChannel.class;
        } else {
            return clazz;
        }
    }

    @ModifyArg(method = "/^(connect|method_52271|m_290025_|connectToServer|method_10753|m_178300_)$/", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/AbstractBootstrap;"))
    private static EventLoopGroup hijackGroup(EventLoopGroup group) {
        if (e4mc$smuggledDialtoneAddress != null) {
            return DialtoneAmbientSession.INSTANCE.group;
        } else {
            return group;
        }
    }

    @WrapOperation(method = "/^(connect|method_52271|m_290025_|connectToServer|method_10753|m_178300_)$/", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;"))
    private static ChannelFuture hijackConnect(Bootstrap instance, InetAddress inetHost, int inetPort, Operation<ChannelFuture> operation) {
        if (e4mc$smuggledDialtoneAddress != null) {
            var ret = instance.connect(e4mc$smuggledDialtoneAddress);
            e4mc$smuggledDialtoneAddress = null;
            return ret;
        } else {
            return operation.call(instance, inetHost, inetPort);
        }
    }

    @Inject(method = "setEncryptionKey", at = @At(value = "FIELD", target = "Lnet/minecraft/network/Connection;channel:Lio/netty/channel/Channel;", opcode = Opcodes.GETFIELD, ordinal = 0), cancellable = true)
    private void killDoubleEncryption(Cipher cipher, Cipher cipher2, CallbackInfo ci) {
        if (channel instanceof DialtoneChannel) {
            ci.cancel();
        }
    }
}
