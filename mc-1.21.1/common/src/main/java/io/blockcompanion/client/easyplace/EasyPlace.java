package io.blockcompanion.client.easyplace;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.LoadedPlacement;
import io.blockcompanion.client.chests.ChestTracker;
import io.blockcompanion.client.StateMapper;
import io.blockcompanion.core.compare.Compare;
import io.blockcompanion.core.easyplace.AutoPlacePlanner;
import io.blockcompanion.core.easyplace.CellRay;
import io.blockcompanion.core.easyplace.EasyPlacePlanner;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.placement.Layers;
import io.blockcompanion.core.placement.Placement;
import io.blockcompanion.core.sync.Features;
import io.blockcompanion.core.sync.SyncClient;
import io.blockcompanion.mixin.BlockItemInvoker;
import io.blockcompanion.network.ClientSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Easy place, as in Litematica: looking at a ghost block (nearer than any real block in the way) and right-clicking
 * with its item places exactly that block in that cell, even in mid-air. It sends an ordinary "use item on block"
 * aimed at the cell itself, with the face, hit point and a brief player rotation chosen so vanilla's own placement rule
 * gives the wanted state; so it works in survival on vanilla and Paper servers. The click is planned by
 * {@link EasyPlacePlanner} and each candidate is checked against the real block's placement rule before it is sent.
 *
 * <p>With several placements loaded, the nearest ghost along the look wins. Without the block in hand or inventory, and
 * on a server that allows building from linked chests, it asks the server for a stack from the player's linked chests
 * and places the block as soon as it arrives.
 *
 * <p>Middle click on a ghost picks its item. A BlockCompanion server can switch easy place off
 * ({@link Features#easyPlaceAllowed()}). Milestone 3's printer can reuse {@link #place}.
 *
 * <p>Auto mode ({@link #tickAuto}) places the missing blocks within reach by itself, at a set pace, through the same
 * click; {@link AutoPlacePlanner} picks the cells. Neither mode clicks the same cell again straight away, a cell that
 * holds a block, or the upper half of a door or bed; and a held right-click doesn't keep toggling doors or opening
 * chests inside a shown schematic.
 */
public final class EasyPlace {
    /** Simulated clicks per placement at most. */
    private static final int MAX_SIMULATIONS = 600;

    /** A ghost cell the player looks at: where, which face the look enters, the hit point, the wanted state, and whose it is. */
    public record Target(BlockPos pos, Direction face, Vec3 hit, double distance, io.blockcompanion.core.model.BlockState want, BlockState wantMc,
                         LoadedPlacement owner) {
        public Target(BlockPos pos, Direction face, Vec3 hit, double distance, io.blockcompanion.core.model.BlockState want, BlockState wantMc) {
            this(pos, face, hit, distance, want, wantMc, null);
        }

        Target withOwner(LoadedPlacement lp) {
            return new Target(pos, face, hit, distance, want, wantMc, lp);
        }
    }

    /** Waiting for a restock from the linked chests: the ghost to fill, the item, and until when. */
    private record Pending(Target target, Item item, long until) {
    }

    private Target target;
    private Pending pending;
    private boolean blockedByServer;
    private String lastHint;
    private long hintUntil;
    /** Client ticks seen: the clock for cooldowns and auto mode's pace. */
    private long ticks;
    /** Cells clicked a moment ago, left alone until the server's answer is in. */
    private final AutoPlacePlanner.Cooldowns cooldowns = new AutoPlacePlanner.Cooldowns();
    private long lastUseTick = -1000;
    private long nextAutoTick;
    private String autoStatus;

    public Target target() {
        return target;
    }

    /** A short hint for the HUD ("Needs Oak Planks"), or null. */
    public String hint() {
        return System.currentTimeMillis() < hintUntil ? lastHint : null;
    }

    private void hint(String text) {
        lastHint = text;
        hintUntil = System.currentTimeMillis() + 1500;
    }

    /** True while the server (if it runs BlockCompanion) allows easy place. */
    public static boolean serverAllows() {
        SyncClient c = ClientSync.client();
        return c == null || !c.serverPresent() || c.features().easyPlaceAllowed();
    }

    /** True while the server (if it runs BlockCompanion) allows easy place's auto mode. */
    public static boolean autoServerAllows() {
        SyncClient c = ClientSync.client();
        return c == null || !c.serverPresent() || c.features().easyPlaceAutoAllowed();
    }

    /** Called every tick: follows the server's permission, refreshes the looked-at ghost, and places restocked blocks. */
    public void tick(Minecraft mc, List<LoadedPlacement> shown) {
        ticks++;
        if (ticks % 200 == 0) cooldowns.prune(ticks);
        boolean allowed = serverAllows();
        if (!allowed && !blockedByServer && BlockCompanionClient.config().easyPlace) {
            BlockCompanionClient.actionBar("Easy place is switched off on this server");
        }
        blockedByServer = !allowed;
        target = findAny(mc, shown, 1f);
        tickPending(mc);
        tickAuto(mc, shown);
    }

    public void clear() {
        target = null;
        pending = null;
        cooldowns.clear();
    }

    /** The nearest ghost the player looks at over every shown placement, or null. */
    public static Target findAny(Minecraft mc, List<LoadedPlacement> shown, float partialTick) {
        Target best = null;
        for (LoadedPlacement lp : shown) {
            Target t = find(mc, lp.placement, lp.layers, partialTick);
            if (t != null && (best == null || t.distance() < best.distance())) best = t.withOwner(lp);
        }
        return best;
    }

    /** Easy place is on in the config and allowed here. */
    public boolean enabled() {
        return BlockCompanionClient.config().easyPlace && !blockedByServer;
    }

    /**
     * The ghost cell the player looks at, if it is nearer than the real block they look at. Ghost cells are the missing
     * blocks in visible levels (and single slabs that should be double).
     */
    public static Target find(Minecraft mc, Placement placement, Layers layers, float partialTick) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || placement == null) return null;
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 look = player.getViewVector(partialTick);
        double reach = player.blockInteractionRange();
        int range = BlockCompanionClient.config().interactRange;
        if (range > 0) reach = Math.min(reach, range);
        double realDist = Double.POSITIVE_INFINITY;
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) realDist = mc.hitResult.getLocation().distanceTo(eye);
        Box box = placement.worldBox();
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        CellRay.Hit h = CellRay.cast(eye.x, eye.y, eye.z, look.x, look.y, look.z, Math.min(reach, realDist), (x, y, z) -> {
            if (!box.contains(x, y, z) || !layers.isVisible(y - box.minY())) return false;
            io.blockcompanion.core.model.BlockState want = placement.stateAt(x, y, z);
            // A door's or bed's other half comes with the first: aiming at it would start a second door.
            if (want.isAir() || AutoPlacePlanner.secondHalf(want)) return false;
            return placeable(want, StateMapper.toCore(level.getBlockState(mpos.set(x, y, z))));
        });
        if (h == null) return null;
        io.blockcompanion.core.model.BlockState want = placement.stateAt(h.x(), h.y(), h.z());
        BlockState wantMc = StateMapper.toMc(want);
        if (wantMc == null) return null;
        return new Target(new BlockPos(h.x(), h.y(), h.z()), direction(h.face()), new Vec3(h.hx(), h.hy(), h.hz()), h.distance(), want, wantMc);
    }

    /** A cell easy place can fill: missing, or a single slab that should be a double one. */
    static boolean placeable(io.blockcompanion.core.model.BlockState want, io.blockcompanion.core.model.BlockState have) {
        return AutoPlacePlanner.placeable(want, have);
    }

    static Direction direction(EasyPlacePlanner.Dir d) {
        return switch (d) {
            case DOWN -> Direction.DOWN;
            case UP -> Direction.UP;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
        };
    }

    static EasyPlacePlanner.Dir dir(Direction d) {
        return switch (d) {
            case DOWN -> EasyPlacePlanner.Dir.DOWN;
            case UP -> EasyPlacePlanner.Dir.UP;
            case NORTH -> EasyPlacePlanner.Dir.NORTH;
            case SOUTH -> EasyPlacePlanner.Dir.SOUTH;
            case WEST -> EasyPlacePlanner.Dir.WEST;
            case EAST -> EasyPlacePlanner.Dir.EAST;
        };
    }

    // ---- right click ----------------------------------------------------------------------------------------------

    /**
     * Right-click. Returns true when easy place handled it (placed, or refused with a hint because the wrong block is
     * held); false lets vanilla handle the click.
     */
    public boolean onUse(Minecraft mc, List<LoadedPlacement> shown) {
        long lastUse = lastUseTick;
        lastUseTick = ticks;
        if (!enabled() || shown.isEmpty()) return false;
        Target t = findAny(mc, shown, 1f);
        if (t == null) return repeatOnReactive(mc, shown, lastUse);
        // Clicked a moment ago and not there yet: wait for the server's answer rather than click it again.
        if (cooldowns.cooling(t.pos().getX(), t.pos().getY(), t.pos().getZ(), ticks)) return true;
        LocalPlayer player = mc.player;
        Item needed = t.wantMc().getBlock().asItem();
        InteractionHand hand = null;
        for (InteractionHand h : InteractionHand.values()) {
            if (!player.getItemInHand(h).isEmpty() && player.getItemInHand(h).is(needed)) {
                hand = h;
                break;
            }
        }
        if (hand == null) {
            ItemStack main = player.getMainHandItem();
            // Food, tools and the like still work as usual; another block would land somewhere odd, so it is refused.
            boolean emptyHand = main.isEmpty();
            if (!emptyHand && !(main.getItem() instanceof BlockItem)) return false;
            ItemStack wanted = new ItemStack(needed);
            if (BlockCompanionClient.config().easyPlaceAutoPick && player.getInventory().findSlotMatchingItem(wanted) >= 0) {
                // In the inventory: bring it to hand and place it.
                pick(mc, wanted);
                if (player.getMainHandItem().is(needed)) placeOnce(mc, t, InteractionHand.MAIN_HAND);
                return true;
            }
            if (restock(mc, t, needed)) return true;
            if (emptyHand && !player.getAbilities().instabuild) return false;
            if (player.getAbilities().instabuild) {
                pick(mc, wanted);
                if (player.getMainHandItem().is(needed)) placeOnce(mc, t, InteractionHand.MAIN_HAND);
                return true;
            }
            hint("Needs " + wanted.getHoverName().getString());
            BlockCompanionClient.actionBar(lastHint);
            return true;
        }
        placeOnce(mc, t, hand);
        return true;
    }

    /** A manual click on {@code t}: the cell gets its cooldown, then the block is placed. */
    private boolean placeOnce(Minecraft mc, Target t, InteractionHand hand) {
        cooldowns.tried(t.pos().getX(), t.pos().getY(), t.pos().getZ(), ticks, AutoPlacePlanner.MANUAL_COOLDOWN);
        return place(mc, t, hand);
    }

    /**
     * Holding right-click to build with a block: a repeated click that would reach a door, trapdoor, gate, chest or other
     * block that reacts to use inside a shown schematic is swallowed, so it doesn't flap open and shut or open its screen
     * over and over. The first click, and any click while sneaking, still work as usual.
     */
    private boolean repeatOnReactive(Minecraft mc, List<LoadedPlacement> shown, long lastUse) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || ticks - lastUse > 5 || !mc.options.keyUse.isDown() || player.isShiftKeyDown()) return false;
        if (!(player.getMainHandItem().getItem() instanceof BlockItem)) return false;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return false;
        BlockPos p = hit.getBlockPos();
        if (!AutoPlacePlanner.reactsToUse(StateMapper.toCore(mc.level.getBlockState(p)))) return false;
        for (LoadedPlacement lp : shown) if (lp.placement.worldBox().contains(p.getX(), p.getY(), p.getZ())) return true;
        return false;
    }

    /**
     * Asks the server for the block from the linked chests when it allows that and a chest is known to hold it; the block
     * is placed once it arrives (see {@link #tickPending}). True when a request went out.
     */
    private boolean restock(Minecraft mc, Target t, Item needed) {
        SyncClient c = ClientSync.client();
        if (c == null || !c.serverPresent() || !c.features().chestBuildAllowed()) return false;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(needed).toString();
        if (ChestTracker.get().available(id) <= 0) return false;
        if (pending != null && pending.item() == needed && System.currentTimeMillis() < pending.until()) return true;
        c.restock(id, Math.max(1, Math.min(BlockCompanionClient.config().restockCount, new ItemStack(needed).getMaxStackSize() * 9)));
        pending = new Pending(t, needed, System.currentTimeMillis() + 3000);
        hint("Fetching " + new ItemStack(needed).getHoverName().getString() + " from your chests");
        return true;
    }

    /** Places the ghost a restock was for, once the item is in the inventory. */
    private void tickPending(Minecraft mc) {
        if (pending == null || mc.player == null) return;
        if (System.currentTimeMillis() > pending.until()) {
            pending = null;
            return;
        }
        ItemStack wanted = new ItemStack(pending.item());
        if (mc.player.getInventory().findSlotMatchingItem(wanted) < 0) return;
        Target t = pending.target();
        pending = null;
        pick(mc, wanted);
        if (mc.player.getMainHandItem().is(wanted.getItem())) placeOnce(mc, t, InteractionHand.MAIN_HAND);
    }

    /** Places the target's block from {@code hand}: plans the click, checks it against vanilla's rule, sends it. */
    public static boolean place(Minecraft mc, Target t, InteractionHand hand) {
        return place(mc, t, hand, false);
    }

    /** {@link #place(Minecraft, Target, InteractionHand)}; {@code quiet} leaves out the "can't place" message (auto mode). */
    public static boolean place(Minecraft mc, Target t, InteractionHand hand, boolean quiet) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null || mc.getConnection() == null) return false;
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BlockItem item)) return false;
        io.blockcompanion.core.model.BlockState existing = StateMapper.toCore(level.getBlockState(t.pos()));
        // The cell may have changed since it was aimed at (a late restock, a block placed meanwhile): the click would land
        // on that block, toggling a door or opening a chest, so it isn't sent.
        if (!placeable(t.want(), existing)) return false;
        Vec3 rel = t.hit().subtract(t.pos().getX(), t.pos().getY(), t.pos().getZ());
        EasyPlacePlanner.Click mine = new EasyPlacePlanner.Click(dir(t.face()), clamp(rel.x), clamp(rel.y), clamp(rel.z), player.getYRot(),
                player.getXRot(), false);
        List<EasyPlacePlanner.Click> candidates = EasyPlacePlanner.candidates(t.want(), mine, existing);

        float yaw = player.getYRot(), pitch = player.getXRot();
        EasyPlacePlanner.Click chosen = null, sameBlock = null;
        try {
            int n = 0;
            for (EasyPlacePlanner.Click c : candidates) {
                if (n++ >= MAX_SIMULATIONS) break;
                player.setYRot(c.yaw());
                player.setXRot(c.pitch());
                BlockPlaceContext ctx = new BlockPlaceContext(player, hand, stack, hitResult(t.pos(), c));
                ctx = item.updatePlacementContext(ctx);
                if (ctx == null || !ctx.canPlace() || !ctx.getClickedPos().equals(t.pos())) continue;
                BlockState result = ((BlockItemInvoker) item).blockcompanion$placementState(ctx);
                if (result == null) continue;
                if (Compare.classify(t.want(), StateMapper.toCore(result)) == Compare.Result.CORRECT) {
                    chosen = c;
                    break;
                }
                if (sameBlock == null && result.getBlock() == t.wantMc().getBlock()) sameBlock = c;
            }
        } finally {
            player.setYRot(yaw);
            player.setXRot(pitch);
        }
        if (chosen == null) chosen = sameBlock;
        if (chosen == null) {
            if (!quiet) BlockCompanionClient.actionBar("Can't place " + stack.getHoverName().getString() + " there");
            return false;
        }
        return send(mc, player, hand, t.pos(), chosen);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static BlockHitResult hitResult(BlockPos pos, EasyPlacePlanner.Click c) {
        Vec3 hit = new Vec3(pos.getX() + c.hitX(), pos.getY() + c.hitY(), pos.getZ() + c.hitZ());
        return new BlockHitResult(hit, direction(c.face()), pos, false);
    }

    /** Turns the player for the one click when needed (the server sees a rotation packet first), clicks, turns back. */
    private static boolean send(Minecraft mc, LocalPlayer player, InteractionHand hand, BlockPos pos, EasyPlacePlanner.Click c) {
        float yaw = player.getYRot(), pitch = player.getXRot();
        boolean turn = Math.abs(Math.IEEEremainder(c.yaw() - yaw, 360)) > 0.01 || Math.abs(c.pitch() - pitch) > 0.01;
        if (turn) {
            player.setYRot(c.yaw());
            player.setXRot(c.pitch());
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(c.yaw(), c.pitch(), player.onGround()));
        }
        InteractionResult r;
        try {
            r = mc.gameMode.useItemOn(player, hand, hitResult(pos, c));
        } finally {
            if (turn) {
                player.setYRot(yaw);
                player.setXRot(pitch);
                mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround()));
            }
        }
        if (r.consumesAction() && r.shouldSwing()) player.swing(hand);
        return r.consumesAction();
    }

    // ---- auto mode ------------------------------------------------------------------------------------------------

    /** Most cells auto mode checks against vanilla's rules in one tick. */
    private static final int AUTO_TRIES = 6;

    /** "Auto place on" or "Auto place paused: why" while auto mode is switched on; null while it is off. */
    public String autoStatus() {
        return autoStatus;
    }

    /** Why auto mode can't place right now, or null when it can. */
    private String autoPause(Minecraft mc, List<LoadedPlacement> shown) {
        LocalPlayer player = mc.player;
        if (!BlockCompanionClient.config().easyPlace) return "easy place is off";
        if (blockedByServer || !autoServerAllows()) return "off on this server";
        if (player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) return "not in a world";
        if (mc.screen != null) return "menu open";
        if (player.isSpectator()) return "spectator";
        if (shown.isEmpty()) return "no schematic shown";
        ItemStack main = player.getMainHandItem();
        // Like a click: food, tools and the selection tool in hand mean the player is doing something else.
        if (!main.isEmpty() && !(main.getItem() instanceof BlockItem)) return "hold a block";
        if (player.isUsingItem()) return "using an item";
        return null;
    }

    /**
     * Auto mode, every tick while it is on: when its pace allows, places the best missing block within reach
     * ({@link AutoPlacePlanner#select}) that the player carries, with the same click as easy place. Every cell tried gets
     * a cooldown, placed or not, so nothing is clicked over and over.
     */
    private void tickAuto(Minecraft mc, List<LoadedPlacement> shown) {
        var config = BlockCompanionClient.config();
        if (!config.easyPlaceAuto) {
            autoStatus = null;
            return;
        }
        String why = autoPause(mc, shown);
        autoStatus = why == null ? "Auto place on" : "Auto place paused: " + why;
        if (why != null || ticks < nextAutoTick) return;
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        SyncClient sc = ClientSync.client();
        boolean server = sc != null && sc.serverPresent();
        int interval = AutoPlacePlanner.intervalTicks(config.easyPlaceAutoRate, server ? sc.features().autoPlaceRate() : 0);
        double reach = AutoPlacePlanner.reach(player.blockInteractionRange(), server ? sc.features().autoPlaceRange() : 0);
        if (config.interactRange > 0) reach = Math.min(reach, config.interactRange);

        // What the player carries: the off hand and the hotbar, and the rest of the inventory when easy place may take from it.
        Inventory inv = player.getInventory();
        Set<Item> carried = new HashSet<>();
        carried.add(player.getOffhandItem().getItem());
        for (int i = 0; i < 36; i++) if (Inventory.isHotbarSlot(i) || config.easyPlaceAutoPick) carried.add(inv.getItem(i).getItem());
        carried.remove(net.minecraft.world.item.Items.AIR);
        if (carried.isEmpty()) return;
        Map<io.blockcompanion.core.model.BlockState, Item> items = new HashMap<>();
        java.util.function.Function<io.blockcompanion.core.model.BlockState, Item> itemFor = w -> items.computeIfAbsent(w, k -> {
            BlockState mcState = StateMapper.toMc(k);
            return mcState == null ? net.minecraft.world.item.Items.AIR : mcState.getBlock().asItem();
        });

        List<AutoPlacePlanner.Schematic> schematics = new ArrayList<>();
        for (LoadedPlacement lp : shown) {
            Placement p = lp.placement;
            Layers layers = lp.layers;
            Box box = p.worldBox();
            schematics.add(new AutoPlacePlanner.Schematic() {
                @Override
                public Box box() {
                    return box;
                }

                @Override
                public io.blockcompanion.core.model.BlockState want(int x, int y, int z) {
                    return p.stateAt(x, y, z);
                }

                @Override
                public boolean visible(int x, int y, int z) {
                    return layers.isVisible(y - box.minY());
                }
            });
        }
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        Vec3 eye = player.getEyePosition();
        List<AutoPlacePlanner.Cell> cells = AutoPlacePlanner.select(eye.x, eye.y, eye.z, reach, schematics,
                (x, y, z) -> StateMapper.toCore(level.getBlockState(mpos.set(x, y, z))),
                c -> !cooldowns.cooling(c.x(), c.y(), c.z(), ticks) && carried.contains(itemFor.apply(c.want())), AUTO_TRIES);
        for (AutoPlacePlanner.Cell c : cells) {
            cooldowns.tried(c.x(), c.y(), c.z(), ticks, AutoPlacePlanner.AUTO_COOLDOWN);
            Target t = aim(eye, c, shown.get(c.schematic()));
            InteractionHand hand = t == null ? null : toHand(mc, itemFor.apply(c.want()), config.easyPlaceAutoPick);
            if (hand == null) continue;
            if (place(mc, t, hand, true)) {
                BlockCompanionClient.noteOwnClick(t.pos());
                nextAutoTick = ticks + interval;
                return;
            }
        }
    }

    /** Auto mode's target for a cell: aimed at through the face that looks towards the eye. */
    private static Target aim(Vec3 eye, AutoPlacePlanner.Cell c, LoadedPlacement owner) {
        BlockState wantMc = StateMapper.toMc(c.want());
        if (wantMc == null) return null;
        BlockPos pos = new BlockPos(c.x(), c.y(), c.z());
        Vec3 center = Vec3.atCenterOf(pos);
        double dx = eye.x - center.x, dy = eye.y - center.y, dz = eye.z - center.z;
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        Direction face = ay >= ax && ay >= az ? (dy > 0 ? Direction.UP : Direction.DOWN)
                : ax >= az ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        Vec3 hit = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return new Target(pos, face, hit, Math.sqrt(c.distanceSq()), c.want(), wantMc, owner);
    }

    /**
     * Brings {@code item} to a hand: already held, from the hotbar, or (when {@code fromInventory}) swapped in from the
     * inventory. Null when the player doesn't carry it.
     */
    private static InteractionHand toHand(Minecraft mc, Item item, boolean fromInventory) {
        LocalPlayer player = mc.player;
        if (player.getMainHandItem().is(item)) return InteractionHand.MAIN_HAND;
        if (player.getOffhandItem().is(item)) return InteractionHand.OFF_HAND;
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !s.is(item) || (!Inventory.isHotbarSlot(i) && !fromInventory)) continue;
            pick(mc, s.copy());
            return player.getMainHandItem().is(item) ? InteractionHand.MAIN_HAND : null;
        }
        return null;
    }

    // ---- middle click ---------------------------------------------------------------------------------------------

    /** Middle click on a ghost: brings its item into the hand. Returns true when it handled the click. */
    public boolean onPick(Minecraft mc, List<LoadedPlacement> shown) {
        if (!BlockCompanionClient.config().pickGhost || shown.isEmpty()) return false;
        Target t = findAny(mc, shown, 1f);
        if (t == null) return false;
        Item item = t.wantMc().getBlock().asItem();
        if (item == net.minecraft.world.item.Items.AIR) return false;
        pick(mc, new ItemStack(item));
        return true;
    }

    /** Selects the item: from the hotbar, swapped in from the inventory, or (creative) made. */
    public static void pick(Minecraft mc, ItemStack wanted) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) return;
        Inventory inv = player.getInventory();
        int slot = inv.findSlotMatchingItem(wanted);
        if (slot >= 0 && Inventory.isHotbarSlot(slot)) {
            inv.selected = slot;
            return;
        }
        if (slot >= 0) {
            int hotbar = inv.getSuitableHotbarSlot();
            // Inventory slots 9..35 have the same numbers in the player's inventory menu.
            mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, hotbar, ClickType.SWAP, player);
            inv.selected = hotbar;
            return;
        }
        if (player.getAbilities().instabuild) {
            int hotbar = inv.getSuitableHotbarSlot();
            inv.selected = hotbar;
            inv.setItem(hotbar, wanted.copy());
            mc.gameMode.handleCreativeModeItemAdd(wanted.copy(), 36 + hotbar);
            return;
        }
        BlockCompanionClient.actionBar("No " + wanted.getHoverName().getString() + " in your inventory");
    }
}
