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

/**
 * Керує поведінкою фейків у чаті.
 * Відповідь: хмарна нейромережа (якщо ввімкнена) -> вбудований MiniBrain -> загальні фрази.
 * Є "сесії розмови": якщо фейк почав говорити з гравцем, він продовжує відповідати йому далі.
 */
final class ChatBrain {

    private static final Pattern GREET = Pattern.compile(
            "(?iu)^(прив\\S*|хай|здоров\\S*|віта\\S*|ку|hi+|hello|hey|yo|добр\\S*)(\\s.*)?$");
    private static final List<String> BAD = List.of("language model", "as an ai", "i'm an ai", "i am an ai",
            "штучн", "нейромереж", "я бот", "я - бот", "assistant", "ассистент", "асистент", "openai", "chatgpt");

    private static final class Session {
        final FakeManager.Active fake;
        long lastAt;
        int turns;
        long pendingUntil;
        Session(FakeManager.Active fake) { this.fake = fake; }
    }

    private final FakeCrowd plugin;
    private final FakeManager mgr;
    private final MiniBrain mini;
    private final AiClient ai;
    private final Random rnd = new Random();
    private final Map<String, Session> sessions = new HashMap<>();
    private final Deque<String> history = new ArrayDeque<>();
    private long lastFakeMsg, lastAnyChat;

    ChatBrain(FakeCrowd plugin, FakeManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.mini = new MiniBrain(plugin, mgr);
        this.ai = new AiClient(plugin);
    }

    void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, mini::save, 6000L, 6000L);
    }
    void reload() { mini.reload(); }
    String stats() { return mini.stats(); }
    void shutdown() { mini.save(); }

    // ---------- події ----------
    void onChat(String sender, String msg) {
        long now = System.currentTimeMillis();
        lastAnyChat = now;
        mini.learn(sender, msg);
        remember(sender + ": " + msg);
        List<FakeManager.Active> act = mgr.all();
        if (act.isEmpty()) { sessions.clear(); return; }
        FileConfiguration c = plugin.getConfig();

        String key = sender.toLowerCase(Locale.ROOT);
        Session s = sessions.get(key);
        long sessMs = c.getLong("chat.session-seconds", 150) * 1000L;
        if (s != null && (!mgr.isActive(s.fake) || now - s.lastAt > sessMs
                || s.turns >= c.getInt("chat.session-max-turns", 12))) {
            sessions.remove(key);
            s = null;
        }

        String lower = msg.toLowerCase(Locale.ROOT);
        FakeManager.Active addressed = null;
        for (FakeManager.Active a : act) {
            if (isMentioned(lower, a)) { addressed = a; break; }
        }

        FakeManager.Active speaker;
        boolean direct = false;
        if (addressed != null) {
            if (rnd.nextDouble() > c.getDouble("chat.mention-reply-chance", 0.9)) return;
            speaker = addressed; direct = true;
        } else if (s != null) {
            // продовження розмови: гравець пише далі після відповіді фейка
            if (mentionsOtherPlayer(lower, sender)) return;
            if (rnd.nextDouble() > c.getDouble("chat.session-reply-chance", 0.95)) return;
            speaker = s.fake; direct = true;
        } else {
            if (rnd.nextDouble() > c.getDouble("chat.reply-chance", 0.3)) return;
            long global = c.getLong("chat.global-cooldown-seconds", 25) * 1000L;
            long per = c.getLong("chat.per-fake-cooldown-seconds", 60) * 1000L;
            if (now - lastFakeMsg < global) return;
            List<FakeManager.Active> ok = new ArrayList<>();
            for (FakeManager.Active a : act) if (now - a.lastSpoke >= per) ok.add(a);
            if (ok.isEmpty()) return;
            speaker = ok.get(rnd.nextInt(ok.size()));
        }

        if (s != null && s.pendingUntil > now) return;            // вже друкує відповідь цьому гравцю
        if (now - speaker.lastSpoke < 2500L && direct) return;

        Session ns = (s != null && s.fake == speaker) ? s : new Session(speaker);
        ns.lastAt = now;
        ns.pendingUntil = now + 30_000L;
        sessions.put(key, ns);
        speaker.lastSpoke = now;
        lastFakeMsg = now;
        respond(speaker, ns, sender, msg, null, direct);
    }

    void onWhisper(Player from, FakeManager.Active fake, String msg) {
        if (rnd.nextDouble() > plugin.getConfig().getDouble("chat.private-reply-chance", 0.8)) return;
        respond(fake, null, from.getName(), msg, from, true);
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
        later(4000 + rnd.nextInt(8000), () -> deliver(sp, null, t, null));
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
        later(text.length() * (110L + rnd.nextInt(110)), () -> deliver(sp, null, text, null));
    }

    // ---------- відповідь ----------
    private void respond(FakeManager.Active sp, Session ns, String sender, String msg, Player pmTo, boolean direct) {
        later(1000 + rnd.nextInt(2000), () -> {   // "читає" повідомлення
            if (!mgr.isActive(sp)) { release(ns); return; }
            ai.ask(system(sp.p), userPrompt(sp.p, sender, msg, pmTo != null))
              .thenAccept(raw -> Bukkit.getScheduler().runTask(plugin, () -> {
                  if (!mgr.isActive(sp)) { release(ns); return; }
                  String text = clean(raw);                                   // 1) нейромережа
                  if (text == null) text = clean(mini.reply(sp.p.lang, msg, sender)); // 2) вбудований ШІ
                  if (text == null) {                                         // 3) загальні фрази
                      double gen = plugin.getConfig().getDouble("brain.generic-reply-chance", 0.4);
                      if (!direct && rnd.nextDouble() > gen) { release(ns); return; }
                      text = fallback(sp.p, msg);
                  }
                  if (text == null) { release(ns); return; }
                  final String t = text;
                  later((long) t.length() * (110 + rnd.nextInt(110)), () -> deliver(sp, ns, t, pmTo)); // "друкує"
              }));
        });
    }

    private void release(Session ns) { if (ns != null) ns.pendingUntil = 0; }

    private void deliver(FakeManager.Active sp, Session ns, String text, Player pmTo) {
        if (ns != null) { ns.pendingUntil = 0; ns.lastAt = System.currentTimeMillis(); ns.turns++; }
        if (!mgr.isActive(sp)) return;
        sp.lastSpoke = System.currentTimeMillis();
        lastFakeMsg = sp.lastSpoke;
        if (pmTo == null) {
            Bukkit.broadcast(chatLine(sp.p.name, text));
            remember(sp.p.name + ": " + text);
        } else if (pmTo.isOnline()) {
            pmTo.sendMessage(Component.translatable("commands.message.display.incoming",
                    Component.text(sp.p.name), Component.text(text))
                    .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC));
        }
    }

    /** Повний нік або його "коротка" частина (Sashko_Gamer -> sashko, Dmytro2008 -> dmytro). */
    private boolean isMentioned(String lower, FakeManager.Active a) {
        String full = a.p.name.toLowerCase(Locale.ROOT);
        if (lower.contains(full)) return true;
        String shortName = full.split("[_\\d]+")[0];
        if (shortName.length() < 4) return false;
        return Pattern.compile("(?iu)(^|[^\\p{L}])" + Pattern.quote(shortName) + "([^\\p{L}]|$)").matcher(lower).find();
    }

    private boolean mentionsOtherPlayer(String lower, String sender) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(sender)) continue;
            if (p.getName().length() >= 4 && lower.contains(p.getName().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private Component chatLine(String name, String msg) {
        String fmt = plugin.getConfig().getString("chat.format", "<%name%> %msg%").replace("%name%", name);
        int i = fmt.indexOf("%msg%");
        LegacyComponentSerializer ser = LegacyComponentSerializer.legacyAmpersand();
        if (i < 0) return ser.deserialize(fmt).append(Component.text(" " + msg));
        return ser.deserialize(fmt.substring(0, i)).append(Component.text(msg))
                .append(ser.deserialize(fmt.substring(i + 5)));
    }

    // ---------- промпти для нейромережі ----------
    private String system(FakeManager.Persona p) {
        String lang = switch (p.lang) {
            case "uk" -> "Ukrainian (casual, like in a game chat)";
            case "ru" -> "Russian (casual)";
            case "pl" -> "Polish (casual)";
            default -> "English (casual)";
        };
        FileConfiguration c = plugin.getConfig();
        return c.getString("ai.system-template", "You are a Minecraft player named %name%.")
                .replace("%name%", p.name).replace("%language%", lang).replace("%style%", p.style)
                .replace("%server-info%", c.getString("ai.server-info", ""));
    }

    private String userPrompt(FakeManager.Persona p, String sender, String msg, boolean priv) {
        StringBuilder sb = new StringBuilder("Recent chat (oldest first):\n");
        synchronized (history) { for (String h : history) sb.append(h).append('\n'); }
        if (priv) sb.append("\n(").append(sender).append(" sent you a PRIVATE message: ").append(msg).append(")\n");
        sb.append("\nWrite ONLY the next chat message from ").append(p.name)
          .append(", replying to ").append(sender).append(". Match the language ").append(sender)
          .append(" writes in.");
        return sb.toString();
    }

    private void remember(String line) {
        int max = plugin.getConfig().getInt("ai.history-lines", 8);
        synchronized (history) {
            history.addLast(line.length() > 140 ? line.substring(0, 140) : line);
            while (history.size() > max) history.removeFirst();
        }
    }

    // ---------- постобробка (щоб не палилось) ----------
    private String clean(String raw) {
        if (raw == null) return null;
        String s = raw.strip().lines().findFirst().orElse("").strip();
        s = s.replaceFirst("^[\\w\\[\\]]{2,20}:\\s+", "");
        s = s.replace("*", "").replace("&", "").replace("§", "");
        s = s.replaceAll("^[\"'«“]+|[\"'»”]+$", "").strip();
        if (s.isEmpty()) return null;
        if (s.length() > 90) { int i = s.lastIndexOf(' ', 90); s = s.substring(0, i > 20 ? i : 90); }
        String l = s.toLowerCase(Locale.ROOT);
        for (String b : BAD) if (l.contains(b)) return null;
        if (rnd.nextDouble() < 0.7) s = Character.toLowerCase(s.charAt(0)) + s.substring(1);
        if (s.endsWith(".") && rnd.nextDouble() < 0.85) s = s.substring(0, s.length() - 1);
        if (s.length() > 5 && rnd.nextDouble() < 0.05) {
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
