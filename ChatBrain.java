package dev.fakecrowd;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.regex.Pattern;

/** Вирішує, коли і що відповідають фейки; робить відповіді схожими на людські. */
final class ChatBrain {

    private static final Pattern GREET = Pattern.compile(
            "(?iu)^(прив\\S*|хай|здоров\\S*|віта\\S*|ку|hi+|hello|hey|yo|добр\\S*)(\\s.*)?$");

    private final FakeCrowd plugin;
    private final FakeManager mgr;
    private final MiniBrain mini;
    private final Random rnd = new Random();
    private long lastFakeMsg, lastAnyChat;

    ChatBrain(FakeCrowd plugin, FakeManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.mini = new MiniBrain(plugin, mgr);
    }

    void start() {
        // автозбереження вивченого раз на 5 хв (асинхронно)
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, mini::save, 6000L, 6000L);
    }

    void reload() { mini.reload(); }
    void shutdown() { mini.save(); }

    // ---------- події ----------
    void onChat(String sender, String msg) {
        long now = System.currentTimeMillis();
        lastAnyChat = now;
        mini.learn(sender, msg);
        List<FakeManager.Active> act = mgr.all();
        if (act.isEmpty()) return;
        FileConfiguration c = plugin.getConfig();

        String lower = msg.toLowerCase(Locale.ROOT);
        FakeManager.Active addressed = null;
        for (FakeManager.Active a : act) {
            if (lower.contains(a.p.name.toLowerCase(Locale.ROOT))) { addressed = a; break; }
        }

        double chance = addressed != null ? c.getDouble("chat.mention-reply-chance", 0.9)
                                          : c.getDouble("chat.reply-chance", 0.3);
        if (rnd.nextDouble() > chance) return;

        long global = c.getLong("chat.global-cooldown-seconds", 25) * 1000L;
        long per = c.getLong("chat.per-fake-cooldown-seconds", 60) * 1000L;
        if (addressed == null && now - lastFakeMsg < global) return;
        if (addressed != null && now - lastFakeMsg < 4000L) return;

        FakeManager.Active speaker = addressed;
        if (speaker == null) {
            List<FakeManager.Active> ok = new ArrayList<>();
            for (FakeManager.Active a : act) if (now - a.lastSpoke >= per) ok.add(a);
            if (ok.isEmpty()) return;
            speaker = ok.get(rnd.nextInt(ok.size()));
        }
        speaker.lastSpoke = now;
        lastFakeMsg = now;
        respond(speaker, msg, null, addressed != null);
    }

    void onWhisper(Player from, FakeManager.Active fake, String msg) {
        if (rnd.nextDouble() > plugin.getConfig().getDouble("chat.private-reply-chance", 0.8)) return;
        respond(fake, msg, from, true);
    }

    void onRealJoin(String name) {
        List<FakeManager.Active> act = mgr.all();
        if (act.isEmpty()) return;
        if (rnd.nextDouble() > plugin.getConfig().getDouble("chat.join-greet-chance", 0.35)) return;
        FakeManager.Active sp = act.get(rnd.nextInt(act.size()));
        String text = pick(sp.p.lang, "greetings");
        if (text == null) return;
        if (rnd.nextDouble() < 0.4) text = text + " " + name;
        final String t = text;
        later(4000 + rnd.nextInt(8000), () -> deliver(sp, t, null));
    }

    void idleTick() {
        FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("chat.idle-chat.enabled", true)) return;
        if (Bukkit.getOnlinePlayers().isEmpty() || mgr.all().isEmpty()) return;
        long now = System.currentTimeMillis();
        long quiet = c.getLong("chat.idle-chat.min-silence-seconds", 180) * 1000L;
        if (now - Math.max(lastFakeMsg, lastAnyChat) < quiet) return;
        if (rnd.nextDouble() > c.getDouble("chat.idle-chat.chance", 0.15)) return;
        FakeManager.Active sp = mgr.all().get(rnd.nextInt(mgr.all().size()));
        String text = pick(sp.p.lang, "idle");
        if (text == null) return;
        lastFakeMsg = now;
        later(text.length() * (110L + rnd.nextInt(110)), () -> deliver(sp, text, null));
    }

    // ---------- відповідь ----------
    private void respond(FakeManager.Active sp, String msg, Player pmTo, boolean direct) {
        later(1200 + rnd.nextInt(2500), () -> {   // "читає" повідомлення
            if (!mgr.isActive(sp)) return;
            String text = clean(mini.reply(sp.p.lang, msg));
            if (text == null) {
                double gen = plugin.getConfig().getDouble("brain.generic-reply-chance", 0.4);
                if (!direct && rnd.nextDouble() > gen) return;
                text = fallback(sp.p, msg);
            }
            if (text == null) return;
            final String t = text;
            later((long) t.length() * (110 + rnd.nextInt(110)), () -> deliver(sp, t, pmTo)); // "друкує"
        });
    }

    private void deliver(FakeManager.Active sp, String text, Player pmTo) {
        if (!mgr.isActive(sp)) return;
        sp.lastSpoke = System.currentTimeMillis();
        lastFakeMsg = sp.lastSpoke;
        if (pmTo == null) {
            Bukkit.broadcast(chatLine(sp.p.name, text));
        } else if (pmTo.isOnline()) {
            pmTo.sendMessage(Component.translatable("commands.message.display.incoming",
                    Component.text(sp.p.name), Component.text(text))
                    .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC));
        }
    }

    private Component chatLine(String name, String msg) {
        String fmt = plugin.getConfig().getString("chat.format", "<%name%> %msg%").replace("%name%", name);
        int i = fmt.indexOf("%msg%");
        LegacyComponentSerializer ser = LegacyComponentSerializer.legacyAmpersand();
        if (i < 0) return ser.deserialize(fmt).append(Component.text(" " + msg));
        return ser.deserialize(fmt.substring(0, i)).append(Component.text(msg))
                .append(ser.deserialize(fmt.substring(i + 5)));
    }

    // ---------- постобробка (щоб не палилось) ----------
    private String clean(String raw) {
        if (raw == null) return null;
        String s = raw.strip().replace("&", "").replace("§", "");
        if (s.isEmpty()) return null;
        if (s.length() > 90) { int i = s.lastIndexOf(' ', 90); s = s.substring(0, i > 20 ? i : 90); }
        if (rnd.nextDouble() < 0.7) s = Character.toLowerCase(s.charAt(0)) + s.substring(1);
        if (s.endsWith(".") && rnd.nextDouble() < 0.85) s = s.substring(0, s.length() - 1);
        if (s.length() > 5 && rnd.nextDouble() < 0.05) { // рідкісна друкарська помилка
            int i = 1 + rnd.nextInt(s.length() - 2);
            char[] ch = s.toCharArray();
            char t = ch[i]; ch[i] = ch[i + 1]; ch[i + 1] = t;
            s = new String(ch);
        }
        return s;
    }

    private String fallback(FakeManager.Persona p, String msg) {
        String m = msg.strip();
        if (GREET.matcher(m).matches()) return pick(p.lang, "greetings");
        if (m.endsWith("?")) return pick(p.lang, "unsure");
        return pick(p.lang, "generic");
    }

    private String pick(String lang, String bucket) {
        List<String> l = plugin.getConfig().getStringList("phrases." + lang + "." + bucket);
        if (l.isEmpty()) l = plugin.getConfig().getStringList("phrases.en." + bucket);
        return l.isEmpty() ? null : l.get(rnd.nextInt(l.size()));
    }

    private void later(long ms, Runnable r) {
        Bukkit.getScheduler().runTaskLater(plugin, r, Math.max(1L, ms / 50L));
    }
}
