package dev.fakecrowd;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Керує списком фейкових гравців, пакетами табу та "плаваючим" онлайном. */
final class FakeManager {

    static final class Persona {
        final String name, lang, style, skinValue, skinSig;
        final UUID uuid;
        Persona(String name, String lang, String style, String skinValue, String skinSig) {
            this.name = name; this.lang = lang; this.style = style;
            this.skinValue = skinValue; this.skinSig = skinSig;
            // Той самий UUID, який дає offline-mode сервер реальному гравцю з таким ніком
            this.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        }
    }

    static final class Active {
        final Persona p;
        final long leaveAt;
        volatile int latency;
        volatile long lastSpoke;
        Active(Persona p, long leaveAt, int latency) { this.p = p; this.leaveAt = leaveAt; this.latency = latency; }
    }

    private final FakeCrowd plugin;
    private final ProtocolManager pm = ProtocolLibrary.getProtocolManager();
    private final Random rnd = new Random();
    private final Map<String, Persona> pool = new LinkedHashMap<>();
    private final List<Active> active = new CopyOnWriteArrayList<>();
    private BukkitTask task;
    private double phantom = 0;
    private long nextWalk, nextEvent, nextLatency;
    private boolean warned;

    FakeManager(FakeCrowd plugin) { this.plugin = plugin; }

    void start() {
        reload();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    void reload() {
        FileConfiguration c = plugin.getConfig();
        pool.clear();
        ConfigurationSection s = c.getConfigurationSection("personas");
        if (s != null) {
            for (String key : s.getKeys(false)) {
                ConfigurationSection p = s.getConfigurationSection(key);
                if (p == null) continue;
                pool.put(key, new Persona(key, p.getString("lang", "uk"), p.getString("style", "casual"),
                        p.getString("skin-value", ""), p.getString("skin-signature", "")));
            }
        }
        for (Active a : new ArrayList<>(active)) {
            if (!pool.containsKey(a.p.name)) remove(a, false);
        }
        double base = c.getDouble("online.target", 15);
        double var = c.getDouble("online.variation", 4);
        if (phantom == 0) phantom = base;
        phantom = Math.max(base - var, Math.min(base + var, phantom));

        int min = c.getInt("fake-players.count-min", 2);
        while (active.size() < min && addRandom(false)) { /* тихо заповнюємо */ }
    }

    void shutdown() {
        if (task != null) task.cancel();
        for (Active a : new ArrayList<>(active)) remove(a, false);
    }

    // ---------- щосекундний тік ----------
    private void tick() {
        long now = System.currentTimeMillis();
        FileConfiguration c = plugin.getConfig();

        if (now >= nextWalk) {
            walk(c);
            long lo = c.getInt("online.update-seconds-min", 40), hi = c.getInt("online.update-seconds-max", 150);
            nextWalk = now + (lo + rnd.nextInt((int) Math.max(1, hi - lo + 1))) * 1000L;
        }
        if (now >= nextEvent) {
            crowdEvent(now, c);
            nextEvent = now + (15 + rnd.nextInt(46)) * 1000L;
        }
        if (now >= nextLatency) {
            refreshLatency(c);
            nextLatency = now + 30_000L;
        }
    }

    /** Випадкове блукання навколо target з поверненням до середнього. */
    private void walk(FileConfiguration c) {
        double base = c.getDouble("online.target", 15);
        double var = c.getDouble("online.variation", 4);
        double drift = (base - phantom) * 0.25;
        double step = rnd.nextGaussian() * 1.2 + drift;
        phantom = Math.max(base - var, Math.min(base + var, phantom + step));
    }

    private void crowdEvent(long now, FileConfiguration c) {
        int min = c.getInt("fake-players.count-min", 2);
        int max = c.getInt("fake-players.count-max", 3);
        for (Active a : active) {
            if (now >= a.leaveAt) { remove(a, true); return; }
        }
        if (active.size() < min || (active.size() < max && rnd.nextDouble() < 0.35)) {
            addRandom(true);
        }
    }

    // ---------- додавання / видалення ----------
    boolean addRandom(boolean announce) {
        FileConfiguration c = plugin.getConfig();
        List<Persona> free = new ArrayList<>();
        for (Persona p : pool.values()) {
            boolean used = active.stream().anyMatch(a -> a.p == p);
            if (!used && Bukkit.getPlayerExact(p.name) == null) free.add(p);
        }
        if (free.isEmpty()) return false;
        Persona p = free.get(rnd.nextInt(free.size()));

        int minS = c.getInt("fake-players.min-session-minutes", 15);
        int maxS = Math.max(minS, c.getInt("fake-players.max-session-minutes", 80));
        long leave = System.currentTimeMillis() + (minS + rnd.nextInt(maxS - minS + 1)) * 60_000L;
        Active a = new Active(p, leave, randLatency(c));
        active.add(a);

        for (Player pl : Bukkit.getOnlinePlayers()) sendAdd(pl, a);
        if (announce && c.getBoolean("fake-players.announce-join-leave", true)) {
            Bukkit.broadcast(Component.translatable("multiplayer.player.joined", Component.text(p.name))
                    .color(NamedTextColor.YELLOW));
        }
        return true;
    }

    void remove(Active a, boolean announce) {
        if (!active.remove(a)) return;
        for (Player pl : Bukkit.getOnlinePlayers()) sendRemove(pl, a);
        if (announce && plugin.getConfig().getBoolean("fake-players.announce-join-leave", true)) {
            Bukkit.broadcast(Component.translatable("multiplayer.player.left", Component.text(a.p.name))
                    .color(NamedTextColor.YELLOW));
        }
    }

    private int randLatency(FileConfiguration c) {
        int lo = c.getInt("fake-players.ping-min", 25), hi = Math.max(lo, c.getInt("fake-players.ping-max", 95));
        return lo + rnd.nextInt(hi - lo + 1);
    }

    private void refreshLatency(FileConfiguration c) {
        for (Active a : active) {
            int lat = randLatency(c);
            a.latency = lat;
            for (Player pl : Bukkit.getOnlinePlayers()) {
                send(pl, EnumSet.of(EnumWrappers.PlayerInfoAction.UPDATE_LATENCY), a);
            }
        }
    }

    // ---------- пакети ----------
    void sendAllTo(Player pl) {
        for (Active a : active) sendAdd(pl, a);
    }

    private void sendAdd(Player target, Active a) {
        send(target, EnumSet.of(
                EnumWrappers.PlayerInfoAction.ADD_PLAYER,
                EnumWrappers.PlayerInfoAction.UPDATE_LISTED,
                EnumWrappers.PlayerInfoAction.UPDATE_LATENCY,
                EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE), a);
    }

    private void send(Player target, EnumSet<EnumWrappers.PlayerInfoAction> actions, Active a) {
        try {
            if (!target.isOnline()) return;
            PacketContainer packet = pm.createPacket(PacketType.Play.Server.PLAYER_INFO);
            packet.getPlayerInfoActions().write(0, actions);
            WrappedGameProfile profile = new WrappedGameProfile(a.p.uuid, a.p.name);
            if (!a.p.skinValue.isEmpty()) {
                profile.getProperties().put("textures",
                        new WrappedSignedProperty("textures", a.p.skinValue, a.p.skinSig));
            }
            PlayerInfoData data = new PlayerInfoData(profile, a.latency,
                    EnumWrappers.NativeGameMode.SURVIVAL, WrappedChatComponent.fromText(a.p.name));
            packet.getPlayerInfoDataLists().write(1, List.of(data));
            pm.sendServerPacket(target, packet);
        } catch (Exception ex) {
            if (!warned) { warned = true; plugin.getLogger().warning("Tab packet failed: " + ex); }
        }
    }

    private void sendRemove(Player target, Active a) {
        try {
            if (!target.isOnline()) return;
            PacketContainer packet = pm.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
            packet.getUUIDLists().write(0, List.of(a.p.uuid));
            pm.sendServerPacket(target, packet);
        } catch (Exception ex) {
            if (!warned) { warned = true; plugin.getLogger().warning("Tab remove packet failed: " + ex); }
        }
    }

    // ---------- доступ ----------
    List<Active> all() { return active; }
    boolean isActive(Active a) { return active.contains(a); }

    Active byName(String name) {
        for (Active a : active) if (a.p.name.equalsIgnoreCase(name)) return a;
        return null;
    }

    boolean pingEnabled() { return plugin.getConfig().getBoolean("online.enabled", true); }

    /** Число для списку серверів: плаваюче, але не менше за реальних + фейків у табі. */
    int displayedOnline(int real) {
        return Math.max((int) Math.round(phantom), real + active.size());
    }
}
