package com.example.simpleac;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * SimpleAC (Fabric, server-side). Put in the server's mods folder with Fabric API.
 * Checks: Speed, Fly, Reach, KillAura, AutoCrystal, AutoTotem, AutoClicker.
 */
public class SimpleAC implements ModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("SimpleAC");

    // ---------- Config (config/simpleac.properties) ----------

    private static class Config {
        final Properties p = new Properties();
        Set<String> staff = new HashSet<>();

        static Config load() {
            Config c = new Config();
            Path file = FabricLoader.getInstance().getConfigDir().resolve("simpleac.properties");
            if (!Files.exists(file)) {
                String def = """
                        # Comma-separated player names: they get alerts and are exempt from checks
                        staff=
                        alert-threshold=3
                        kick-threshold=15
                        max-speed=0.50
                        max-air-ticks=10
                        max-reach=3.6
                        max-cps=20
                        max-attack-angle=60
                        min-target-switch-ms=60
                        min-crystal-delay-ms=60
                        max-crystal-breaks-per-sec=12
                        min-totem-reaction-ticks=2
                        """;
                try (OutputStream out = Files.newOutputStream(file)) {
                    out.write(def.getBytes());
                } catch (IOException e) {
                    LOG.warn("Could not write default config", e);
                }
            }
            try (InputStream in = Files.newInputStream(file)) {
                c.p.load(in);
            } catch (IOException e) {
                LOG.warn("Could not read config, using defaults", e);
            }
            for (String s : c.p.getProperty("staff", "").split(",")) {
                if (!s.isBlank()) c.staff.add(s.trim().toLowerCase(Locale.ROOT));
            }
            return c;
        }

        double d(String k, double def) {
            try { return Double.parseDouble(p.getProperty(k, String.valueOf(def)).trim()); }
            catch (NumberFormatException e) { return def; }
        }

        int i(String k, int def) { return (int) d(k, def); }
    }

    // ---------- Per-player data ----------

    private static class PlayerData {
        final Map<String, Double> vl = new HashMap<>();
        final Deque<Long> attacks = new ArrayDeque<>();
        final Deque<Long> crystalBreaks = new ArrayDeque<>();
        boolean hasPrev = false;
        double px, py, pz;
        double[] lastSafe;
        int airTicks = 0;
        int exempt = 40;
        UUID lastTarget;
        long lastHitMs = 0;
        long lastPlaceMs = 0;
        int prevTotems = 0;
        boolean prevOffhandTotem = false;
        int popTicks = -1;
    }

    private final Map<UUID, PlayerData> data = new HashMap<>();
    private Config cfg;
    private MinecraftServer server;
    private int tickCounter = 0;

    private PlayerData get(ServerPlayer p) {
        return data.computeIfAbsent(p.getUUID(), k -> new PlayerData());
    }

    @Override
    public void onInitialize() {
        cfg = Config.load();

        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            PlayerData d = get(handler.getPlayer());
            d.exempt = 40;
            d.hasPrev = false;
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> data.remove(handler.getPlayer().getUUID()));
        AttackEntityCallback.EVENT.register(this::onAttack);
        UseBlockCallback.EVENT.register(this::onUseBlock);

        LOG.info("SimpleAC loaded.");
    }

    // ---------- Flagging ----------

    private boolean isStaff(ServerPlayer p) {
        return cfg.staff.contains(p.getName().getString().toLowerCase(Locale.ROOT));
    }

    private void flag(ServerPlayer p, String check, String info) {
        PlayerData d = get(p);
        double vl = d.vl.merge(check, 1.0, Double::sum);

        if (vl >= cfg.d("alert-threshold", 3)) {
            String msg = "[SimpleAC] " + p.getName().getString() + " failed " + check
                    + " (vl " + (int) vl + ") " + info;
            LOG.info(msg);
            if (server != null) {
                for (ServerPlayer s : server.getPlayerList().getPlayers()) {
                    if (isStaff(s)) s.sendSystemMessage(Component.literal(msg));
                }
            }
        }
        if (vl >= cfg.d("kick-threshold", 15)) {
            d.vl.put(check, 0.0);
            p.connection.disconnect(Component.literal("Kicked by SimpleAC: " + check));
        }
    }

    private void setback(ServerPlayer p, PlayerData d) {
        if (d.lastSafe == null) return;
        d.exempt = 3;
        p.connection.teleport(d.lastSafe[0], d.lastSafe[1], d.lastSafe[2], p.getYRot(), p.getXRot());
    }

    // ---------- Tick: Speed, Fly, AutoTotem ----------

    private void onTick(MinecraftServer s) {
        tickCounter++;
        if (tickCounter % 20 == 0) {
            for (PlayerData d : data.values()) d.vl.replaceAll((k, v) -> Math.max(0, v - 1));
        }
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            try {
                check(p);
            } catch (Exception e) {
                LOG.warn("Check error for " + p.getName().getString(), e);
            }
        }
    }

    private void check(ServerPlayer p) {
        PlayerData d = get(p);
        double x = p.getX(), y = p.getY(), z = p.getZ();

        if (!d.hasPrev) {
            d.px = x; d.py = y; d.pz = z;
            d.lastSafe = new double[]{x, y, z};
            d.prevTotems = countTotems(p);
            d.prevOffhandTotem = p.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
            d.hasPrev = true;
            return;
        }

        double dx = x - d.px, dy = y - d.py, dz = z - d.pz;
        d.px = x; d.py = y; d.pz = z;

        checkTotem(p, d);

        if (p.hurtTime > 0) d.exempt = Math.max(d.exempt, 20); // knockback

        if (isStaff(p) || isExempt(p) || nearSpecial(p) || d.exempt > 0) {
            if (d.exempt > 0) d.exempt--;
            d.airTicks = 0;
            d.lastSafe = new double[]{x, y, z};
            return;
        }

        // Big jump = teleport/respawn/portal, not movement
        if (dx * dx + dy * dy + dz * dz > 25) {
            d.exempt = 20;
            d.lastSafe = new double[]{x, y, z};
            return;
        }

        if (solidBelow(p, x, y, z)) {
            d.airTicks = 0;
            d.lastSafe = new double[]{x, y, z};
        } else {
            d.airTicks++;
        }

        // SPEED
        double h = Math.sqrt(dx * dx + dz * dz);
        double max = cfg.d("max-speed", 0.50);
        MobEffectInstance speed = p.getEffect(MobEffects.SPEED);
        if (speed != null) max += 0.07 * (speed.getAmplifier() + 1);
        if (h > max) {
            flag(p, "Speed", String.format("%.3f > %.3f", h, max));
            setback(p, d);
            return;
        }

        // FLY (hovering / rising in the air too long)
        if (d.airTicks > cfg.i("max-air-ticks", 10) && dy >= -0.02 && !standingOnEntity(p)) {
            flag(p, "Fly", "air=" + d.airTicks + " dy=" + String.format("%.3f", dy));
            setback(p, d);
        }
    }

    private boolean isExempt(ServerPlayer p) {
        return p.isCreative() || p.isSpectator() || p.getAbilities().mayfly
                || p.isFallFlying() || p.isPassenger() || p.isAutoSpinAttack()
                || p.hasEffect(MobEffects.LEVITATION) || p.hasEffect(MobEffects.SLOW_FALLING);
    }

    private boolean solidBelow(ServerPlayer p, double x, double y, double z) {
        Level level = p.level();
        double[] offs = {-0.3, 0, 0.3};
        for (double ox : offs) {
            for (double oz : offs) {
                BlockPos pos = BlockPos.containing(x + ox, y - 0.3, z + oz);
                BlockState st = level.getBlockState(pos);
                if (!st.getCollisionShape(level, pos).isEmpty()) return true;
            }
        }
        return false;
    }

    private boolean nearSpecial(ServerPlayer p) {
        Level level = p.level();
        for (int oy = -1; oy <= 1; oy++) {
            BlockState st = level.getBlockState(BlockPos.containing(p.getX(), p.getY() + oy, p.getZ()));
            if (!st.getFluidState().isEmpty()
                    || st.is(BlockTags.CLIMBABLE)
                    || st.is(BlockTags.ICE)
                    || st.is(Blocks.COBWEB)
                    || st.is(Blocks.SLIME_BLOCK)
                    || st.is(Blocks.HONEY_BLOCK)
                    || st.is(Blocks.POWDER_SNOW)
                    || st.is(Blocks.BUBBLE_COLUMN)
                    || st.is(Blocks.SCAFFOLDING)
                    || st.is(Blocks.SWEET_BERRY_BUSH)) {
                return true;
            }
        }
        return false;
    }

    private boolean standingOnEntity(ServerPlayer p) {
        AABB box = p.getBoundingBox().inflate(0.5, 0.5, 0.5);
        return !p.level().getEntities(p, box, e -> !(e instanceof Player)).isEmpty();
    }

    // ---------- AutoTotem ----------

    private int countTotems(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) n++;
        }
        if (p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) n++;
        return n;
    }

    private void checkTotem(ServerPlayer p, PlayerData d) {
        int count = countTotems(p);
        boolean offhand = p.getOffhandItem().is(Items.TOTEM_OF_UNDYING);

        // A pop = fewer totems AND the fresh Regeneration II the totem just applied
        MobEffectInstance regen = p.getEffect(MobEffects.REGENERATION);
        boolean fresh = regen != null && regen.getAmplifier() == 1 && regen.getDuration() >= 890;
        if (count < d.prevTotems && fresh) {
            d.popTicks = 0;
        } else if (d.popTicks >= 0) {
            d.popTicks++;
            if (d.popTicks > 20) d.popTicks = -1;
        }

        // Re-equipped into the offhand faster than a human can react
        if (offhand && !d.prevOffhandTotem && d.popTicks >= 0
                && d.popTicks <= cfg.i("min-totem-reaction-ticks", 2) && !isStaff(p)) {
            flag(p, "AutoTotem", "re-equipped " + (d.popTicks * 50) + "ms after pop");
        }

        d.prevTotems = count;
        d.prevOffhandTotem = offhand;
    }

    // ---------- AutoCrystal: record placements ----------

    private InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer sp
                && player.getItemInHand(hand).is(Items.END_CRYSTAL)) {
            get(sp).lastPlaceMs = System.currentTimeMillis();
        }
        return InteractionResult.PASS;
    }

    // ---------- Attacks: Reach, KillAura, AutoCrystal, AutoClicker ----------

    private InteractionResult onAttack(Player player, Level level, InteractionHand hand,
                                       Entity target, EntityHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (isStaff(sp) || sp.isCreative() || sp.isSpectator()) return InteractionResult.PASS;

        PlayerData d = get(sp);
        long now = System.currentTimeMillis();

        // AUTOCLICKER (attack rate)
        d.attacks.addLast(now);
        while (!d.attacks.isEmpty() && now - d.attacks.peekFirst() > 1000) d.attacks.pollFirst();
        if (d.attacks.size() > cfg.i("max-cps", 20)) {
            flag(sp, "AutoClicker", "cps=" + d.attacks.size());
        }

        // REACH
        Vec3 eye = sp.getEyePosition();
        double dist = distanceToBox(eye, target.getBoundingBox());
        double maxReach = cfg.d("max-reach", 3.6);
        if (dist > maxReach) {
            flag(sp, "Reach", String.format("%.2f > %.2f", dist, maxReach));
            return InteractionResult.FAIL;
        }

        // KILLAURA: hitting something not under the crosshair
        Vec3 to = target.getBoundingBox().getCenter().subtract(eye);
        if (to.lengthSqr() > 4.0) {
            double dot = sp.getLookAngle().normalize().dot(to.normalize());
            double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
            double maxAngle = cfg.d("max-attack-angle", 60);
            if (angle > maxAngle) {
                flag(sp, "KillAura", String.format("angle %.0f > %.0f", angle, maxAngle));
                return InteractionResult.FAIL;
            }
        }

        if (target instanceof EndCrystal) {
            // AUTOCRYSTAL: break right after placing, or at inhuman rates
            long since = now - d.lastPlaceMs;
            if (since < cfg.d("min-crystal-delay-ms", 60)) {
                flag(sp, "AutoCrystal", "break " + since + "ms after place");
            }
            d.crystalBreaks.addLast(now);
            while (!d.crystalBreaks.isEmpty() && now - d.crystalBreaks.peekFirst() > 1000) {
                d.crystalBreaks.pollFirst();
            }
            if (d.crystalBreaks.size() > cfg.i("max-crystal-breaks-per-sec", 12)) {
                flag(sp, "AutoCrystal", "breaks/s=" + d.crystalBreaks.size());
            }
        } else {
            // KILLAURA (multi): different targets hit almost simultaneously
            if (d.lastTarget != null && !d.lastTarget.equals(target.getUUID())
                    && now - d.lastHitMs < cfg.d("min-target-switch-ms", 60)) {
                flag(sp, "KillAura", "multi-target in " + (now - d.lastHitMs) + "ms");
                return InteractionResult.FAIL;
            }
            d.lastTarget = target.getUUID();
            d.lastHitMs = now;
        }

        return InteractionResult.PASS;
    }

    private double distanceToBox(Vec3 p, AABB b) {
        double cx = Math.max(b.minX, Math.min(p.x, b.maxX));
        double cy = Math.max(b.minY, Math.min(p.y, b.maxY));
        double cz = Math.max(b.minZ, Math.min(p.z, b.maxZ));
        return p.distanceTo(new Vec3(cx, cy, cz));
    }
}
