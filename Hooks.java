package dev.fakecrowd;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.TabCompleteEvent;

import java.util.*;

final class Hooks implements Listener {
    private static final Set<String> WHISPER = Set.of("msg", "tell", "w", "whisper", "pm", "m", "t");

    private final FakeCrowd plugin;
    private final FakeManager mgr;
    private final ChatBrain brain;
    private final Map<UUID, FakeManager.Active> lastWhisper = new HashMap<>();

    Hooks(FakeCrowd plugin, FakeManager mgr, ChatBrain brain) {
        this.plugin = plugin; this.mgr = mgr; this.brain = brain;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            mgr.sendAllTo(p);
            brain.onRealJoin(p.getName());
        }, 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        String name = e.getPlayer().getName();
        Bukkit.getScheduler().runTask(plugin, () -> brain.onChat(name, text));
    }

    /** Ніки фейків у автодоповненні команд (/tpa, /msg і т.д. - ті, що доповнює сервер). */
    @EventHandler
    public void onTab(TabCompleteEvent e) {
        if (mgr.all().isEmpty()) return;
        String buf = e.getBuffer();
        if (!buf.startsWith("/")) return;
        String[] parts = buf.substring(1).split(" ", -1);
        if (parts.length != 2) return; // доповнюємо лише перший аргумент
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        if (cmd.startsWith("minecraft:")) cmd = cmd.substring(10);
        List<String> allowed = plugin.getConfig().getStringList("tab-complete.commands");
        if (!allowed.contains(cmd)) return;
        String partial = parts[1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(e.getCompletions());
        for (FakeManager.Active a : mgr.all()) {
            if (a.p.name.toLowerCase(Locale.ROOT).startsWith(partial) && !out.contains(a.p.name)) out.add(a.p.name);
        }
        e.setCompletions(out);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String raw = e.getMessage().substring(1).trim();
        String[] p = raw.split("\\s+", 3);
        String cmd = p[0].toLowerCase(Locale.ROOT);
        if (cmd.startsWith("minecraft:")) cmd = cmd.substring(10);
        Player sender = e.getPlayer();

        // /list з фейками
        if (cmd.equals("list") && p.length == 1
                && plugin.getConfig().getBoolean("fake-players.override-list-command", true)) {
            e.setCancelled(true);
            List<String> names = new ArrayList<>();
            for (Player pl : Bukkit.getOnlinePlayers()) names.add(pl.getName());
            for (FakeManager.Active a : mgr.all()) names.add(a.p.name);
            sender.sendMessage(Component.translatable("commands.list.players",
                    Component.text(names.size()),
                    Component.text(Bukkit.getMaxPlayers()),
                    Component.text(String.join(", ", names))));
            return;
        }

        // /r <текст> - відповідь останньому фейку, якому писав гравець
        if ((cmd.equals("r") || cmd.equals("reply")) && p.length >= 2) {
            FakeManager.Active last = lastWhisper.get(sender.getUniqueId());
            if (last != null && mgr.isActive(last)) {
                String text = raw.substring(raw.indexOf(' ') + 1);
                e.setCancelled(true);
                sender.sendMessage(Component.translatable("commands.message.display.outgoing",
                        Component.text(last.p.name), Component.text(text))
                        .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC));
                brain.onWhisper(sender, last, text);
            }
            return;
        }

        // /msg <фейк> текст
        if (WHISPER.contains(cmd) && p.length >= 3) {
            FakeManager.Active fake = mgr.byName(p[1]);
            if (fake != null) {
                lastWhisper.put(sender.getUniqueId(), fake);
                e.setCancelled(true);
                sender.sendMessage(Component.translatable("commands.message.display.outgoing",
                        Component.text(fake.p.name), Component.text(p[2]))
                        .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC));
                brain.onWhisper(sender, fake, p[2]);
            }
            return;
        }

        // /tpa <фейк> - робимо вигляд, що запит надіслано (лише якщо така команда є на сервері)
        List<String> tpa = plugin.getConfig().getStringList("fake-commands.tpa-commands");
        if (tpa.contains(cmd) && p.length >= 2 && Bukkit.getCommandMap().getCommand(cmd) != null) {
            FakeManager.Active fake = mgr.byName(p[1]);
            if (fake != null) {
                e.setCancelled(true);
                LegacyComponentSerializer ser = LegacyComponentSerializer.legacyAmpersand();
                sender.sendMessage(ser.deserialize(plugin.getConfig()
                        .getString("fake-commands.tpa-sent", "&6Request sent to &c%name%&6.")
                        .replace("%name%", fake.p.name)));
                String deny = plugin.getConfig().getString("fake-commands.tpa-deny", "");
                if (!deny.isBlank() && new Random().nextDouble() < 0.5) {
                    long delay = (6 + new Random().nextInt(10)) * 20L;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (sender.isOnline() && mgr.isActive(fake)) {
                            sender.sendMessage(ser.deserialize(deny.replace("%name%", fake.p.name)));
                        }
                    }, delay);
                }
            }
        }
    }
}
