package link.e4mc.dialtone;

import link.e4mc.Agnos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Fixed connection code for offline mode: the host keeps one Dialtone (iroh) secret key,
 * so its endpoint id never changes. Players type the id as the server address.
 */
public class DialtoneCode {
    private static final Path KEY_FILE = Agnos.configDir().resolve("e4mc-dialtone-host.key");
    // The 64-char hex id split in two labels: Minecraft rejects host labels longer than 63 chars.
    private static final Pattern CODE = Pattern.compile("^([0-9a-f]{32})\\.([0-9a-f]{32})$");

    public static synchronized byte[] hostKey() {
        try {
            if (Files.exists(KEY_FILE)) {
                byte[] key = Files.readAllBytes(KEY_FILE);
                if (key.length != 32) {
                    throw new IOException(KEY_FILE + " must hold exactly 32 bytes");
                }
                return key;
            }
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            Files.write(KEY_FILE, key);
            return key;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load the Dialtone host key", e);
        }
    }

    public static String format(byte[] endpointId) {
        String hex = HexFormat.of().formatHex(endpointId);
        return hex.substring(0, 32) + "." + hex.substring(32);
    }

    /** The hex endpoint id if the host is a fixed code, else null. */
    public static String parse(String host) {
        var matcher = CODE.matcher(host.toLowerCase());
        return matcher.matches() ? matcher.group(1) + matcher.group(2) : null;
    }
}
