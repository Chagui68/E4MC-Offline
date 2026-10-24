package link.e4mc;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Offline mode login: premium names still authenticate with Mojang, every other name
 * must /register and /login with a password before it can do anything.
 */
public class OfflineAuth {
    public static final long LOGIN_TIMEOUT_MS = 60_000;
    private static final int MIN_PASSWORD_LENGTH = 4;
    private static final int PBKDF2_ITERATIONS = 20_000;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final Map<String, Boolean> PREMIUM_CACHE = new ConcurrentHashMap<>();
    private static final Path ACCOUNTS_FILE = Agnos.configDir().resolve("e4mc-offline-accounts.properties");
    private static Properties accounts;

    /** Per-connection login state, implemented on Connection by ConnectionMixin. */
    public interface State {
        boolean e4mc$isLoggedIn();
        void e4mc$setLoggedIn();
        /** Time of the first packet seen while locked, or 0. */
        long e4mc$lockedSince(long now);
        /** Sends the reason to the client, then closes the connection. */
        void e4mc$kick(Component reason);
    }

    // The mod is compiled against 1.20.2 but runs on other versions. Class literals cover the
    // compile target and Fabric (intermediary names survive package moves); the old names cover
    // Forge 1.20.1, where these packets still live in the game package.
    private static final Set<Class<?>> ALLOWED_WHILE_LOCKED = resolveClasses(
            List.of(
                    () -> ServerboundKeepAlivePacket.class,
                    () -> ServerboundPongPacket.class,
                    () -> ServerboundClientInformationPacket.class,
                    () -> ServerboundCustomPayloadPacket.class,
                    () -> ServerboundResourcePackPacket.class,
                    () -> ServerboundAcceptTeleportationPacket.class,
                    () -> ServerboundChunkBatchReceivedPacket.class,
                    () -> ServerboundChatAckPacket.class,
                    () -> ServerboundChatSessionUpdatePacket.class,
                    () -> ServerboundConfigurationAcknowledgedPacket.class),
            "net.minecraft.network.protocol.game.ServerboundKeepAlivePacket",
            "net.minecraft.network.protocol.game.ServerboundPongPacket",
            "net.minecraft.network.protocol.game.ServerboundClientInformationPacket",
            "net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket",
            "net.minecraft.network.protocol.game.ServerboundResourcePackPacket");
    private static final Constructor<?> DISCONNECT_PACKET = resolveClasses(
            List.of(() -> ClientboundDisconnectPacket.class),
            "net.minecraft.network.protocol.game.ClientboundDisconnectPacket")
            .stream()
            .map(clazz -> {
                try {
                    return clazz.getConstructor(Component.class);
                } catch (NoSuchMethodException e) {
                    return null;
                }
            })
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
    private static volatile Field connectionField;

    private static Set<Class<?>> resolveClasses(List<Supplier<Class<?>>> literals, String... oldNames) {
        Set<Class<?>> classes = new HashSet<>();
        for (Supplier<Class<?>> literal : literals) {
            try {
                classes.add(literal.get());
            } catch (LinkageError ignored) {
                // Not in this Minecraft version.
            }
        }
        for (String name : oldNames) {
            try {
                classes.add(Class.forName(name, false, OfflineAuth.class.getClassLoader()));
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Not in this Minecraft version or mapping.
            }
        }
        return classes;
    }

    /** The disconnect packet for this Minecraft version, or null if it cannot be built. */
    public static Packet<?> disconnectPacket(Component reason) {
        try {
            return DISCONNECT_PACKET == null ? null : (Packet<?>) DISCONNECT_PACKET.newInstance(reason);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public static boolean enabled() {
        return Config.INSTANCE.offlineMode.value();
    }

    /**
     * Whether a Mojang account with this name exists. Premium names keep Mojang authentication,
     * so a cracked client cannot take a premium player's name.
     * Asynchronous so a slow Mojang API never blocks the network thread other players share.
     */
    public static CompletableFuture<Boolean> isPremium(String name) {
        Boolean cached = PREMIUM_CACHE.get(name.toLowerCase());
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(new URI("https://api.mojang.com/users/profiles/minecraft/" + name))
                    .timeout(Duration.ofSeconds(5))
                    .build();
        } catch (Exception e) {
            return CompletableFuture.completedFuture(false);
        }
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding()).handle((response, error) -> {
            if (error == null && (response.statusCode() == 200 || response.statusCode() == 204 || response.statusCode() == 404)) {
                boolean premium = response.statusCode() == 200;
                PREMIUM_CACHE.put(name.toLowerCase(), premium);
                return premium;
            }
            // Not cached: retry next login. Non-premium still has to pass the password check.
            E4mcClient.LOGGER.warn("Mojang profile lookup for {} failed ({}), treating as non-premium", name,
                    error != null ? error.toString() : "HTTP " + response.statusCode());
            return false;
        });
    }

    /** Players with an offline (unverified) UUID must log in. The host on the local connection never does. */
    public static boolean needsLogin(GameProfile profile, boolean memoryConnection) {
        return enabled() && !memoryConnection && profile.getId().equals(UUIDUtil.createOfflinePlayerUUID(profile.getName()));
    }

    /** Packets a locked player may still send: connection upkeep plus /login and /register. */
    public static boolean isAllowedWhileLocked(Packet<?> packet) {
        if (packet instanceof ServerboundChatCommandPacket command) {
            String name = command.command().split(" ", 2)[0];
            return name.equals("login") || name.equals("register");
        }
        for (Class<?> allowed : ALLOWED_WHILE_LOCKED) {
            if (allowed.isInstance(packet)) {
                return true;
            }
        }
        return false;
    }

    public static Component prompt(String name) {
        String text = isRegistered(name)
                ? "This server is in offline mode. Log in with /login <password>"
                : "This server is in offline mode. Register with /register <password> <password>";
        return Mirror.withStyle(Mirror.literal(text), it -> it.withColor(ChatFormatting.GOLD));
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("register")
                .requires(src -> enabled())
                .then(Commands.argument("password", StringArgumentType.word())
                        .then(Commands.argument("confirm", StringArgumentType.word())
                                .executes(OfflineAuth::registerCommand))));
        dispatcher.register(Commands.literal("login")
                .requires(src -> enabled())
                .then(Commands.argument("password", StringArgumentType.word())
                        .executes(OfflineAuth::loginCommand)));
    }

    private static int registerCommand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        State state = state(player);
        String name = player.getGameProfile().getName();
        String password = StringArgumentType.getString(ctx, "password");
        if (state.e4mc$isLoggedIn() || !needsLogin(player)) {
            return fail(player, "You are already logged in.");
        }
        if (isRegistered(name)) {
            return fail(player, "This name is already registered. Use /login <password>");
        }
        if (!password.equals(StringArgumentType.getString(ctx, "confirm"))) {
            return fail(player, "The passwords do not match.");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return fail(player, "The password must have at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        try {
            saveAccount(name, password);
        } catch (IOException e) {
            E4mcClient.LOGGER.error("Failed to save offline account for {}", name, e);
            return fail(player, "Could not save the account. Ask the host to check the logs.");
        }
        state.e4mc$setLoggedIn();
        player.sendSystemMessage(Mirror.withStyle(Mirror.literal("Registered and logged in."), it -> it.withColor(ChatFormatting.GREEN)));
        return 1;
    }

    private static int loginCommand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        State state = state(player);
        String name = player.getGameProfile().getName();
        if (state.e4mc$isLoggedIn() || !needsLogin(player)) {
            return fail(player, "You are already logged in.");
        }
        if (!isRegistered(name)) {
            return fail(player, "This name is not registered. Use /register <password> <password>");
        }
        if (!checkPassword(name, StringArgumentType.getString(ctx, "password"))) {
            // Kick on a wrong password so guessing needs a reconnect per attempt.
            state.e4mc$kick(Mirror.literal("Wrong password."));
            return 0;
        }
        state.e4mc$setLoggedIn();
        player.sendSystemMessage(Mirror.withStyle(Mirror.literal("Logged in."), it -> it.withColor(ChatFormatting.GREEN)));
        return 1;
    }

    private static int fail(ServerPlayer player, String message) {
        player.sendSystemMessage(Mirror.withStyle(Mirror.literal(message), it -> it.withColor(ChatFormatting.RED)));
        return 0;
    }

    private static State state(ServerPlayer player) {
        return (State) connection(player);
    }

    private static boolean needsLogin(ServerPlayer player) {
        return needsLogin(player.getGameProfile(), connection(player).isMemoryConnection());
    }

    // The Connection field moved to ServerCommonPacketListenerImpl in 1.20.2, so find it by type.
    private static Connection connection(ServerPlayer player) {
        try {
            if (connectionField == null) {
                for (Class<?> clazz = player.connection.getClass(); clazz != null && connectionField == null; clazz = clazz.getSuperclass()) {
                    for (Field field : clazz.getDeclaredFields()) {
                        if (field.getType() == Connection.class) {
                            field.setAccessible(true);
                            connectionField = field;
                            break;
                        }
                    }
                }
            }
            return (Connection) connectionField.get(player.connection);
        } catch (ReflectiveOperationException | NullPointerException e) {
            throw new IllegalStateException("Cannot find the player's connection", e);
        }
    }

    private static synchronized Properties accounts() {
        if (accounts == null) {
            accounts = new Properties();
            if (Files.exists(ACCOUNTS_FILE)) {
                try (Reader reader = Files.newBufferedReader(ACCOUNTS_FILE)) {
                    accounts.load(reader);
                } catch (IOException e) {
                    // Keep a null cache so a broken file is retried, never overwritten with an empty one.
                    accounts = null;
                    throw new RuntimeException("Failed to read " + ACCOUNTS_FILE, e);
                }
            }
        }
        return accounts;
    }

    public static synchronized boolean isRegistered(String name) {
        return accounts().containsKey(name);
    }

    private static synchronized void saveAccount(String name, String password) throws IOException {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        Base64.Encoder b64 = Base64.getEncoder();
        accounts().setProperty(name, b64.encodeToString(salt) + ":" + b64.encodeToString(hash(password, salt)));
        try (Writer writer = Files.newBufferedWriter(ACCOUNTS_FILE)) {
            accounts().store(writer, "E4MC Offline accounts: player name = salt:PBKDF2 hash. Delete a line to reset that player's password.");
        }
    }

    static synchronized boolean checkPassword(String name, String password) {
        String stored = accounts().getProperty(name);
        if (stored == null) {
            return false;
        }
        String[] parts = stored.split(":", 2);
        Base64.Decoder b64 = Base64.getDecoder();
        return MessageDigest.isEqual(hash(password, b64.decode(parts[0])), b64.decode(parts[1]));
    }

    private static byte[] hash(String password, byte[] salt) {
        try {
            var spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
