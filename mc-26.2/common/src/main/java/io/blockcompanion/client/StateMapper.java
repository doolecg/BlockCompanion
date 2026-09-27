package io.blockcompanion.client;

import io.blockcompanion.core.model.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Converts between the core's block states and Minecraft's. Both sides are canonical objects, so each conversion is
 * worked out once and cached. Main thread only.
 */
public final class StateMapper {
    private static final Map<BlockState, net.minecraft.world.level.block.state.BlockState> TO_MC = new HashMap<>();
    private static final Map<net.minecraft.world.level.block.state.BlockState, BlockState> TO_CORE = new IdentityHashMap<>();
    /** Marks core states with no Minecraft block (a mod that isn't installed, a renamed block). */
    private static final net.minecraft.world.level.block.state.BlockState UNKNOWN = null;

    private StateMapper() {
    }

    /** The Minecraft state for a core state, or null if its block doesn't exist in this game. */
    public static net.minecraft.world.level.block.state.BlockState toMc(BlockState s) {
        if (TO_MC.containsKey(s)) return TO_MC.get(s);
        net.minecraft.world.level.block.state.BlockState mc = resolve(s);
        TO_MC.put(s, mc);
        return mc;
    }

    private static net.minecraft.world.level.block.state.BlockState resolve(BlockState s) {
        Identifier id = Identifier.tryParse(s.name());
        if (id == null) return UNKNOWN;
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
        if (block.isEmpty()) return UNKNOWN;
        net.minecraft.world.level.block.state.BlockState mc = block.get().defaultBlockState();
        for (var e : s.properties().entrySet()) {
            Property<?> prop = block.get().getStateDefinition().getProperty(e.getKey());
            if (prop != null) mc = with(mc, prop, e.getValue());
        }
        return mc;
    }

    private static <T extends Comparable<T>> net.minecraft.world.level.block.state.BlockState with(
            net.minecraft.world.level.block.state.BlockState state, Property<T> prop, String value) {
        return prop.getValue(value).map(v -> state.setValue(prop, v)).orElse(state);
    }

    /** The core state for a Minecraft state. */
    public static BlockState toCore(net.minecraft.world.level.block.state.BlockState mc) {
        BlockState s = TO_CORE.get(mc);
        if (s != null) return s;
        s = convert(mc);
        TO_CORE.put(mc, s);
        return s;
    }

    /** The core state for a Minecraft state without touching the cache: safe off the main thread (the server thread). */
    public static BlockState toCoreUncached(net.minecraft.world.level.block.state.BlockState mc) {
        return convert(mc);
    }

    private static BlockState convert(net.minecraft.world.level.block.state.BlockState mc) {
        String name = BuiltInRegistries.BLOCK.getKey(mc.getBlock()).toString();
        if (mc.getProperties().isEmpty()) return BlockState.of(name);
        Map<String, String> props = new TreeMap<>();
        mc.getValues().forEach(v -> props.put(v.property().getName(), v.valueName()));
        return BlockState.of(name, props);
    }
}
