package id.zentrasmp.intro;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Helper kompatibilitas lintas versi (Purpur/Paper 1.21 sampai 26+) dan Geyser/Floodgate.
 * Semuanya berbasis refleksi, jadi tidak ada ketergantungan compile-time ke versi tertentu.
 */
final class Compat {

    private Compat() { }

    /**
     * Mengambil konstanta statis (enum / field interface) berdasarkan nama.
     * Mencoba beberapa nama berurutan agar tahan terhadap perubahan nama antar versi.
     * Mengembalikan null jika tidak ada satu pun yang cocok.
     */
    static <T> T constant(Class<T> type, String... names) {
        for (String name : names) {
            try {
                Field field = type.getField(name);
                Object value = field.get(null);
                if (type.isInstance(value)) {
                    return type.cast(value);
                }
            } catch (Throwable ignored) {
                // coba nama berikutnya
            }
        }
        return null;
    }

    private static Class<?> load(String className, String... pluginNames) {
        for (String pluginName : pluginNames) {
            try {
                Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
                if (plugin != null) {
                    return Class.forName(className, true, plugin.getClass().getClassLoader());
                }
            } catch (Throwable ignored) {
                // lanjut
            }
        }
        try {
            return Class.forName(className);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Deteksi pemain Bedrock: Floodgate -> Geyser API -> pola UUID Floodgate. */
    static boolean isBedrock(Player player) {
        UUID id = player.getUniqueId();
        try {
            Class<?> cls = load("org.geysermc.floodgate.api.FloodgateApi", "floodgate");
            if (cls != null) {
                Object api = cls.getMethod("getInstance").invoke(null);
                Object result = cls.getMethod("isFloodgatePlayer", UUID.class).invoke(api, id);
                if (result instanceof Boolean b && b) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Floodgate tidak ada
        }
        try {
            Class<?> cls = load("org.geysermc.geyser.api.GeyserApi", "Geyser-Spigot", "Geyser-Paper", "Geyser");
            if (cls != null) {
                Object api = cls.getMethod("api").invoke(null);
                Object result = cls.getMethod("isBedrockPlayer", UUID.class).invoke(api, id);
                if (result instanceof Boolean b && b) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Geyser API tidak ada
        }
        return id.getMostSignificantBits() == 0L;
    }
}
