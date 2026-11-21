package com.nerithmc.ghosthit.monitor;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired by NerithMcHitMonitor on every left-click swing of a player.
 * claimedVictim is non-null when the client's attack packet named a player (PrePlayerAttackEntityEvent).
 */
public class HitAttemptEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player attacker;
    private final long tick;
    private final UUID claimedVictim;

    public HitAttemptEvent(Player attacker, long tick, UUID claimedVictim) {
        super(false);
        this.attacker = attacker;
        this.tick = tick;
        this.claimedVictim = claimedVictim;
    }

    public Player getAttacker() {
        return attacker;
    }

    public long getTick() {
        return tick;
    }

    public UUID getClaimedVictim() {
        return claimedVictim;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
