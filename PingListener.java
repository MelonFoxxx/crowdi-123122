package dev.fakecrowd;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

final class PingListener implements Listener {
    private final FakeCrowd plugin;
    private final FakeManager mgr;

    PingListener(FakeCrowd plugin, FakeManager mgr) { this.plugin = plugin; this.mgr = mgr; }

    @EventHandler
    public void onPing(PaperServerListPingEvent e) {
        if (!mgr.pingEnabled()) return;
        e.setNumPlayers(mgr.displayedOnline(e.getNumPlayers()));

        // Підказка при наведенні на онлайн. Якщо цей блок не компілюється на твоїй збірці Paper -
        // просто видали його (число онлайну працюватиме й без нього).
        if (plugin.getConfig().getBoolean("online.show-fake-in-sample", true)) {
            for (FakeManager.Active a : mgr.all()) {
                e.getListedPlayers().add(new PaperServerListPingEvent.ListedPlayerInfo(a.p.name, a.p.uuid));
            }
        }
    }
}
