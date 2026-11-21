package com.nerithmc.ghosthit.verifier;

import com.nerithmc.ghosthit.monitor.HitAttemptEvent;
import com.nerithmc.ghosthit.monitor.NerithMonitorPlugin;
import com.nerithmc.ghosthit.monitor.Snapshot;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NerithVerifierPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private NerithMonitorPlugin monitor;
    private Method vanillaAttack;

    private Attribute attrDamage;
    private Attribute attrReach;

    // config
    private boolean debug;
    private boolean respectItemCooldown;
    private String mode;
    private double reachDefault, reachTol, expansion, claimedExtra, elytraExtra, scanRadius, minStrength;
    private int maxRewind, rewindSlack, attackerSlack, maxPerSecond;

    private final Map<UUID, ArrayDeque<Long>> recent = new HashMap<>();
    private final Map<String, Long> pairLast = new HashMap<>();
    private int detected;
    private int corrected;

    @Override
    public void onEnable() {
        monitor = NerithMonitorPlugin.get();
        if (monitor == null) {
            getLogger().severe("NerithMcHitMonitor is not running. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        loadSettings();

        try {
            vanillaAttack = Player.class.getMethod("attack", Entity.class);
        } catch (NoSuchMethodException ex) {
            vanillaAttack = null;
            getLogger().warning("Player#attack not found, using MANUAL damage calculation.");
        }

        attrDamage = findAttribute("generic.attack_damage", "attack_damage");
        attrReach = findAttribute("player.entity_interaction_range", "entity_interaction_range");

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("nerithghost") != null) {
            getCommand("nerithghost").setExecutor(this);
        }
        getLogger().info("NerithMc HitVerifier enabled.");
    }

    private void loadSettings() {
        reloadConfig();
        debug = getConfig().getBoolean("debug", false);
        mode = getConfig().getString("damage-mode", "AUTO").toUpperCase();
        reachDefault = getConfig().getDouble("reach-default", 3.0);
        reachTol = getConfig().getDouble("reach-tolerance", 0.35);
        expansion = getConfig().getDouble("hitbox-expansion", 0.15);
        claimedExtra = getConfig().getDouble("claimed-extra-tolerance", 0.6);
        elytraExtra = getConfig().getDouble("elytra-extra-tolerance", 0.5);
        scanRadius = getConfig().getDouble("scan-radius", 14.0);
        minStrength = getConfig().getDouble("min-attack-strength", 0.25);
        respectItemCooldown = getConfig().getBoolean("respect-item-cooldown", true);
        maxRewind = getConfig().getInt("max-rewind-ticks", 8);
        rewindSlack = getConfig().getInt("rewind-slack-ticks", 2);
        attackerSlack = getConfig().getInt("attacker-slack-ticks", 2);
        maxPerSecond = getConfig().getInt("max-corrections-per-second", 10);
    }

    // ------------------------------------------------------------ helpers

    private static Attribute findAttribute(String... keys) {
        for (String k : keys) {
            Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(k));
            if (a != null) return a;
        }
        return null;
    }

    private static double getAttr(LivingEntity e, Attribute attr, double def) {
        if (attr == null) return def;
        AttributeInstance inst = e.getAttribute(attr);
        return inst == null ? def : inst.getValue();
    }

    private static boolean fighter(Player p) {
        GameMode gm = p.getGameMode();
        return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
    }

    private boolean validVictim(Player a, Player o) {
        return o != null && o != a && o.isOnline() && !o.isDead() && fighter(o)
                && !o.isInvulnerable() && o.getWorld().equals(a.getWorld());
    }

    // ------------------------------------------------------------- verify

    @EventHandler
    public void onAttempt(HitAttemptEvent e) {
        // The attack packet is processed before the swing packet, so one tick later we know
        // whether the server really processed a hit or not.
        Bukkit.getScheduler().runTask(this, () -> verify(e));
    }

    private void verify(HitAttemptEvent e) {
        Player a = e.getAttacker();
        if (!a.isOnline() || a.isDead() || !fighter(a)) return;

        long t = e.getTick();
        // The server already processed a hit for this click -> not a ghost hit.
        if (monitor.getLastDamageTick(a.getUniqueId()) >= t - 1) return;

        List<Player> candidates = findCandidates(e, a);
        if (candidates.isEmpty()) return;

        Player target = null;
        double bestDist = Double.MAX_VALUE;
        for (Player c : candidates) {
            boolean claimed = c.getUniqueId().equals(e.getClaimedVictim());
            double d = hitDistance(e, a, c, claimed);
            if (d >= 0 && d < bestDist) {
                bestDist = d;
                target = c;
            }
        }
        if (target == null) return;

        // Legit "no damage" situations: victim is in his invulnerability frames.
        if (target.getNoDamageTicks() > target.getMaximumNoDamageTicks() / 2.0f) return;

        // Attack cooldown / item cooldown of the weapon.
        if (a.getCooledAttackStrength(0.5f) < minStrength) return;
        ItemStack hand = a.getInventory().getItemInMainHand();
        if (respectItemCooldown && !hand.getType().isAir() && a.hasCooldown(hand.getType())) return;

        String key = a.getUniqueId() + ":" + target.getUniqueId();
        Long last = pairLast.get(key);
        if (last != null && t - last < 2) return;
        if (!allowedByRate(a)) return;

        pairLast.put(key, t);
        detected++;

        if (debug) {
            PotionEffect st = a.getPotionEffect(PotionEffectType.STRENGTH);
            getLogger().info("[GhostHit] " + a.getName() + " -> " + target.getName()
                    + " dist=" + String.format("%.2f", bestDist)
                    + " ping=" + a.getPing()
                    + " strengthLevel=" + (st == null ? 0 : st.getAmplifier() + 1)
                    + " sharpness=" + hand.getEnchantmentLevel(Enchantment.SHARPNESS)
                    + " cooldown=" + String.format("%.2f", a.getCooledAttackStrength(0.5f)));
        }

        applyHit(a, target);
        corrected++;
    }

    private List<Player> findCandidates(HitAttemptEvent e, Player a) {
        List<Player> list = new ArrayList<>(2);
        UUID claimed = e.getClaimedVictim();
        if (claimed != null) {
            Player c = Bukkit.getPlayer(claimed);
            if (validVictim(a, c)) list.add(c);
            return list;
        }
        for (Player o : a.getWorld().getNearbyPlayers(a.getLocation(), scanRadius)) {
            if (validVictim(a, o)) list.add(o);
        }
        return list;
    }

    /**
     * Aim check with lag compensation. Returns the distance eye -> hitbox of the best matching
     * (rewind tick, attacker tick) combination, or -1 if the attacker was not aiming at the victim.
     */
    private double hitDistance(HitAttemptEvent e, Player a, Player v, boolean claimed) {
        long t = e.getTick();
        World w = a.getWorld();
        int center = Math.min(maxRewind, (int) Math.round(Math.max(0, a.getPing()) / 50.0));
        int from = Math.max(0, center - rewindSlack);
        int to = Math.min(maxRewind + rewindSlack, center + rewindSlack);
        double baseReach = getAttr(a, attrReach, reachDefault);

        double best = -1;
        for (int k = from; k <= to; k++) {
            Snapshot vs = monitor.getSnapshotAt(v.getUniqueId(), t - k);
            if (vs == null) continue;
            for (int j = 0; j <= attackerSlack; j++) {
                Snapshot as = monitor.getSnapshotAt(a.getUniqueId(), t - j);
                if (as == null) continue;

                boolean glide = vs.gliding() || as.gliding();
                double reach = baseReach + reachTol + (glide ? elytraExtra : 0) + (claimed ? claimedExtra : 0);
                double exp = expansion + (glide ? elytraExtra * 0.5 : 0);

                BoundingBox box = vs.box().clone().expand(exp);
                RayTraceResult hit = box.rayTrace(as.eye(), as.dir(), reach);
                if (hit == null) continue;

                double dist = hit.getHitPosition().distance(as.eye());
                RayTraceResult blk = w.rayTraceBlocks(as.eye().toLocation(w), as.dir(), dist,
                        FluidCollisionMode.NEVER, true);
                if (blk != null && blk.getHitPosition().distance(as.eye()) + 0.05 < dist) continue;

                if (best < 0 || dist < best) best = dist;
            }
        }
        return best;
    }

    private boolean allowedByRate(Player a) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> dq = recent.computeIfAbsent(a.getUniqueId(), k -> new ArrayDeque<>());
        while (!dq.isEmpty() && now - dq.peekFirst() > 1000) dq.pollFirst();
        if (dq.size() >= maxPerSecond) return false;
        dq.addLast(now);
        return true;
    }

    // ------------------------------------------------------------- damage

    private void applyHit(Player a, Player v) {
        if (!"MANUAL".equals(mode) && vanillaAttack != null) {
            try {
                vanillaAttack.invoke(a, v);
                return;
            } catch (Exception ex) {
                if (debug) getLogger().warning("Player#attack failed, using manual: " + ex.getMessage());
            }
        }
        manualAttack(a, v);
    }

    /** Hand-made copy of the vanilla melee formula. */
    private void manualAttack(Player a, Player v) {
        ItemStack hand = a.getInventory().getItemInMainHand();

        // Attack damage attribute already contains the weapon, Strength and Weakness modifiers.
        double base = getAttr(a, attrDamage, 1.0);
        float cd = a.getCooledAttackStrength(0.5f);

        int sharp = hand.getEnchantmentLevel(Enchantment.SHARPNESS);
        double enchantBonus = sharp > 0 ? 1.0 + 0.5 * (sharp - 1) : 0.0;

        double dmg = base * (0.2 + cd * cd * 0.8) + enchantBonus * cd;

        boolean crit = cd > 0.9f && a.getFallDistance() > 0 && !a.isOnGround() && !a.isInWater()
                && !a.isSprinting() && !a.isInsideVehicle() && !a.hasPotionEffect(PotionEffectType.BLINDNESS);
        if (crit) dmg *= 1.5;

        v.damage(dmg, a);

        // Extra knockback: Knockback enchant + sprint hit (the base knockback is done by damage()).
        int kb = hand.getEnchantmentLevel(Enchantment.KNOCKBACK) + (a.isSprinting() && cd > 0.9f ? 1 : 0);
        if (kb > 0) {
            double yaw = Math.toRadians(a.getLocation().getYaw());
            double s = kb * 0.5;
            Vector vel = v.getVelocity();
            vel.setX(vel.getX() / 2 - Math.sin(yaw) * s);
            vel.setZ(vel.getZ() / 2 + Math.cos(yaw) * s);
            if (v.isOnGround()) vel.setY(Math.min(0.4, vel.getY() / 2 + s));
            v.setVelocity(vel);
        }

        int fire = hand.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
        if (fire > 0) v.setFireTicks(Math.max(v.getFireTicks(), fire * 80));

        a.resetCooldown();
    }

    // ------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            sender.sendMessage("NerithMc HitVerifier: config reloaded.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("stats")) {
            sender.sendMessage("NerithMc HitVerifier: ghost hits detected=" + detected + ", corrected=" + corrected);
            return true;
        }
        sender.sendMessage("/nerithghost <reload|stats>");
        return true;
    }
}
