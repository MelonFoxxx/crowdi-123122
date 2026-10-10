package dev.fakecrowd;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Вбудований легкий "ШІ" без зовнішніх залежностей.
 *
 * РОЗУМІННЯ: визначає мову, тип питання (чому/де/коли/хто/скільки/так-ні...), прохання, образи,
 *            похвалу/скаргу, ключові слова (з грубим "стемінгом" для uk/ru/en), пам'ятає тему розмови.
 * НАВЧАННЯ:  запам'ятовує пари "повідомлення -> відповідь" з реального чату (пошук за TF-IDF схожістю)
 *            і всі фрази гравців для генерації власних речень.
 * ЗБІР РЕЧЕНЬ: марковський ланцюг (триграми) по фразах гравців, який стартує з ключового слова
 *            повідомлення, плюс шаблони відповідей по типу питання та "віддзеркалення" (я купив -> ти купив?).
 */
final class MiniBrain {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static Pattern rx(String s) { return Pattern.compile(s, FLAGS); }

    private static final Pattern REPEAT = Pattern.compile("(.)\\1{2,}");
    private static final Pattern WH_START = rx("^(?:чому|чого|нащо|навіщо|де|куди|звідки|коли|хто|що|шо|чо|як|скільки|який|яка|яке|які|чи|почему|зачем|где|куда|откуда|когда|кто|что|как|сколько|какой|why|where|when|who|what|how|which)\\b");
    private static final Pattern K_HOWMANY = rx("\\b(?:скільки|сколько|how (?:many|much))\\b");
    private static final Pattern K_WHY = rx("\\b(?:чому|чого|нащо|навіщо|почему|зачем|why)\\b");
    private static final Pattern K_WHERE = rx("\\b(?:де|куди|звідки|где|куда|откуда|where)\\b");
    private static final Pattern K_WHEN = rx("\\b(?:коли|когда|when)\\b");
    private static final Pattern K_WHO = rx("\\b(?:хто|кто|who)\\b");
    private static final Pattern K_HOW = rx("\\b(?:як|как|how)\\b");
    private static final Pattern K_WHAT = rx("\\b(?:що|шо|чо|что|what)\\b");
    private static final Pattern INSULT = rx("\\b(?:ти|ты|you|u)\\b.{0,15}\\b(?:нуб\\w*|тупий|тупой|дурн\\w*|дурак|лох|noob|stupid|idiot|dumb)\\b");
    private static final Pattern REQUEST = rx("\\b(?:дай|дайте|подаруй|підкинь|скинь|кинь|одолжи|give me|gimme)\\b");
    private static final Pattern POS = rx("\\b(?:круто|класно|супер|топ|кайф|чудово|cool|nice|great|awesome|здорово|отлично)\\b");
    private static final Pattern NEG = rx("\\b(?:погано|жах|нудно|тупо|бісить|сумно|жесть|bad|sucks|boring|hate|скучно|плохо|облом)\\b");
    private static final Pattern PAST_RU_UK = rx("^я\\s+\\S*(?:в|ла|ли|ло|вся|лась|лися|лись)(?:\\s.*)?$");
    private static final Pattern I_AM = rx("^i(?: am|'m)\\s+.{2,30}$");

    private static final String[] SUFFIX = {"исть", "ться", "ами", "ями", "ого", "ему", "ому", "ння", "ати", "ять", "ити",
            "ать", "ить", "нии", "ing", "ою", "ею", "ах", "ях", "ив", "ов", "ев", "ам", "ям", "ом", "ем", "ии", "ий",
            "ой", "ая", "яя", "ие", "ed", "es", "ly", "а", "я", "у", "ю", "е", "и", "о", "ь", "s"};

    private static final Set<String> STOP = new HashSet<>();
    static {
        for (String w : ("это как так там тут але або для при над про які яка який яке тоже також дуже якщо коли очень "
                + "если когда или уже еще the and you are that this with for have not but was can what there from they "
                + "them мене тебе мені тобі тебя меня мне його она они вона вони его цей цього цьому тому потому чому "
                + "чего почему").split(" ")) STOP.add(norm(w));
    }

    // ---------- текстові утиліти ----------
    static String norm(String s) {
        String t = s.toLowerCase(Locale.ROOT).replace('ё', 'е').replace('ґ', 'г').replace('ї', 'и')
                .replace('і', 'и').replace('є', 'е').replace('ы', 'и').replace('э', 'е');
        return REPEAT.matcher(t).replaceAll("$1");
    }

    static String stem(String w) {
        for (String s : SUFFIX) if (w.endsWith(s) && w.length() - s.length() >= 3) return w.substring(0, w.length() - s.length());
        return w;
    }

    static Set<String> tokens(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : norm(s).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() < 3 || STOP.contains(w)) continue;
            out.add(stem(w));
        }
        return out;
    }

    static List<String> keywordsRaw(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() < 4 || STOP.contains(norm(w))) continue;
            if (!out.contains(w)) out.add(w);
        }
        return out;
    }

    static String detectLang(String s) {
        int uk = 0, ru = 0, lat = 0, cyr = 0;
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if ("іїєґ".indexOf(c) >= 0) uk++;
            else if ("ыэъё".indexOf(c) >= 0) ru++;
            if (c >= 'a' && c <= 'z') lat++;
            else if (Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC) cyr++;
        }
        if (uk > 0) return "uk";
        if (ru > 0) return "ru";
        return lat > cyr ? "en" : "uk";
    }

    // ---------- дані ----------
    private static final class Intent {
        final Pattern pattern;
        final Map<String, List<String>> replies = new HashMap<>();
        Intent(Pattern p) { this.pattern = p; }
    }

    private static final class Pair {
        final String prev, reply;
        final Set<String> stems;
        Pair(String prev, String reply) { this.prev = prev; this.reply = reply; this.stems = tokens(prev); }
    }

    private final FakeCrowd plugin;
    private final FakeManager mgr;
    private final Random rnd = new Random();
    private final Object lock = new Object();
    private final List<Intent> intents = new ArrayList<>();
    private final List<Pair> pairs = new ArrayList<>();
    private final List<String> corpus = new ArrayList<>();
    private final Map<String, List<String>> chain = new HashMap<>();
    private final Map<String, Integer> df = new HashMap<>();
    private final Map<String, String> stemToWord = new HashMap<>();
    private final Map<String, List<String>> topics = new HashMap<>();
    private final Deque<String> recent = new ArrayDeque<>();
    private final File pairsFile, corpusFile;
    private String prevText, prevSender;
    private long prevAt;
    private volatile boolean dirty, rebuild;

    MiniBrain(FakeCrowd plugin, FakeManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.pairsFile = new File(plugin.getDataFolder(), "learned.txt");
        this.corpusFile = new File(plugin.getDataFolder(), "corpus.txt");
        reload();
        loadLearned();
    }

    void reload() {
        intents.clear();
        for (Map<?, ?> m : plugin.getConfig().getMapList("brain.intents")) {
            try {
                Object rx = m.get("match");
                if (rx == null) continue;
                Intent in = new Intent(Pattern.compile(String.valueOf(rx), FLAGS));
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

    String stats() {
        synchronized (lock) { return pairs.size() + " reply pairs, " + corpus.size() + " sentences"; }
    }

    // ---------- головний метод ----------
    /** Повертає відповідь або null, якщо нічого підходящого. Викликати з головного потоку. */
    String reply(String lang, String msg, String sender) {
        String m = msg.strip();
        if (m.isEmpty()) return null;
        String detected = detectLang(m);
        String rl = (detected.equals("en") && !lang.equals("en") && rnd.nextDouble() < 0.5) ? "en" : lang;
        List<String> kw = keywordsRaw(m);
        List<String> ctx;
        String skey = sender.toLowerCase(Locale.ROOT);
        synchronized (lock) { ctx = kw.isEmpty() ? topics.getOrDefault(skey, List.of()) : kw; }

        String out = compute(rl, m, kw, ctx, sender);

        if (!kw.isEmpty()) {
            synchronized (lock) {
                if (topics.size() > 200) topics.clear();
                topics.put(skey, new ArrayList<>(kw.subList(0, Math.min(4, kw.size()))));
            }
        }
        return out;
    }

    private String compute(String rl, String m, List<String> kw, List<String> ctx, String sender) {
        FileConfiguration c = plugin.getConfig();

        // 1) правила з конфігу
        for (Intent in : intents) {
            if (in.pattern.matcher(m).find()) {
                List<String> l = in.replies.get(rl);
                if (l == null || l.isEmpty()) l = in.replies.get("en");
                if (l == null || l.isEmpty()) l = in.replies.get("uk");
                if (l != null && !l.isEmpty()) return l.get(rnd.nextInt(l.size()));
            }
        }

        // 2) відповіді, вивчені від реальних гравців
        if (rnd.nextDouble() < c.getDouble("brain.use-learned-chance", 0.6)) {
            String s = fromLearned(m);
            if (s != null) return s;
        }

        // 3) розуміння типу повідомлення
        if (INSULT.matcher(m).find()) return answer("insult", rl, ctx, sender);
        if (REQUEST.matcher(m).find()) return answer("request", rl, ctx, sender);

        boolean question = m.contains("?") || WH_START.matcher(m).find();
        if (question) {
            String kind = questionKind(m);
            if ((kind.equals("what") || kind.equals("how")) && rnd.nextDouble() < 0.5) {
                String g = markov(kw);
                if (g != null) return g;
            }
            String a = answer(kind, rl, ctx, sender);
            if (a != null) return a;
        } else {
            String r = reflect(rl, m);
            if (r != null) return r;
            if (POS.matcher(m).find()) return answer("praise", rl, ctx, sender);
            if (NEG.matcher(m).find()) return answer("complain", rl, ctx, sender);
        }

        // 4) власне речення з марковського ланцюга
        if (rnd.nextDouble() < c.getDouble("brain.markov-chance", 0.7)) {
            String g = markov(kw);
            if (g != null) return g;
        }
        return null;
    }

    private String questionKind(String m) {
        if (K_HOWMANY.matcher(m).find()) return "howmany";
        if (K_WHY.matcher(m).find()) return "why";
        if (K_WHERE.matcher(m).find()) return "where";
        if (K_WHEN.matcher(m).find()) return "when";
        if (K_WHO.matcher(m).find()) return "who";
        if (K_HOW.matcher(m).find()) return "how";
        if (K_WHAT.matcher(m).find()) return "what";
        return "yesno";
    }

    // ---------- шаблонні відповіді ----------
    private List<String> list(String path, String lang) {
        FileConfiguration c = plugin.getConfig();
        List<String> l = c.getStringList(path + "." + lang);
        if (l.isEmpty()) l = c.getStringList(path + ".en");
        if (l.isEmpty()) l = c.getStringList(path + ".uk");
        return l;
    }

    private String answer(String kind, String lang, List<String> ctx, String sender) {
        List<String> l = list("brain.answers." + kind, lang);
        if (l.isEmpty()) return null;
        for (int i = 0; i < 6; i++) {
            String f = fill(l.get(rnd.nextInt(l.size())), ctx, sender);
            if (f != null) return f;
        }
        return null;
    }

    /** {n} - число, {kw} - ключове слово з розмови, {player} - інший гравець онлайн. null якщо не вийшло. */
    private String fill(String t, List<String> ctx, String sender) {
        if (t.contains("{n}")) t = t.replace("{n}", String.valueOf(2 + rnd.nextInt(29)));
        if (t.contains("{kw}")) {
            if (ctx.isEmpty()) return null;
            t = t.replace("{kw}", ctx.get(rnd.nextInt(ctx.size())));
        }
        if (t.contains("{player}")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) if (!p.getName().equalsIgnoreCase(sender)) names.add(p.getName());
            for (FakeManager.Active a : mgr.all()) if (!a.p.name.equalsIgnoreCase(sender)) names.add(a.p.name);
            if (names.isEmpty()) return null;
            t = t.replace("{player}", names.get(rnd.nextInt(names.size())));
        }
        return t;
    }

    /** "я купив меч" -> "ти купив меч? серйозно?" */
    private String reflect(String lang, String m) {
        String t = m.toLowerCase(Locale.ROOT).replaceAll("[.!]+$", "").strip();
        if (t.split("\\s+").length > 7) return null;
        if (lang.equals("en")) {
            Matcher mm = I_AM.matcher(t);
            if (mm.matches()) {
                String x = t.replaceFirst("^i(?: am|'m)\\s+", "");
                String[] tpl = {"you are %s? really?", "why are you %s?"};
                return String.format(tpl[rnd.nextInt(tpl.length)], x);
            }
            return null;
        }
        if (PAST_RU_UK.matcher(t).matches()) {
            String x = t.substring(2).strip();
            String you = lang.equals("ru") ? "ты" : "ти";
            String[] tpl = lang.equals("ru")
                    ? new String[]{"%s %s? серьезно?", "а чего %s %s?", "ого, %s %s"}
                    : new String[]{"%s %s? серйозно?", "а чого %s %s?", "ого, %s %s"};
            return String.format(tpl[rnd.nextInt(tpl.length)], you, x);
        }
        return null;
    }

    // ---------- пошук схожих вивчених відповідей (TF-IDF) ----------
    private double w(String stem) {
        return Math.log(2.0 + (corpus.size() + 1.0) / (1.0 + df.getOrDefault(stem, 0)));
    }

    private String fromLearned(String m) {
        Set<String> q = tokens(m);
        if (q.isEmpty()) return null;
        double thr = plugin.getConfig().getDouble("brain.similarity-threshold", 0.4);
        synchronized (lock) {
            double wq = 0;
            for (String s : q) wq += w(s);
            List<Pair> best = new ArrayList<>();
            double bestScore = thr;
            for (Pair p : pairs) {
                if (recent.contains(p.reply)) continue;
                double shared = 0, wp = 0;
                for (String s : p.stems) wp += w(s);
                for (String s : q) if (p.stems.contains(s)) shared += w(s);
                if (shared == 0) continue;
                double sc = shared / Math.sqrt(wq * wp);
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

    // ---------- марковський генератор речень ----------
    private String markov(List<String> kw) {
        int minCorpus = plugin.getConfig().getInt("brain.min-corpus-for-markov", 40);
        synchronized (lock) {
            if (corpus.size() < minCorpus) return null;
            String seed = null;
            List<String> shuffled = new ArrayList<>(kw);
            Collections.shuffle(shuffled, rnd);
            for (String k : shuffled) {
                String raw = stemToWord.get(stem(norm(k)));
                if (raw != null && chain.containsKey(raw)) { seed = raw; break; }
            }
            String g = generate(seed);
            if (g == null) return null;
            if (rnd.nextDouble() < 0.2) {
                List<String> fl = plugin.getConfig().getStringList("brain.answers.filler.uk");
                if (!fl.isEmpty()) g = fl.get(rnd.nextInt(fl.size())) + " " + g;
            }
            return g;
        }
    }

    private String generate(String seed) {
        List<String> out = new ArrayList<>();
        String prev, cur;
        if (seed != null) { cur = seed; prev = null; out.add(cur); }
        else {
            List<String> starts = chain.get("^");
            if (starts == null || starts.isEmpty()) return null;
            cur = starts.get(rnd.nextInt(starts.size()));
            prev = "^";
            out.add(cur);
        }
        for (int i = 0; i < 9; i++) {
            List<String> opts = null;
            if (prev != null) opts = chain.get(prev + " " + cur);
            if (opts == null) opts = chain.get(cur);
            if (opts == null || opts.isEmpty()) break;
            String next = opts.get(rnd.nextInt(opts.size()));
            if (next.equals("$")) break;
            out.add(next);
            prev = cur;
            cur = next;
        }
        if (out.size() < 2) return null;
        return String.join(" ", out);
    }

    private void addToChain(String sentence) {
        String[] w = sentence.split("\\s+");
        if (w.length < 2) return;
        List<String> seq = new ArrayList<>();
        seq.add("^");
        seq.addAll(Arrays.asList(w));
        seq.add("$");
        for (int i = 0; i < seq.size() - 1; i++) {
            chain.computeIfAbsent(seq.get(i), k -> new ArrayList<>()).add(seq.get(i + 1));
            if (i >= 1) chain.computeIfAbsent(seq.get(i - 1) + " " + seq.get(i), k -> new ArrayList<>()).add(seq.get(i + 1));
        }
        for (String x : w) {
            if (x.length() < 4) continue;
            String st = stem(norm(x));
            if (st.length() >= 3) stemToWord.put(st, x);
        }
    }

    private void indexSentence(String s) {
        addToChain(s);
        for (String t : tokens(s)) df.merge(t, 1, Integer::sum);
    }

    private void rebuildIndexes() {
        chain.clear(); df.clear(); stemToWord.clear();
        for (String s : corpus) indexSentence(s);
    }

    // ---------- навчання з реального чату ----------
    /** Вчимося з повідомлень гравців. Викликати з головного потоку. */
    void learn(String sender, String msg) {
        FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("brain.learn-from-chat", true)) return;
        String t = sanitize(msg);
        long now = System.currentTimeMillis();
        synchronized (lock) {
            if (t != null) {
                String plain = t.replaceAll("[!?.,;:\"«»]+", " ").replaceAll("\\s+", " ").strip();
                if (plain.split(" ").length >= 2) {
                    corpus.add(plain);
                    indexSentence(plain);
                    int maxC = c.getInt("brain.max-learned-sentences", 4000);
                    while (corpus.size() > maxC) { corpus.remove(0); rebuild = true; }
                }
                if (prevText != null && !sender.equals(prevSender) && now - prevAt < 30_000L) {
                    Pair p = new Pair(prevText, t);
                    if (!p.stems.isEmpty()) {
                        pairs.add(p);
                        int max = c.getInt("brain.max-learned-pairs", 1500);
                        while (pairs.size() > max) pairs.remove(rnd.nextInt(pairs.size()));
                    }
                }
                dirty = true;
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

    // ---------- збереження ----------
    private void loadLearned() {
        try {
            synchronized (lock) {
                if (pairsFile.exists()) {
                    for (String line : Files.readAllLines(pairsFile.toPath(), StandardCharsets.UTF_8)) {
                        String[] p = line.split("\t", 2);
                        if (p.length == 2) pairs.add(new Pair(p[0], p[1]));
                    }
                }
                if (corpusFile.exists()) {
                    corpus.addAll(Files.readAllLines(corpusFile.toPath(), StandardCharsets.UTF_8));
                }
                rebuildIndexes();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not load learned data: " + e.getMessage());
        }
    }

    void save() {
        if (!dirty) return;
        List<String> pl = new ArrayList<>();
        List<String> cl;
        synchronized (lock) {
            if (rebuild) { rebuildIndexes(); rebuild = false; }
            for (Pair p : pairs) pl.add(p.prev.replace('\t', ' ') + "\t" + p.reply.replace('\t', ' '));
            cl = new ArrayList<>(corpus);
            dirty = false;
        }
        try {
            plugin.getDataFolder().mkdirs();
            Files.write(pairsFile.toPath(), pl, StandardCharsets.UTF_8);
            Files.write(corpusFile.toPath(), cl, StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not save learned data: " + e.getMessage());
        }
    }
}
