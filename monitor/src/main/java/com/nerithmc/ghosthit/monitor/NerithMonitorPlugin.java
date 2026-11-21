package com.nerithmc.ghosthit.monitor;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class NerithMonitorPlugin extends JavaPlugin implements Listener {

    private record Claim(UUID victim, long tick) {
    }

    private static NerithMonitorPlugin instance;

    private long tick = 0;
    private int historyTicks;

    private final Map<UUID, ArrayDeque<Snapshot>> history = new HashMap<>();
    private final Map<UUID, Long> lastDamageTick = new HashMap<>();
    private final Map<UUID, Claim> claims = new HashMap<>();

    public static NerithMonitorPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        historyTicks = Math.max(10, getConfig().getInt("history-ticks", 40));
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::onTick, 1L, 1L);
        getLogger().info("NerithMc HitMonitor enabled.");
    }

    @Override
    public void onDisable() {
        instance = null;
    }

    private void onTick() {
        tick++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            Location eye = p.getEyeLocation();
            Snapshot s = new Snapshot(tick, eye.toVector(), eye.getDirection(),
                    p.getBoundingBox().clone(), p.isGliding(), p.isSprinting(), p.isOnGround());
            ArrayDeque<Snapshot> dq = history.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
            dq.addFirst(s);
            while (dq.size() > historyTicks) dq.removeLast();
        }
    }

    // ------------------------------------------------------------------ API

    public long getCurrentTick() {
        return tick;
    }

    /** Snapshot at the given tick, or the nearest older one. Null if there is none. */
    public Snapshot getSnapshotAt(UUID id, long atTick) {
        ArrayDeque<Snapshot> dq = history.get(id);
        if (dq == null) return null;
        for (Snapshot s : dq) {
            if (s.tick() <= atTick) return s;
        }
        return null;
    }

    /** Tick of the last melee damage event (even cancelled) dealt by this player. */
    public long getLastDamageTick(UUID attacker) {
        return lastDamageTick.getOrDefault(attacker, -1000L);
    }

    // --------------------------------------------------------------- Events

    /** The client sent an attack packet naming a player. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreAttack(PrePlayerAttackEntityEvent e) {
        if (e.getAttacked() instanceof Player victim) {
            claims.put(e.getPlayer().getUniqueId(), new Claim(victim.getUniqueId(), tick));
        }
    }

    /** Left click swing. Fired for every swing. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        GameMode gm = p.getGameMode();
        if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return;

        Claim c = claims.get(p.getUniqueId());
        UUID claimed = (c != null && tick - c.tick() <= 2) ? c.victim() : null;
        Bukkit.getPluginManager().callEvent(new HitAttemptEvent(p, tick, claimed));
    }

    /** Every melee damage attempt the server actually processed (cancelled ones too). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p) {
            lastDamageTick.put(p.getUniqueId(), tick);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        history.remove(id);
        lastDamageTick.remove(id);
        claims.remove(id);
    }
}
