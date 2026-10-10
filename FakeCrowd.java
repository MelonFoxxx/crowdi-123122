package dev.fakecrowd;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.stream.Collectors;

public final class FakeCrowd extends JavaPlugin {

    private FakeManager manager;
    private ChatBrain brain;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new FakeManager(this);
        brain = new ChatBrain(this, manager);

        Bukkit.getPluginManager().registerEvents(new Hooks(this, manager, brain), this);
        Bukkit.getPluginManager().registerEvents(new PingListener(this, manager), this);

        manager.start();
        brain.start();
        Bukkit.getScheduler().runTaskTimer(this, brain::idleTick, 400L, 400L);
        getLogger().info("FakeCrowd enabled.");
    }

    @Override
    public void onDisable() {
        if (brain != null) brain.shutdown();
        if (manager != null) manager.shutdown();
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) { s.sendMessage("/fakecrowd <reload|status>"); return true; }
        switch (a[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                manager.reload();
                brain.reload();
                s.sendMessage("FakeCrowd: config reloaded.");
            }
            case "status" -> {
                s.sendMessage("Active fakes: " + manager.all().stream()
                        .map(x -> x.p.name).collect(Collectors.joining(", ")));
                s.sendMessage("Learned: " + brain.stats());
                s.sendMessage("Displayed online base: " + manager.displayedOnline(Bukkit.getOnlinePlayers().size()));
            }
            default -> s.sendMessage("/fakecrowd <reload|status>");
        }
        return true;
    }
}
