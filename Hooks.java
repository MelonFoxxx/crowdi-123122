package dev.fakecrowd;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class Hooks implements Listener {
    private static final Set<String> WHISPER = Set.of("msg", "tell", "w", "whisper", "pm", "m", "t");

    private final FakeCrowd plugin;
    private final FakeManager mgr;
    private final ChatBrain brain;

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

        // /msg <фейк> текст
        if (WHISPER.contains(cmd) && p.length >= 3) {
            FakeManager.Active fake = mgr.byName(p[1]);
            if (fake != null) {
                e.setCancelled(true);
                sender.sendMessage(Component.translatable("commands.message.display.outgoing",
                        Component.text(fake.p.name), Component.text(p[2]))
                        .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC));
                brain.onWhisper(sender, fake, p[2]);
            }
        }
    }
}
