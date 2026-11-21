package com.nerithmc.ghosthit.monitor;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Player state captured every server tick. */
public record Snapshot(long tick,
                       Vector eye,
                       Vector dir,
                       BoundingBox box,
                       boolean gliding,
                       boolean sprinting,
                       boolean onGround) {
}
