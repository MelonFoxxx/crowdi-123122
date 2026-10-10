package dev.fakecrowd;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Вбудований легкий "ШІ" без зовнішніх залежностей:
 *  1) правила (intents) з config.yml - привітання, "ти бот?", "як справи" тощо;
 *  2) самонавчання: запам'ятовує пари "повідомлення -> відповідь" з реального чату
 *     і відповідає схожими фразами з вашого ж сервера.
 * Працює за мікросекунди, пам'яті майже не їсть.
 */
final class MiniBrain {

    private static final class Intent {
        final Pattern pattern;
        final Map<String, List<String>> replies = new HashMap<>();
        Intent(Pattern p) { this.pattern = p; }
    }

    private static final class Pair {
        final String prev, reply;
        final Set<String> keys;
        Pair(String prev, String reply) { this.prev = prev; this.reply = reply; this.keys = tokens(prev); }
    }

    private final FakeCrowd plugin;
    private final FakeManager mgr;
    private final Random rnd = new Random();
    private final List<Intent> intents = new ArrayList<>();
    private final List<Pair> pairs = new ArrayList<>();
    private final Deque<String> recent = new ArrayDeque<>();
    private final File file;
    private String prevText, prevSender;
    private long prevAt;
    private volatile boolean dirty;

    MiniBrain(FakeCrowd plugin, FakeManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.file = new File(plugin.getDataFolder(), "learned.txt");
        reload();
        loadLearned();
    }

    void reload() {
        intents.clear();
        for (Map<?, ?> m : plugin.getConfig().getMapList("brain.intents")) {
            try {
                Object rx = m.get("match");
                if (rx == null) continue;
                Intent in = new Intent(Pattern.compile(String.valueOf(rx),
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS));
                for (String lang : List.of("uk", "ru", "en", "pl")) {
                    Object o = m.get(lang);
                    if (o instanceof List<?> l) {
                        List<String> out = new ArrayList<>();
                        for (Object x : l) out.add(String.valueOf(x));
                        in.replies.put(lang, out);
                    }
                }
                intents.add(in);
            } catch (Exception ex) {
                plugin.getLogger().warning("Bad intent in config: " + ex.getMessage());
            }
        }
    }

    /** Повертає відповідь або null, якщо нічого підходящого. */
    String reply(String lang, String msg) {
        String m = msg.strip();
        for (Intent in : intents) {
            if (in.pattern.matcher(m).find()) {
                List<String> l = in.replies.get(lang);
                if (l == null || l.isEmpty()) l = in.replies.get("en");
                if (l != null && !l.isEmpty()) return l.get(rnd.nextInt(l.size()));
            }
        }
        if (rnd.nextDouble() < plugin.getConfig().getDouble("brain.use-learned-chance", 0.6)) {
            return fromLearned(m);
        }
        return null;
    }

    private String fromLearned(String m) {
        Set<String> k = tokens(m);
        if (k.isEmpty()) return null;
        synchronized (pairs) {
            List<Pair> best = new ArrayList<>();
            double bestScore = 0.33;
            for (Pair p : pairs) {
                if (recent.contains(p.reply)) continue;
                int inter = 0;
                for (String t : k) if (p.keys.contains(t)) inter++;
                if (inter == 0) continue;
                double sc = (double) inter / (k.size() + p.keys.size() - inter);
                if (sc > bestScore) { bestScore = sc; best.clear(); best.add(p); }
                else if (sc == bestScore && !best.isEmpty()) best.add(p);
            }
            if (best.isEmpty()) return null;
            String r = best.get(rnd.nextInt(best.size())).reply;
            recent.addLast(r);
            while (recent.size() > 20) recent.removeFirst();
            return r;
        }
    }

    /** Вчимося з реального чату: (повідомлення A) -> (відповідь іншого гравця B). Викликати з головного потоку. */
    void learn(String sender, String msg) {
        if (!plugin.getConfig().getBoolean("brain.learn-from-chat", true)) return;
        String t = sanitize(msg);
        long now = System.currentTimeMillis();
        synchronized (pairs) {
            if (t != null && prevText != null && !sender.equals(prevSender) && now - prevAt < 30_000L) {
                Pair p = new Pair(prevText, t);
                if (!p.keys.isEmpty()) {
                    pairs.add(p);
                    dirty = true;
                    int max = plugin.getConfig().getInt("brain.max-learned-pairs", 1500);
                    while (pairs.size() > max) pairs.remove(rnd.nextInt(pairs.size()));
                }
            }
            prevText = t;
            prevSender = sender;
            prevAt = now;
        }
    }

    private String sanitize(String msg) {
        String s = msg.strip().toLowerCase(Locale.ROOT);
        if (s.length() < 2 || s.length() > 60) return null;
        if (s.startsWith("/") || s.contains("http") || s.contains("www.") || s.contains("@")) return null;
        String[] words = s.split("[^\\p{L}\\p{N}]+");
        if (words.length > 10) return null;
        List<String> blocked = plugin.getConfig().getStringList("brain.blocked-words");
        for (String w : words) {
            for (String b : blocked) {
                if (!b.isBlank() && w.startsWith(b.toLowerCase(Locale.ROOT))) return null;
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) if (s.contains(p.getName().toLowerCase(Locale.ROOT))) return null;
        for (FakeManager.Active a : mgr.all()) if (s.contains(a.p.name.toLowerCase(Locale.ROOT))) return null;
        return s;
    }

    static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 3) out.add(w);
        return out;
    }

    // ---------- збереження ----------
    private void loadLearned() {
        try {
            if (!file.exists()) return;
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", 2);
                if (p.length == 2) pairs.add(new Pair(p[0], p[1]));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not load learned.txt: " + e.getMessage());
        }
    }

    void save() {
        if (!dirty) return;
        List<String> lines = new ArrayList<>();
        synchronized (pairs) {
            for (Pair p : pairs) lines.add(p.prev.replace('\t', ' ') + "\t" + p.reply.replace('\t', ' '));
            dirty = false;
        }
        try {
            plugin.getDataFolder().mkdirs();
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not save learned.txt: " + e.getMessage());
        }
    }
}
