package io.blockcompanion.client.fx;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.ClientConfig;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.progress.ProgressTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The satisfying part, kept quiet: a few sparkles when a ghost is filled correctly (no sound, so building a lot stays
 * calm), a low note for a wrong block, a toast and sparkles along a finished level, and fireworks with a summary
 * when the whole schematic is done. Sounds play in the Blocks category, so the game's volume sliders apply. Every part
 * can be switched off in the config.
 */
public final class Effects {
    /** Placing a lot of wrong blocks plays the low note at most this often, so it doesn't get tiring. */
    private static final long SOUND_GAP_MS = 3000;
    /** Placements further away than this are someone else's: sparkles, but no sound. */
    private static final double SOUND_RANGE = 12;
    private static final SystemToast.SystemToastId LAYER_TOAST = new SystemToast.SystemToastId(4000L);
    private static final SystemToast.SystemToastId FINISH_TOAST = new SystemToast.SystemToastId(8000L);

    private final RandomSource random = RandomSource.create();
    private long lastWrongNote;
    /** Delayed firework bursts: tick countdown and position. */
    private final List<double[]> bursts = new ArrayList<>();

    private static ClientConfig config() {
        return BlockCompanionClient.config();
    }

    /** A cell became correct through a live change. */
    public void correct(ClientLevel level, int x, int y, int z) {
        double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
        if (config().particles) {
            for (int i = 0; i < 6; i++) {
                double dx = random.nextGaussian() * 0.04, dy = 0.03 + random.nextDouble() * 0.05, dz = random.nextGaussian() * 0.04;
                spawn(level, ParticleTypes.END_ROD, cx + random.nextGaussian() * 0.25, cy + random.nextGaussian() * 0.25,
                        cz + random.nextGaussian() * 0.25, dx, dy, dz);
            }
        }
    }

    /** A cell became wrong through a live change. */
    public void wrong(ClientLevel level, int x, int y, int z) {
        Player player = Minecraft.getInstance().player;
        if (player == null || player.distanceToSqr(x + 0.5, y + 0.5, z + 0.5) > SOUND_RANGE * SOUND_RANGE) return;
        long now = System.currentTimeMillis();
        if (now - lastWrongNote < SOUND_GAP_MS) return;
        lastWrongNote = now;
        play(level, SoundEvents.NOTE_BLOCK_BASS.value(), x + 0.5, y + 0.5, z + 0.5, 0.35f, 0.6f);
    }

    /** A level (0 = bottom) of the placed box was finished. */
    public void levelDone(ClientLevel level, Box box, int levelIndex) {
        if (!config().layerCelebration) return;
        int y = box.minY() + levelIndex;
        toast(LAYER_TOAST, Component.literal("Layer " + (levelIndex + 1) + " done"), Component.literal("Y " + y + " is all correct"));
        Player player = Minecraft.getInstance().player;
        if (player != null) play(level, SoundEvents.PLAYER_LEVELUP, player.getX(), player.getY(), player.getZ(), 0.3f, 1.4f);
        if (!config().particles) return;
        // Sparkles along the level's outline, spread evenly, at most ~96.
        int perimeter = 2 * (box.sizeX() + box.sizeZ());
        int step = Math.max(1, perimeter / 96);
        for (int i = 0; i < perimeter; i += step) {
            double px, pz;
            int sx = box.sizeX(), sz = box.sizeZ();
            if (i < sx) {
                px = box.minX() + i;
                pz = box.minZ();
            } else if (i < sx + sz) {
                px = box.maxX() + 1;
                pz = box.minZ() + (i - sx);
            } else if (i < 2 * sx + sz) {
                px = box.maxX() + 1 - (i - sx - sz);
                pz = box.maxZ() + 1;
            } else {
                px = box.minX();
                pz = box.maxZ() + 1 - (i - 2 * sx - sz);
            }
            spawn(level, ParticleTypes.HAPPY_VILLAGER, px, y + 1.1, pz, 0, 0.02, 0);
        }
    }

    /** The whole schematic is done. */
    public void finished(ClientLevel level, Box box, ProgressTracker.Stats stats, long total) {
        if (!config().finishCelebration) return;
        Minecraft mc = Minecraft.getInstance();
        long seconds = stats.buildMillis / 1000;
        String time = seconds >= 3600 ? String.format(Locale.ROOT, "%dh %02dm", seconds / 3600, (seconds / 60) % 60)
                : String.format(Locale.ROOT, "%dm %02ds", seconds / 60, seconds % 60);
        String accuracy = String.format(Locale.ROOT, "%.0f%%", stats.accuracy() * 100);
        toast(FINISH_TOAST, Component.literal("Schematic finished!"),
                Component.literal(String.format(Locale.ROOT, "%,d blocks · %s · %s accurate", total, time, accuracy)));
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("Schematic finished! ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(String.format(Locale.ROOT, "%,d blocks in the build, %,d placed with BlockCompanion watching, building time %s, accuracy %s",
                            total, stats.placed, time, accuracy)).withStyle(ChatFormatting.WHITE)));
            play(level, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, mc.player.getX(), mc.player.getY(), mc.player.getZ(), 0.5f, 1f);
        }
        if (!config().particles) return;
        double cx = (box.minX() + box.maxX() + 1) / 2.0, top = box.maxY() + 2.5, cz = (box.minZ() + box.maxZ() + 1) / 2.0;
        bursts.add(new double[]{0, cx, top, cz});
        bursts.add(new double[]{10, box.minX(), top - 1, box.minZ()});
        bursts.add(new double[]{16, box.maxX() + 1, top - 1, box.maxZ() + 1});
        bursts.add(new double[]{22, box.minX(), top - 1, box.maxZ() + 1});
        bursts.add(new double[]{28, box.maxX() + 1, top - 1, box.minZ()});
        bursts.add(new double[]{38, cx, top + 1, cz});
    }

    /** Runs delayed bursts; call every client tick. */
    public void tick(ClientLevel level) {
        if (bursts.isEmpty() || level == null) return;
        for (int i = bursts.size() - 1; i >= 0; i--) {
            double[] b = bursts.get(i);
            if (--b[0] > 0) continue;
            bursts.remove(i);
            burst(level, b[1], b[2], b[3]);
        }
    }

    private void burst(ClientLevel level, double x, double y, double z) {
        for (int i = 0; i < 60; i++) {
            double theta = random.nextDouble() * Math.PI * 2, phi = Math.acos(2 * random.nextDouble() - 1);
            double speed = 0.25 + random.nextDouble() * 0.1;
            spawn(level, ParticleTypes.FIREWORK, x, y, z, Math.sin(phi) * Math.cos(theta) * speed, Math.cos(phi) * speed,
                    Math.sin(phi) * Math.sin(theta) * speed);
        }
        for (int i = 0; i < 20; i++) {
            spawn(level, ParticleTypes.TOTEM_OF_UNDYING, x, y, z, random.nextGaussian() * 0.3, random.nextDouble() * 0.4, random.nextGaussian() * 0.3);
        }
        play(level, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, x, y, z, 0.6f, 1f);
        play(level, SoundEvents.FIREWORK_ROCKET_TWINKLE, x, y, z, 0.4f, 1f);
    }

    public void clear() {
        bursts.clear();
    }

    private void spawn(ClientLevel level, ParticleOptions type, double x, double y, double z, double dx, double dy, double dz) {
        level.addParticle(type, x, y, z, dx, dy, dz);
    }

    private void play(ClientLevel level, SoundEvent sound, double x, double y, double z, float volume, float pitch) {
        if (!config().sounds) return;
        level.playLocalSound(x, y, z, sound, SoundSource.BLOCKS, volume, pitch, false);
    }

    private static void toast(SystemToast.SystemToastId id, Component title, Component message) {
        SystemToast.addOrUpdate(Minecraft.getInstance().gui.toastManager(), id, title, message);
    }
}
