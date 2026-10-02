package org.pawling.eaglerlocust;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class EaglerLocustPlugin extends JavaPlugin implements Listener, TabExecutor {

    private enum State {
        STALKING,
        AMBUSHING,
        FLEEING
    }

    private static final long TICK_MILLIS = 50L;

    private final Random random = new Random();
    private final Set<UUID> attemptedThisNight = new HashSet<>();
    private final Set<UUID> containerBusy = new HashSet<>();
    private final Map<UUID, Long> interactionBusyUntil = new HashMap<>();

    private NamespacedKey locustMarkerKey;
    private Mannequin locust;
    private UUID targetId;
    private UUID pendingReappearTargetId;
    private State state = State.STALKING;

    private long reappearAtMillis;
    private long fleeStartedAtMillis;
    private long nextAttackAtMillis;
    private long nextShadowRelocateAtMillis;
    private Location fleeCover;
    private boolean spawnedByForceCommand;

    private double isolationRadius;
    private int spawnMinAxis;
    private int spawnMaxAxis;
    private int spawnAttempts;
    private double stalkSpeed;
    private double ambushSpeed;
    private double fleeSpeed;
    private double stalkStopDistance;
    private double attackRange;
    private double attackDamage;
    private int attackCooldownTicks;
    private double directLookDot;
    private double facingDot;
    private double fleeSuccessDistance;
    private int fleeTimeoutSeconds;
    private int reappearCooldownSeconds;
    private int shadowRelocateMin;
    private int shadowRelocateMax;
    private int shadowRelocateCooldownSeconds;
    private long interactionBusyWindowMillis;
    private String spawnMessage;
    private URL herobrineSkinUrl;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        locustMarkerKey = new NamespacedKey(this, "locust_entity");
        loadSettings();
        removeStaleLocustEntities();

        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("locust") != null) {
            getCommand("locust").setExecutor(this);
            getCommand("locust").setTabCompleter(this);
        }

        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 2L);
        getLogger().info("EaglerLocustFall26 enabled. Waiting for an isolated player at night.");
    }

    @Override
    public void onDisable() {
        dismissLocust(false, false);
        removeStaleLocustEntities();
        containerBusy.clear();
        interactionBusyUntil.clear();
        pendingReappearTargetId = null;
    }

    private void loadSettings() {
        reloadConfig();
        isolationRadius = Math.max(1.0, getConfig().getDouble("isolation-radius", 24.0));
        spawnMinAxis = Math.max(20, getConfig().getInt("spawn-min-axis-distance", 20));
        spawnMaxAxis = Math.max(spawnMinAxis, getConfig().getInt("spawn-max-axis-distance", 36));
        spawnAttempts = Math.max(12, getConfig().getInt("spawn-search-attempts", 72));
        stalkSpeed = clamp(getConfig().getDouble("stalk-speed", 0.34), 0.05, 1.25);
        ambushSpeed = clamp(getConfig().getDouble("ambush-speed", 0.72), 0.05, 1.75);
        fleeSpeed = clamp(getConfig().getDouble("flee-speed", 0.88), 0.05, 2.0);
        stalkStopDistance = Math.max(2.0, getConfig().getDouble("stalk-stop-distance", 4.5));
        attackRange = Math.max(1.0, getConfig().getDouble("attack-range", 2.7));
        attackDamage = Math.max(0.0, getConfig().getDouble("attack-damage", 16.0));
        attackCooldownTicks = Math.max(1, getConfig().getInt("attack-cooldown-ticks", 16));
        directLookDot = clamp(getConfig().getDouble("look-directly-dot", 0.975), -1.0, 1.0);
        facingDot = clamp(getConfig().getDouble("facing-dot", 0.20), -1.0, 1.0);
        fleeSuccessDistance = Math.max(6.0, getConfig().getDouble("flee-success-distance", 18.0));
        fleeTimeoutSeconds = Math.max(2, getConfig().getInt("flee-timeout-seconds", 8));
        reappearCooldownSeconds = Math.max(1, getConfig().getInt("reappear-cooldown-seconds", 60));
        shadowRelocateMin = Math.max(4, getConfig().getInt("shadow-relocate-min-distance", 8));
        shadowRelocateMax = Math.max(shadowRelocateMin, getConfig().getInt("shadow-relocate-max-distance", 16));
        shadowRelocateCooldownSeconds = Math.max(1, getConfig().getInt("shadow-relocate-cooldown-seconds", 4));
        interactionBusyWindowMillis = Math.max(500L, getConfig().getLong("interaction-busy-window-ms", 3000L));
        spawnMessage = getConfig().getString("spawn-message", "watch your back..");

        String skin = getConfig().getString("herobrine-skin-url",
                "https://textures.minecraft.net/texture/98b7ca3c7d314a61abed8fc18d797fc30b6efc8445425c4e250997e52e6cb");
        try {
            herobrineSkinUrl = new URL(skin);
        } catch (MalformedURLException exception) {
            getLogger().warning("Invalid herobrine-skin-url; using the built-in Herobrine texture.");
            try {
                herobrineSkinUrl = new URL("https://textures.minecraft.net/texture/98b7ca3c7d314a61abed8fc18d797fc30b6efc8445425c4e250997e52e6cb");
            } catch (MalformedURLException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        pruneBusyWindows(now);
        resetDaytimeWorlds();

        if (locust != null && (!locust.isValid() || locust.isDead())) {
            locust = null;
        }

        if (locust == null) {
            handleNoActiveLocust(now);
            return;
        }

        Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
        if (!isUsableTarget(target)) {
            dismissLocust(false, false);
            return;
        }

        if (!spawnedByForceCommand && !isNight(target.getWorld())) {
            dismissLocust(false, false);
            pendingReappearTargetId = null;
            return;
        }

        if (!target.getWorld().equals(locust.getWorld())) {
            dismissLocust(false, false);
            return;
        }

        if (target.isDead() || target.getHealth() <= 0.0) {
            dismissLocust(false, false);
            return;
        }

        if (state == State.FLEEING) {
            tickFlee(target, now);
            return;
        }

        boolean directLook = isLookingDirectlyAt(target, locust.getLocation());
        if (directLook) {
            beginFlee(target, now);
            return;
        }

        boolean busy = isTargetBusy(target, now);
        if (busy) {
            state = State.AMBUSHING;
            tickAmbush(target, now);
        } else {
            state = State.STALKING;
            tickStalk(target, now);
        }
    }

    private void handleNoActiveLocust(long now) {
        if (pendingReappearTargetId != null) {
            Player pending = Bukkit.getPlayer(pendingReappearTargetId);
            if (!isUsableTarget(pending) || (!spawnedByForceCommand && !isNight(pending.getWorld()))) {
                pendingReappearTargetId = null;
            } else if (now >= reappearAtMillis) {
                pendingReappearTargetId = null;
                if (isIsolated(pending)) {
                    Location spawn = findConcealedSpawn(pending, spawnMinAxis, spawnMaxAxis, spawnAttempts, true);
                    if (spawn != null) {
                        spawnLocust(pending, spawn, false, false);
                    } else {
                        getLogger().fine("Cooldown ended but no safe concealed reappearance location was found.");
                    }
                }
                if (locust == null) {
                    spawnedByForceCommand = false;
                }
            }
            if (locust != null || pendingReappearTargetId != null) {
                return;
            }
        }

        if (spawnedByForceCommand) {
            spawnedByForceCommand = false;
        }

        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL || !isNight(world)) {
                continue;
            }
            if (attemptedThisNight.contains(world.getUID())) {
                continue;
            }

            attemptedThisNight.add(world.getUID());
            List<Player> eligible = new ArrayList<>();
            for (Player player : world.getPlayers()) {
                if (isUsableTarget(player) && isIsolated(player)) {
                    eligible.add(player);
                }
            }

            if (eligible.isEmpty()) {
                getLogger().fine("Night spawn skipped: every valid player is grouped with someone else.");
                continue;
            }

            Collections.shuffle(eligible, random);
            for (Player target : eligible) {
                Location spawn = findConcealedSpawn(target, spawnMinAxis, spawnMaxAxis, spawnAttempts, true);
                if (spawn != null) {
                    spawnLocust(target, spawn, true, false);
                    return;
                }
            }

            getLogger().fine("Night spawn skipped: isolated players existed, but no concealed safe location passed the spawn rules.");
        }
    }

    private void tickStalk(Player target, long now) {
        Location entityLocation = locust.getLocation();
        double distance = horizontalDistance(entityLocation, target.getLocation());

        if (isFacingLocation(target, entityLocation, facingDot)) {
            lookAt(locust, target.getEyeLocation());
            return;
        }

        Location behind = desiredBehindLocation(target, Math.max(stalkStopDistance, 5.5));
        boolean moved = false;
        if (distance > stalkStopDistance) {
            moved = moveToward(behind, stalkSpeed, target.getEyeLocation());
        }

        if (!moved && distance > stalkStopDistance + 2.0 && now >= nextShadowRelocateAtMillis
                && !hasClearLineOfSight(target.getEyeLocation(), locustEyeLocation())) {
            Location shadow = findConcealedSpawn(target, shadowRelocateMin, shadowRelocateMax, Math.max(24, spawnAttempts / 2), false);
            if (shadow != null) {
                locust.teleport(shadow);
                lookAt(locust, target.getEyeLocation());
                nextShadowRelocateAtMillis = now + shadowRelocateCooldownSeconds * 1000L;
            }
        }
    }

    private void tickAmbush(Player target, long now) {
        double distance = horizontalDistance(locust.getLocation(), target.getLocation());
        if (distance > attackRange) {
            moveToward(target.getLocation(), ambushSpeed, target.getEyeLocation());
            return;
        }

        lookAt(locust, target.getEyeLocation());
        if (now < nextAttackAtMillis) {
            return;
        }

        nextAttackAtMillis = now + attackCooldownTicks * TICK_MILLIS;
        target.damage(attackDamage, locust);
    }

    private void beginFlee(Player target, long now) {
        state = State.FLEEING;
        fleeStartedAtMillis = now;
        fleeCover = findFleeCover(target);
        nextShadowRelocateAtMillis = now;
    }

    private void tickFlee(Player target, long now) {
        if (now - fleeStartedAtMillis > fleeTimeoutSeconds * 1000L) {
            getLogger().fine("Herobrine could not escape cleanly before the flee timeout and left the game.");
            dismissLocust(false, false);
            pendingReappearTargetId = null;
            return;
        }

        Location targetLocation = target.getLocation();
        Location destination = fleeCover;
        if (destination == null || destination.getWorld() == null || !destination.getWorld().equals(target.getWorld())) {
            Vector away = locust.getLocation().toVector().subtract(targetLocation.toVector());
            away.setY(0);
            if (away.lengthSquared() < 0.001) {
                away = new Vector(1, 0, 0);
            }
            away.normalize().multiply(24.0);
            destination = locust.getLocation().clone().add(away);
        }

        boolean moved = moveToward(destination, fleeSpeed, target.getEyeLocation());
        if (!moved && now >= nextShadowRelocateAtMillis) {
            Location replacement = findFleeCover(target);
            if (replacement != null) {
                fleeCover = replacement;
            }
            nextShadowRelocateAtMillis = now + 750L;
        }

        double distance = horizontalDistance(locust.getLocation(), targetLocation);
        boolean visible = hasClearLineOfSight(target.getEyeLocation(), locustEyeLocation());
        if (distance >= fleeSuccessDistance && !visible) {
            UUID escapedTarget = target.getUniqueId();
            dismissLocust(false, false);
            pendingReappearTargetId = escapedTarget;
            reappearAtMillis = now + reappearCooldownSeconds * 1000L;
        }
    }

    private void spawnLocust(Player target, Location spawn, boolean broadcast, boolean forced) {
        dismissLocust(false, false);

        Mannequin spawned = target.getWorld().spawn(spawn, Mannequin.class, mannequin -> {
            mannequin.setCustomNameVisible(false);
            mannequin.setCustomName(null);
            mannequin.setDescription(null);
            mannequin.setInvulnerable(true);
            mannequin.setSilent(true);
            mannequin.setPersistent(false);
            mannequin.setGlowing(false);
            mannequin.setGravity(true);
            mannequin.setImmovable(false);
            mannequin.getPersistentDataContainer().set(locustMarkerKey, PersistentDataType.BYTE, (byte) 1);

            if (mannequin.getAttribute(Attribute.MAX_HEALTH) != null) {
                mannequin.getAttribute(Attribute.MAX_HEALTH).setBaseValue(40.0);
                mannequin.setHealth(40.0);
            }

            PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "Herobrine");
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(herobrineSkinUrl);
            profile.setTextures(textures);
            mannequin.setProfile(ResolvableProfile.resolvableProfile(profile));
        });

        locust = spawned;
        targetId = target.getUniqueId();
        pendingReappearTargetId = null;
        state = State.STALKING;
        fleeCover = null;
        nextAttackAtMillis = 0L;
        nextShadowRelocateAtMillis = 0L;
        spawnedByForceCommand = forced;
        lookAt(locust, target.getEyeLocation());

        if (broadcast) {
            Bukkit.broadcastMessage(spawnMessage);
        }
    }

    private void dismissLocust(boolean preserveTargetForCooldown, boolean preserveForceFlag) {
        UUID oldTarget = targetId;
        if (locust != null) {
            locust.remove();
        }
        locust = null;
        targetId = null;
        state = State.STALKING;
        fleeCover = null;
        nextAttackAtMillis = 0L;
        if (!preserveForceFlag) {
            spawnedByForceCommand = false;
        }
        if (preserveTargetForCooldown && oldTarget != null) {
            pendingReappearTargetId = oldTarget;
        }
    }

    private void resetDaytimeWorlds() {
        for (World world : Bukkit.getWorlds()) {
            if (!isNight(world)) {
                attemptedThisNight.remove(world.getUID());
                if (pendingReappearTargetId != null) {
                    Player pending = Bukkit.getPlayer(pendingReappearTargetId);
                    if (pending != null && pending.getWorld().equals(world) && !spawnedByForceCommand) {
                        pendingReappearTargetId = null;
                    }
                }
            }
        }
    }

    private boolean isNight(World world) {
        long time = world.getTime();
        return time >= 12542L && time <= 23460L;
    }

    private boolean isUsableTarget(Player player) {
        if (player == null || !player.isOnline() || player.isDead()) {
            return false;
        }
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    private boolean isIsolated(Player target) {
        double radiusSquared = isolationRadius * isolationRadius;
        Location targetLocation = target.getLocation();
        for (Player other : target.getWorld().getPlayers()) {
            if (other.getUniqueId().equals(target.getUniqueId()) || !other.isOnline()) {
                continue;
            }
            if (horizontalDistanceSquared(targetLocation, other.getLocation()) < radiusSquared) {
                return false;
            }
        }
        return true;
    }

    private Location findConcealedSpawn(Player target, int minAxis, int maxAxis, int attempts, boolean requireTargetNearest) {
        Location base = target.getLocation();
        World world = target.getWorld();

        for (int i = 0; i < attempts; i++) {
            int dx = randomAxisOffset(minAxis, maxAxis);
            int dz = randomAxisOffset(minAxis, maxAxis);
            double x = Math.floor(base.getX()) + dx + 0.5;
            double z = Math.floor(base.getZ()) + dz + 0.5;

            Location safe = findSafeStandingLocation(world, x, z, base.getBlockY());
            if (safe == null || !insideWorldBorder(safe)) {
                continue;
            }

            double targetDistanceSquared = horizontalDistanceSquared(safe, base);
            boolean tooCloseToOther = false;
            for (Player other : world.getPlayers()) {
                if (other.getUniqueId().equals(target.getUniqueId()) || !other.isOnline()) {
                    continue;
                }
                double otherDistanceSquared = horizontalDistanceSquared(safe, other.getLocation());
                if (otherDistanceSquared < isolationRadius * isolationRadius
                        || (requireTargetNearest && otherDistanceSquared <= targetDistanceSquared)) {
                    tooCloseToOther = true;
                    break;
                }
            }
            if (tooCloseToOther) {
                continue;
            }

            Location candidateEye = safe.clone().add(0, 1.62, 0);
            if (hasClearLineOfSight(target.getEyeLocation(), candidateEye)) {
                continue;
            }

            return safe;
        }
        return null;
    }

    private Location findFleeCover(Player target) {
        Location targetLocation = target.getLocation();
        Location entityLocation = locust == null ? targetLocation : locust.getLocation();
        Vector away = entityLocation.toVector().subtract(targetLocation.toVector());
        away.setY(0);
        if (away.lengthSquared() < 0.001) {
            away = target.getLocation().getDirection().setY(0).multiply(-1);
        }
        if (away.lengthSquared() < 0.001) {
            away = new Vector(1, 0, 0);
        }
        away.normalize();

        World world = target.getWorld();
        for (int i = 0; i < Math.max(36, spawnAttempts / 2); i++) {
            double distance = 20.0 + random.nextDouble() * 18.0;
            double side = (random.nextDouble() - 0.5) * 16.0;
            Vector perpendicular = new Vector(-away.getZ(), 0, away.getX()).multiply(side);
            Vector offset = away.clone().multiply(distance).add(perpendicular);
            double x = entityLocation.getX() + offset.getX();
            double z = entityLocation.getZ() + offset.getZ();
            Location safe = findSafeStandingLocation(world, x, z, entityLocation.getBlockY());
            if (safe == null || !insideWorldBorder(safe)) {
                continue;
            }
            if (hasClearLineOfSight(target.getEyeLocation(), safe.clone().add(0, 1.62, 0))) {
                continue;
            }
            return safe;
        }
        return null;
    }

    private Location desiredBehindLocation(Player target, double distance) {
        Location location = target.getLocation();
        Vector facing = location.getDirection().setY(0);
        if (facing.lengthSquared() < 0.001) {
            facing = new Vector(0, 0, 1);
        }
        facing.normalize().multiply(-distance);
        Location desired = location.clone().add(facing);
        Location safe = findSafeStandingLocation(target.getWorld(), desired.getX(), desired.getZ(), location.getBlockY());
        return safe != null ? safe : location;
    }

    private boolean moveToward(Location desired, double speed, Location lookAt) {
        if (locust == null || desired == null || desired.getWorld() == null || !desired.getWorld().equals(locust.getWorld())) {
            return false;
        }

        Location current = locust.getLocation();
        Vector delta = desired.toVector().subtract(current.toVector());
        delta.setY(0);
        double horizontal = delta.length();
        if (horizontal < 0.05) {
            lookAt(locust, lookAt);
            return true;
        }

        delta.normalize().multiply(Math.min(speed, horizontal));
        double nextX = current.getX() + delta.getX();
        double nextZ = current.getZ() + delta.getZ();
        Location next = findSafeStandingLocation(current.getWorld(), nextX, nextZ, current.getBlockY());
        if (next == null) {
            return false;
        }

        double yDelta = Math.abs(next.getY() - current.getY());
        if (yDelta > 1.25) {
            return false;
        }

        next.setYaw(current.getYaw());
        next.setPitch(current.getPitch());
        boolean teleported = locust.teleport(next);
        if (teleported) {
            lookAt(locust, lookAt);
        }
        return teleported;
    }

    private Location findSafeStandingLocation(World world, double x, double z, int referenceY) {
        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 3;
        int center = Math.max(minY, Math.min(maxY, referenceY));

        for (int offset = 0; offset <= 12; offset++) {
            int up = center + offset;
            if (up <= maxY) {
                Location found = standingLocationAt(world, x, z, up);
                if (found != null) {
                    return found;
                }
            }
            if (offset == 0) {
                continue;
            }
            int down = center - offset;
            if (down >= minY) {
                Location found = standingLocationAt(world, x, z, down);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Location standingLocationAt(World world, double x, double z, int feetY) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        Material below = world.getBlockAt(blockX, feetY - 1, blockZ).getType();
        Material feet = world.getBlockAt(blockX, feetY, blockZ).getType();
        Material head = world.getBlockAt(blockX, feetY + 1, blockZ).getType();

        if (!below.isSolid() || !feet.isAir() || !head.isAir()) {
            return null;
        }
        return new Location(world, blockX + 0.5, feetY, blockZ + 0.5);
    }

    private boolean insideWorldBorder(Location location) {
        WorldBorder border = location.getWorld().getWorldBorder();
        Location center = border.getCenter();
        double radius = border.getSize() / 2.0 - 2.0;
        return Math.abs(location.getX() - center.getX()) <= radius
                && Math.abs(location.getZ() - center.getZ()) <= radius;
    }

    private boolean isLookingDirectlyAt(Player player, Location entityFeet) {
        Location entityEye = entityFeet.clone().add(0, 1.62, 0);
        Location eye = player.getEyeLocation();
        Vector toEntity = entityEye.toVector().subtract(eye.toVector());
        double distance = toEntity.length();
        if (distance < 0.001) {
            return true;
        }
        double dot = eye.getDirection().normalize().dot(toEntity.normalize());
        return dot >= directLookDot && hasClearLineOfSight(eye, entityEye);
    }

    private boolean isFacingLocation(Player player, Location location, double threshold) {
        Vector horizontalLook = player.getEyeLocation().getDirection().setY(0);
        Vector toLocation = location.toVector().subtract(player.getLocation().toVector()).setY(0);
        if (horizontalLook.lengthSquared() < 0.001 || toLocation.lengthSquared() < 0.001) {
            return false;
        }
        return horizontalLook.normalize().dot(toLocation.normalize()) >= threshold;
    }

    private boolean hasClearLineOfSight(Location start, Location end) {
        if (start.getWorld() == null || end.getWorld() == null || !start.getWorld().equals(end.getWorld())) {
            return false;
        }
        Vector direction = end.toVector().subtract(start.toVector());
        double distance = direction.length();
        if (distance < 0.05) {
            return true;
        }
        RayTraceResult hit = start.getWorld().rayTraceBlocks(start, direction.normalize(), distance,
                FluidCollisionMode.NEVER, true);
        return hit == null;
    }

    private Location locustEyeLocation() {
        return locust.getLocation().clone().add(0, 1.62, 0);
    }

    private void lookAt(Mannequin mannequin, Location target) {
        if (mannequin == null || target == null || target.getWorld() == null || !target.getWorld().equals(mannequin.getWorld())) {
            return;
        }
        Location from = mannequin.getLocation();
        Vector direction = target.toVector().subtract(from.toVector());
        if (direction.lengthSquared() < 0.001) {
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        double xz = Math.sqrt(direction.getX() * direction.getX() + direction.getZ() * direction.getZ());
        float pitch = (float) Math.toDegrees(Math.atan2(-direction.getY(), xz));
        mannequin.setRotation(yaw, pitch);
    }

    private int randomAxisOffset(int min, int max) {
        int magnitude = min + random.nextInt(Math.max(1, max - min + 1));
        return random.nextBoolean() ? magnitude : -magnitude;
    }

    private boolean isTargetBusy(Player player, long now) {
        return containerBusy.contains(player.getUniqueId())
                || interactionBusyUntil.getOrDefault(player.getUniqueId(), 0L) > now;
    }

    private void pruneBusyWindows(long now) {
        interactionBusyUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private void markInteractionBusy(Player player) {
        interactionBusyUntil.put(player.getUniqueId(), System.currentTimeMillis() + interactionBusyWindowMillis);
    }

    private void removeStaleLocustEntities() {
        if (locustMarkerKey == null) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                Byte marker = entity.getPersistentDataContainer().get(locustMarkerKey, PersistentDataType.BYTE);
                if (marker != null && marker == (byte) 1) {
                    entity.remove();
                }
            }
        }
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.sqrt(horizontalDistanceSquared(a, b));
    }

    private static double horizontalDistanceSquared(Location a, Location b) {
        if (a.getWorld() == null || b.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
            return Double.MAX_VALUE;
        }
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            containerBusy.add(player.getUniqueId());
            markInteractionBusy(player);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            containerBusy.remove(player.getUniqueId());
            markInteractionBusy(player);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            markInteractionBusy(player);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            markInteractionBusy(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        containerBusy.remove(id);
        interactionBusyUntil.remove(id);
        if (id.equals(targetId) || id.equals(pendingReappearTargetId)) {
            dismissLocust(false, false);
            pendingReappearTargetId = null;
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (id.equals(targetId) || id.equals(pendingReappearTargetId)) {
            dismissLocust(false, false);
            pendingReappearTargetId = null;
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (event.getPlayer().getUniqueId().equals(targetId)) {
            Bukkit.getScheduler().runTask(this, () -> {
                dismissLocust(false, false);
                pendingReappearTargetId = null;
            });
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                loadSettings();
                sender.sendMessage("EaglerLocust configuration reloaded.");
                return true;
            }
            case "dismiss" -> {
                dismissLocust(false, false);
                pendingReappearTargetId = null;
                sender.sendMessage("EaglerLocust dismissed. Natural spawning can occur on the next eligible night.");
                return true;
            }
            case "force" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /locust force <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (!isUsableTarget(target)) {
                    sender.sendMessage("That player is not online in Survival/Adventure mode.");
                    return true;
                }
                Location spawn = findConcealedSpawn(target, spawnMinAxis, spawnMaxAxis, Math.max(spawnAttempts, 120), false);
                if (spawn == null) {
                    sender.sendMessage("No safe concealed spawn point was found around that player.");
                    return true;
                }
                spawnLocust(target, spawn, true, true);
                sender.sendMessage("Forced EaglerLocust target: " + target.getName());
                return true;
            }
            default -> {
                sender.sendMessage("Usage: /locust <status|force <player>|dismiss|reload>");
                return true;
            }
        }
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage("EaglerLocustFall26 status:");
        sender.sendMessage("  active: " + (locust != null && locust.isValid()));
        if (targetId != null) {
            Player target = Bukkit.getPlayer(targetId);
            sender.sendMessage("  target: " + (target == null ? targetId : target.getName()));
            sender.sendMessage("  state: " + state.name().toLowerCase(Locale.ROOT));
        }
        if (pendingReappearTargetId != null) {
            long seconds = Math.max(0L, (reappearAtMillis - System.currentTimeMillis() + 999L) / 1000L);
            Player pending = Bukkit.getPlayer(pendingReappearTargetId);
            sender.sendMessage("  cooldown target: " + (pending == null ? pendingReappearTargetId : pending.getName()));
            sender.sendMessage("  reappears in: " + seconds + "s");
        }
        sender.sendMessage("  isolation radius: " + isolationRadius + " blocks");
        sender.sendMessage("  damage: " + attackDamage + " HP (" + (attackDamage / 2.0) + " hearts before armor)");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return partial(args[0], List.of("status", "force", "dismiss", "reload"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("force")) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return partial(args[1], names);
        }
        return List.of();
    }

    private List<String> partial(String prefix, List<String> values) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> results = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                results.add(value);
            }
        }
        return results;
    }
}
