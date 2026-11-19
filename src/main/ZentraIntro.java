package id.zentrasmp.intro;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * ZentraIntro - intro sinematik saat pemain join.
 *
 * Alur: join (langsung kebal) -> tunggu loading -> /spawn -> kamera sinematik terbang
 * (judul, bossbar, timelapse, partikel, kembang api) -> layar menggelap perlahan ->
 * pemain dikembalikan ke spawn -> layar terang perlahan -> kontrol dikembalikan.
 *
 * Tanpa NMS, tanpa resource pack. Partikel/efek di-resolve lewat nama (refleksi)
 * sehingga tahan perubahan versi. Aman untuk pemain Bedrock lewat Geyser/Floodgate.
 */
public class ZentraIntro extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    // Tahap penutup (setelah bagian utama selesai), dalam tick
    private static final int OUTRO_FADE = 66;   // dengan fade gelap -> terang
    private static final int OUTRO_PLAIN = 20;  // tanpa fade
    private static final int HOLD = 45;         // lama layar gelap total
    private static final int FADE = 20;         // lama fade terang (blindness vanilla = 20 tick)
    private static final int DARK_LEAD = 50;    // darkness mulai sekian tick sebelum akhir

    private static final double[][] DEFAULT_KEYFRAMES = {
        {0, 12, 90, 1.62, 1.62},
        {80, 10, 160, 4, 5},
        {160, 16, 245, 11, 8},
        {240, 28, 300, 24, 6},
        {320, 22, 385, 30, 14},
        {390, 8, 470, 70, 400}
    };

    private static final Color[][] PALETTES = {
        {Color.fromRGB(255, 210, 90), Color.WHITE},
        {Color.fromRGB(100, 240, 255), Color.WHITE},
        {Color.fromRGB(255, 110, 200), Color.fromRGB(255, 210, 90)},
        {Color.fromRGB(120, 255, 160), Color.WHITE},
        {Color.fromRGB(255, 150, 60), Color.fromRGB(255, 220, 120)},
        {Color.fromRGB(160, 120, 255), Color.fromRGB(100, 240, 255)}
    };

    private final Map<UUID, Session> sessions = new HashMap<>();
    private Settings cfg;

    private Particle pEndRod;
    private Particle pDust;
    private Particle pFirework;
    private Particle pWitch;
    private PotionEffectType eInvisible;
    private PotionEffectType eBlind;
    private PotionEffectType eDark;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        resolveTypes();
        cfg = load();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("ZentraIntro aktif. Durasi bagian utama: " + (cfg.main / 20.0) + " detik.");
    }

    @Override
    public void onDisable() {
        for (Session s : new ArrayList<>(sessions.values())) {
            finish(s, false);
        }
        sessions.clear();
    }

    private void resolveTypes() {
        pEndRod = Compat.constant(Particle.class, "END_ROD");
        pDust = Compat.constant(Particle.class, "DUST", "REDSTONE");
        pFirework = Compat.constant(Particle.class, "FIREWORK", "FIREWORKS_SPARK");
        pWitch = Compat.constant(Particle.class, "WITCH", "SPELL_WITCH");
        eInvisible = Compat.constant(PotionEffectType.class, "INVISIBILITY");
        eBlind = Compat.constant(PotionEffectType.class, "BLINDNESS");
        eDark = Compat.constant(PotionEffectType.class, "DARKNESS");
    }

    // ------------------------------------------------------------------ config

    private static final class Step {
        final String title;
        final String sub;
        final int ticks;

        Step(String title, String sub, int ticks) {
            this.title = title;
            this.sub = sub;
            this.ticks = ticks;
        }
    }

    private static final class Settings {
        boolean enabled;
        String serverName;
        String spawnCommand;
        boolean fallbackSpawn;
        int maxWait;
        int afterTp;
        int joinDelay;
        int bedrockExtra;
        boolean firstJoinOnly;
        String bypassPerm;
        boolean blockCommands;
        double orbit;
        int bedrockInterval;
        double[] kt;
        double[][] kv;
        final List<Step> steps = new ArrayList<>();
        String endTitle;
        String endSub;
        String barText;
        boolean timelapse;
        int timeStart;
        int timeEnd;
        boolean fireworks;
        boolean fantasy;
        boolean fade;
        boolean darkness;
        boolean sounds;
        int main;
    }

    private Settings load() {
        reloadConfig();
        FileConfiguration c = getConfig();
        Settings s = new Settings();
        s.enabled = c.getBoolean("enabled", true);
        s.serverName = c.getString("server-name", "ZentraSMP");
        s.spawnCommand = c.getString("spawn.command", "spawn %player%");
        s.fallbackSpawn = c.getBoolean("spawn.fallback-to-world-spawn", true);
        s.maxWait = Math.max(5, c.getInt("spawn.max-wait-ticks", 30));
        s.afterTp = Math.max(0, c.getInt("spawn.after-teleport-delay-ticks", 10));
        s.joinDelay = Math.max(1, c.getInt("join.delay-ticks", 40));
        s.bedrockExtra = Math.max(0, c.getInt("join.bedrock-extra-delay-ticks", 30));
        s.firstJoinOnly = c.getBoolean("join.first-join-only", false);
        s.bypassPerm = c.getString("join.bypass-permission", "zentraintro.bypass");
        s.blockCommands = c.getBoolean("join.block-commands", true);
        s.orbit = Math.max(2.0, c.getDouble("camera.orbit-distance", 12.0));
        s.bedrockInterval = Math.max(1, Math.min(4, c.getInt("camera.bedrock-update-interval", 1)));

        // keyframe kamera: "tick,radius,sudut,tinggi,tinggiTatapan"
        List<double[]> rows = new ArrayList<>();
        for (String line : c.getStringList("camera.keyframes")) {
            try {
                String[] a = line.split(",");
                if (a.length < 5) {
                    continue;
                }
                double[] r = new double[5];
                for (int i = 0; i < 5; i++) {
                    r[i] = Double.parseDouble(a[i].trim());
                }
                rows.add(r);
            } catch (NumberFormatException ignored) {
                // baris diabaikan
            }
        }
        rows.sort(Comparator.comparingDouble(r -> r[0]));
        boolean ok = rows.size() >= 2 && rows.get(0)[0] == 0.0;
        for (int i = 1; ok && i < rows.size(); i++) {
            if (rows.get(i)[0] <= rows.get(i - 1)[0]) {
                ok = false;
            }
        }
        if (!ok) {
            if (!c.getStringList("camera.keyframes").isEmpty()) {
                getLogger().warning("camera.keyframes tidak valid, memakai bawaan.");
            }
            rows = new ArrayList<>(Arrays.asList(DEFAULT_KEYFRAMES));
        }
        s.kt = new double[rows.size()];
        s.kv = new double[rows.size()][4];
        for (int i = 0; i < rows.size(); i++) {
            double[] r = rows.get(i);
            s.kt[i] = r[0];
            s.kv[i] = new double[]{r[1], r[2], r[3], r[4]};
        }

        // judul bergantian
        for (Map<?, ?> m : c.getMapList("sequence.steps")) {
            Object t = m.get("title");
            Object st = m.get("subtitle");
            Object tk = m.get("ticks");
            int ticks = tk instanceof Number n ? n.intValue() : 80;
            s.steps.add(new Step(t == null ? "" : t.toString(), st == null ? "" : st.toString(), Math.max(20, ticks)));
        }
        if (s.steps.isEmpty()) {
            s.steps.add(new Step("<gradient:#00e5ff:#7c4dff><bold>%server%", "<gray>Selamat datang, <white>%player%", 80));
            s.steps.add(new Step("<gradient:#00e5ff:#7c4dff><bold>DUNIA BARU", "<gray>Bangun. Bertahan. Berkuasa.", 80));
            s.steps.add(new Step("<gradient:#ffd54f:#ff7043><bold>KOMUNITAS", "<gray>Teman baru menunggumu di sini", 80));
            s.steps.add(new Step("<gradient:#69f0ae:#00bfa5><bold>PETUALANGAN", "<gray>Setiap hari ada hal seru", 80));
            s.steps.add(new Step("<gradient:#00e5ff:#7c4dff><bold>SIAP?", "<gray>Selamat bermain di <aqua>%server%", 70));
        }
        int total = 0;
        for (Step st : s.steps) {
            total += st.ticks;
        }
        s.main = Math.max(100, total);
        s.endTitle = c.getString("sequence.end-title", "<gradient:#69f0ae:#00e5ff><bold>SELAMAT BERMAIN!");
        s.endSub = c.getString("sequence.end-subtitle", "<gray>Semoga betah di <aqua>%server%");
        s.barText = c.getString("sequence.bar-text", "<aqua><bold>%server%</bold> <gray>» <white>Memuat dunia... <yellow>%percent%%");

        s.timelapse = c.getBoolean("effects.timelapse", true);
        s.timeStart = c.getInt("effects.time-start", 22000);
        s.timeEnd = c.getInt("effects.time-end", 30000);
        s.fireworks = c.getBoolean("effects.fireworks", true);
        s.fantasy = c.getBoolean("effects.fantasy-particles", true);
        s.fade = c.getBoolean("effects.fade-to-black", true);
        s.darkness = c.getBoolean("effects.darkness-effect", true);
        s.sounds = c.getBoolean("effects.sounds", true);
        return s;
    }

    // ------------------------------------------------------------------ session

    private static final class Session {
        final Player player;
        final UUID id;
        boolean bedrock;
        boolean running;
        World world;
        Location spawn;
        Location center;
        double baseY;
        double th0;
        GameMode prevMode;
        boolean prevAllow;
        boolean prevFlying;
        boolean prevInvul;
        boolean prevCollidable;
        BossBar bar;
        BukkitTask task;
        BukkitTask waitTask;
        int tick;
        int step = -1;

        Session(Player player) {
            this.player = player;
            this.id = player.getUniqueId();
        }
    }

    private boolean valid(Session s) {
        return s.player.isOnline() && sessions.get(s.id) == s;
    }

    /** Memulai alur intro untuk pemain (join normal maupun /zentraintro play). */
    private void launch(Player p, int delay) {
        if (!p.isOnline() || sessions.containsKey(p.getUniqueId())) {
            return;
        }
        Session s = new Session(p);
        s.bedrock = Compat.isBedrock(p);
        s.prevInvul = p.isInvulnerable();
        s.prevCollidable = p.isCollidable();
        sessions.put(s.id, s);
        try {
            p.setInvulnerable(true);   // aman sejak detik pertama (loading, Nether, lava, dll.)
            if (cfg.timelapse) {
                p.setPlayerTime(cfg.timeStart, false);
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Gagal menyiapkan pemain " + p.getName(), t);
        }
        int d = delay + (s.bedrock ? cfg.bedrockExtra : 0);
        s.waitTask = Bukkit.getScheduler().runTaskLater(this, () -> goSpawn(s), Math.max(1, d));
    }

    /** Langkah 1: kirim pemain ke /spawn lalu tunggu teleport (bisa async) selesai. */
    private void goSpawn(Session s) {
        if (!valid(s)) {
            return;
        }
        Player p = s.player;
        try {
            Location pre = p.getLocation();
            boolean ok = false;
            if (cfg.spawnCommand != null && !cfg.spawnCommand.isBlank()) {
                try {
                    ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cfg.spawnCommand.replace("%player%", p.getName()));
                } catch (Throwable t) {
                    ok = false;
                }
            }
            if (!ok && cfg.fallbackSpawn) {
                p.teleport(p.getWorld().getSpawnLocation().add(0.5, 0, 0.5));
            }
            s.waitTask = new BukkitRunnable() {
                int waited = 0;

                @Override
                public void run() {
                    if (!valid(s)) {
                        cancel();
                        return;
                    }
                    waited++;
                    Location now = p.getLocation();
                    boolean moved = !now.getWorld().equals(pre.getWorld()) || now.distanceSquared(pre) > 1.0;
                    if ((moved && waited >= 3) || waited >= cfg.maxWait) {
                        cancel();
                        s.waitTask = Bukkit.getScheduler().runTaskLater(ZentraIntro.this, () -> start(s), Math.max(1, cfg.afterTp));
                    }
                }
            }.runTaskTimer(this, 1L, 1L);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Gagal /spawn untuk " + p.getName() + ", intro dibatalkan.", t);
            finish(s, false);
        }
    }

    /** Langkah 2: mulai kamera sinematik dari titik spawn. */
    private void start(Session s) {
        if (!valid(s)) {
            return;
        }
        Player p = s.player;
        try {
            if (p.isInsideVehicle()) {
                p.leaveVehicle();
            }
            Location loc = p.getLocation();
            Vector d = loc.getDirection().setY(0);
            if (d.lengthSquared() < 1.0E-6) {
                d = new Vector(0, 0, -1);
            }
            d.normalize();

            s.spawn = loc.clone();
            s.world = loc.getWorld();
            s.baseY = loc.getY();
            s.center = loc.clone().add(d.clone().multiply(cfg.orbit));
            s.th0 = Math.atan2(-d.getZ(), -d.getX());
            s.prevMode = p.getGameMode();
            s.prevAllow = p.getAllowFlight();
            s.prevFlying = p.isFlying();

            p.setGameMode(GameMode.ADVENTURE);
            p.setAllowFlight(true);
            p.setFlying(true);
            p.setCollidable(false);
            int total = cfg.main + OUTRO_FADE + 60;
            effect(p, eInvisible, total);
            effect(p, eBlind, 20);   // fade-in dari gelap di awal intro

            s.bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            p.showBossBar(s.bar);

            s.running = true;
            s.task = Bukkit.getScheduler().runTaskTimer(this, () -> tick(s), 1L, 1L);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Gagal memulai intro untuk " + p.getName(), t);
            finish(s, false);
        }
    }

    private void tick(Session s) {
        Player p = s.player;
        if (!p.isOnline()) {
            finish(s, false);
            return;
        }
        try {
            int tk = ++s.tick;
            int outro = cfg.fade ? OUTRO_FADE : OUTRO_PLAIN;
            if (tk <= cfg.main) {
                mainStep(s, tk);
            } else {
                outroStep(s, tk - cfg.main);
            }
            if (tk >= cfg.main + outro) {
                finish(s, true);
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Error saat intro, menghentikan intro untuk " + p.getName(), t);
            finish(s, false);
        }
    }

    // ------------------------------------------------------------------ bagian utama

    private void mainStep(Session s, int tk) {
        Player p = s.player;
        int main = cfg.main;

        if (shouldUpdate(s, tk)) {
            p.teleport(camLoc(s, tk));
        }
        double prog = Math.min(1.0, (double) tk / main);
        updateBar(s, prog);

        if (cfg.timelapse && tk % 2 == 0) {
            p.setPlayerTime((long) (cfg.timeStart + (cfg.timeEnd - cfg.timeStart) * prog), false);
        }

        int acc = 0;
        int idx = 0;
        for (int i = 0; i < cfg.steps.size(); i++) {
            acc += cfg.steps.get(i).ticks;
            idx = i;
            if (tk <= acc) {
                break;
            }
        }
        if (idx != s.step) {
            s.step = idx;
            Step st = cfg.steps.get(idx);
            showTitle(p, st.title, st.sub, 400, 2400, 500);
            sound(p, p.getLocation(), "block.note_block.chime", (float) Math.min(2.0, 1.0 + idx * 0.15));
        }

        ambient(s, tk);

        if (cfg.fireworks) {
            for (int k = 0; k < 8; k++) {
                int bt = burstTick(k);
                if (tk == bt - 16) {
                    rocket(p, burstPos(s, k), k, s.baseY + 1);
                }
            }
        }
        if (cfg.fade && cfg.darkness && tk == main - DARK_LEAD) {
            effect(p, eDark, DARK_LEAD + 40);   // meredup perlahan (efek darkness vanilla)
        }
    }

    // ------------------------------------------------------------------ penutup: gelap -> terang

    private void outroStep(Session s, int o) {
        Player p = s.player;

        if (o == 1) {
            if (cfg.timelapse) {
                p.resetPlayerTime();            // kembali ke waktu server
            }
            if (cfg.fade) {
                effect(p, eBlind, HOLD + FADE); // layar gelap total, lalu fade terang di 20 tick terakhir
            }
        }

        float sp = s.spawn.getPitch();
        float pitch = sp;
        if (cfg.fade) {
            pitch = o <= HOLD ? -90f : (float) (-90.0 + (sp + 90.0) * Math.min(1.0, (o - HOLD) / (double) FADE));
        }
        if (o <= 2 || shouldUpdate(s, o)) {
            p.teleport(new Location(s.world, s.spawn.getX(), s.spawn.getY(), s.spawn.getZ(), s.spawn.getYaw(), pitch));
        }

        if (o == 10) {
            if (s.bar != null) {
                p.hideBossBar(s.bar);
                s.bar = null;
            }
            showTitle(p, cfg.endTitle, cfg.endSub, 300, 2000, 700);
            sound(p, p.getLocation(), "entity.player.levelup", 1f);
        }
        int burstAt = cfg.fade ? 50 : 12;
        if (cfg.fireworks && o == burstAt) {
            Location top = s.center.clone().add(0, 22, 0);
            sound(p, top, "entity.firework_rocket.blast", 1f);
            List<Color> all = new ArrayList<>();
            for (Color[] pal : PALETTES) {
                all.addAll(Arrays.asList(pal));
            }
            burst(p, top, 90, 999L, all.toArray(new Color[0]));
        }
        if (o <= 12) {
            ambient(s, cfg.main + o);
        }
    }

    // ------------------------------------------------------------------ kamera

    private boolean shouldUpdate(Session s, int n) {
        return !s.bedrock || cfg.bedrockInterval <= 1 || n % cfg.bedrockInterval == 0;
    }

    /** Interpolasi Catmull-Rom antar keyframe (dinormalisasi ke durasi bagian utama). */
    private double[] cam(double tick) {
        int n = cfg.kt.length;
        double k = Math.max(0.0, Math.min(1.0, tick / cfg.main)) * cfg.kt[n - 1];
        int i = 0;
        for (int j = 0; j < n - 1; j++) {
            if (k >= cfg.kt[j]) {
                i = j;
            }
        }
        double t = (k - cfg.kt[i]) / (cfg.kt[i + 1] - cfg.kt[i]);
        double[] p0 = cfg.kv[Math.max(i - 1, 0)];
        double[] p1 = cfg.kv[i];
        double[] p2 = cfg.kv[i + 1];
        double[] p3 = cfg.kv[Math.min(i + 2, n - 1)];
        double[] o = new double[4];
        for (int c = 0; c < 4; c++) {
            o[c] = 0.5 * ((2 * p1[c]) + (-p0[c] + p2[c]) * t
                    + (2 * p0[c] - 5 * p1[c] + 4 * p2[c] - p3[c]) * t * t
                    + (-p0[c] + 3 * p1[c] - 3 * p2[c] + p3[c]) * t * t * t);
        }
        return o;
    }

    private Location camLoc(Session s, int tick) {
        double[] c = cam(tick);
        double th = s.th0 + Math.toRadians(c[1] - 90);
        double px = s.center.getX() + c[0] * Math.cos(th);
        double pz = s.center.getZ() + c[0] * Math.sin(th);
        double eyeY = s.baseY + c[2];
        double dx = s.center.getX() - px;
        double dz = s.center.getZ() - pz;
        double dy = (s.baseY + c[3]) - eyeY;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        pitch = Math.max(-90f, Math.min(90f, pitch));
        return new Location(s.world, px, eyeY - 1.62, pz, yaw, pitch);
    }

    // ------------------------------------------------------------------ efek visual

    private void ambient(Session s, int tk) {
        if (!cfg.fantasy) {
            return;
        }
        Player p = s.player;
        // kolom cahaya berputar di tengah lobby
        for (int i = 0; i < 3; i++) {
            double y = s.baseY + 2 + ((tk * 0.15 + i * 5.3) % 16);
            double a = i * 2.399 + tk * 0.06;
            double r = 0.8 + 0.25 * Math.sin(i + tk * 0.1);
            puff(p, pEndRod, s.center.getX() + r * Math.cos(a), y, s.center.getZ() + r * Math.sin(a), 0, 0.0, 0.1, 0.0, 1.0);
        }
        // kunang-kunang / debu sihir di sekitar kamera
        Location cl = p.getLocation();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 5; i++) {
            double x = cl.getX() + rnd.nextDouble(-10, 10);
            double y = cl.getY() + 1.6 + rnd.nextDouble(-4, 6);
            double z = cl.getZ() + rnd.nextDouble(-10, 10);
            puff(p, i % 2 == 0 ? pEndRod : pWitch, x, y, z, 1, 0.02, 0.05, 0.02, 0.01);
        }
    }

    private int burstTick(int k) {
        return (int) Math.round(cfg.main * (100 + 40 * k) / 390.0);
    }

    private Location burstPos(Session s, int k) {
        double a = Math.toRadians(20 + 72 * k);
        return s.center.clone().add(10 * Math.cos(a), 10 + (k % 3) * 1.5, 10 * Math.sin(a));
    }

    private void rocket(Player p, Location top, int k, double groundY) {
        new BukkitRunnable() {
            int age = 0;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    cancel();
                    return;
                }
                double f = age / 16.0;
                puff(p, pFirework, top.getX(), groundY + (top.getY() - groundY) * f, top.getZ(), 2, 0.0, 0.0, 0.0, 0.0);
                if (++age >= 16) {
                    cancel();
                    sound(p, top, "entity.firework_rocket.blast", 1f);
                    burst(p, top, 40, 100L + k, PALETTES[k % PALETTES.length]);
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void burst(Player p, Location c, int n, long seed, Color[] pal) {
        if (pDust == null) {
            return;
        }
        Random r = new Random(seed);
        double[][] dir = new double[n][3];
        double[] sp = new double[n];
        for (int i = 0; i < n; i++) {
            double x = r.nextGaussian();
            double y = r.nextGaussian();
            double z = r.nextGaussian();
            double m = Math.sqrt(x * x + y * y + z * z) + 1.0E-9;
            dir[i] = new double[]{x / m, y / m, z / m};
            sp[i] = (0.55 + 0.45 * r.nextDouble()) * 7;
        }
        new BukkitRunnable() {
            int age = 0;

            @Override
            public void run() {
                if (!p.isOnline() || age > 30) {
                    cancel();
                    return;
                }
                double a = age / 20.0;
                double e = (1 - Math.exp(-2.2 * a)) / 2.2;
                for (int i = 0; i < n; i++) {
                    Location l = c.clone().add(dir[i][0] * sp[i] * e, dir[i][1] * sp[i] * e - 2.5 * a * a, dir[i][2] * sp[i] * e);
                    p.spawnParticle(pDust, l, 1, 0, 0, 0, 0, new Particle.DustOptions(pal[i % pal.length], 1.6f));
                }
                age++;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void puff(Player p, Particle type, double x, double y, double z, int count, double ox, double oy, double oz, double extra) {
        if (type != null) {
            p.spawnParticle(type, x, y, z, count, ox, oy, oz, extra);
        }
    }

    // ------------------------------------------------------------------ util

    private void effect(Player p, PotionEffectType type, int duration) {
        if (type != null) {
            p.addPotionEffect(new PotionEffect(type, duration, 0, false, false, false));
        }
    }

    private void removeEffect(Player p, PotionEffectType type) {
        if (type != null) {
            p.removePotionEffect(type);
        }
    }

    private void sound(Player p, Location l, String key, float pitch) {
        if (cfg.sounds) {
            p.playSound(l, key, 1f, pitch);
        }
    }

    private String fill(String text, Player p) {
        return text.replace("%player%", p.getName()).replace("%server%", cfg.serverName);
    }

    private void showTitle(Player p, String title, String sub, int fadeIn, int stay, int fadeOut) {
        p.showTitle(Title.title(MM.deserialize(fill(title, p)), MM.deserialize(fill(sub, p)),
                Title.Times.times(Duration.ofMillis(fadeIn), Duration.ofMillis(stay), Duration.ofMillis(fadeOut))));
    }

    private void updateBar(Session s, double prog) {
        if (s.bar == null) {
            return;
        }
        s.bar.progress((float) Math.max(0.0, Math.min(1.0, prog)));
        s.bar.name(MM.deserialize(fill(cfg.barText, s.player).replace("%percent%", String.valueOf((int) (prog * 100)))));
    }

    /** Mengembalikan semua state pemain seperti semula. Aman dipanggil berulang. */
    private void finish(Session s, boolean normal) {
        sessions.remove(s.id, s);
        s.running = false;
        if (s.task != null) {
            s.task.cancel();
        }
        if (s.waitTask != null) {
            s.waitTask.cancel();
        }
        Player p = s.player;
        try {
            if (s.bar != null) {
                p.hideBossBar(s.bar);
                s.bar = null;
            }
            removeEffect(p, eInvisible);
            removeEffect(p, eBlind);
            removeEffect(p, eDark);
            p.resetPlayerTime();
            p.setCollidable(s.prevCollidable);
            if (s.prevMode != null) {
                if (!normal && s.spawn != null && p.isOnline()) {
                    p.teleport(s.spawn);   // keluar/dibatalkan di tengah intro: kembali ke spawn
                }
                p.setGameMode(s.prevMode);
                p.setAllowFlight(s.prevAllow);
                p.setFlying(s.prevAllow && s.prevFlying);
            }
            p.setInvulnerable(s.prevInvul);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Gagal memulihkan state pemain " + p.getName(), t);
        }
    }

    // ------------------------------------------------------------------ event

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!cfg.enabled) {
            return;
        }
        if (cfg.bypassPerm != null && !cfg.bypassPerm.isEmpty() && p.hasPermission(cfg.bypassPerm)) {
            return;
        }
        if (cfg.firstJoinOnly && p.hasPlayedBefore()) {
            return;
        }
        launch(p, cfg.joinDelay);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) {
            finish(s, false);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null && s.running) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (sessions.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (sessions.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        if (!cfg.blockCommands || !sessions.containsKey(e.getPlayer().getUniqueId())) {
            return;
        }
        String m = e.getMessage().toLowerCase(Locale.ROOT);
        if (m.startsWith("/zentraintro") || m.startsWith("/zintro")) {
            return;
        }
        e.setCancelled(true);
        e.getPlayer().sendMessage(MM.deserialize("<red>Tunggu intro selesai dulu ya."));
    }

    // ------------------------------------------------------------------ command

    private void msg(CommandSender to, String text) {
        to.sendMessage(MM.deserialize(text));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("zentraintro.admin")) {
            msg(sender, "<red>Kamu tidak punya izin.");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                cfg = load();
                msg(sender, "<green>Config ZentraIntro dimuat ulang.");
            }
            case "play" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player self ? self : null);
                if (target == null) {
                    msg(sender, "<red>Pemain tidak ditemukan. Pakai: /zentraintro play <pemain>");
                } else if (sessions.containsKey(target.getUniqueId())) {
                    msg(sender, "<yellow>Intro sedang berjalan untuk " + target.getName() + ".");
                } else {
                    launch(target, 5);
                    msg(sender, "<green>Intro dimulai untuk " + target.getName() + ".");
                }
            }
            case "stop" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player self ? self : null);
                Session s = target == null ? null : sessions.get(target.getUniqueId());
                if (s == null) {
                    msg(sender, "<yellow>Tidak ada intro yang berjalan.");
                } else {
                    finish(s, false);
                    msg(sender, "<green>Intro dihentikan untuk " + target.getName() + ".");
                }
            }
            default -> msg(sender, "<aqua>/zentraintro <play|stop|reload> [pemain]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String o : new String[]{"play", "stop", "reload"}) {
                if (o.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(o);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("play") || args[0].equalsIgnoreCase("stop"))) {
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(pl.getName());
                }
            }
        }
        return out;
    }
}
