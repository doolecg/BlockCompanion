package io.blockcompanion.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.ClientConfig;
import io.blockcompanion.client.StateMapper;
import io.blockcompanion.core.compare.Compare;
import io.blockcompanion.core.hud.Palette;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.placement.Layers;
import io.blockcompanion.core.placement.Placement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.BlockStateModelWrapper;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws the placement: its bounding box, near-solid ghosts for missing blocks, and red (wrong) or orange (extra)
 * highlights over real blocks. Nothing else. A cell whose real block is right shows nothing: the block has replaced
 * its ghost.
 *
 * <p>Ghosts use the game's own block models and fluids, lit by the world at their cell with ambient occlusion, at high
 * opacity with a slight cool tint and a slow pulse. Chests, heads, banners, shulker boxes, bells, pots and the other
 * blocks with special models are drawn through those models, and signs and lecterns through their block entity
 * renderers. The ghost quads go through the game's translucent moving-block type, which sorts them back to front each
 * frame, so overlapping ghosts don't show see-through artefacts.
 *
 * <p>Geometry is meshed per world-aligned 16x16x16 section and re-meshed only when the placement, the slice view, or
 * the world inside that section changes, a few sections per frame, nearest first. Minecraft 26.3 renders through
 * extracted state and submit nodes, so unlike the 1.21.1 renderer (which keeps GPU buffers per section) each frame
 * the sections inside the view frustum hand their recorded quads to the game's submit collector.
 */
public final class GhostRenderer {
    /** Per-frame time budget for meshing. */
    private static final long BUILD_BUDGET_NANOS = 4_000_000L;
    /** Special models and block entity ghosts are drawn up to this far (blocks), at most this many per frame. */
    private static final double BE_DISTANCE = 48;
    private static final int BE_PER_FRAME = 256;

    /** Fill and outline opacity over wrong and in-the-way blocks; their colours are in the settings. */
    private static final int MARK_FILL_ALPHA = 0x4C, MARK_LINE_ALPHA = 0xE6;
    /** Blocks nothing can draw (barriers, light blocks, unknown modded blocks) show as a faint box. */
    private static final int NO_MODEL_FILL = 0x40D8E8FF;
    /** Special-model and block entity ghosts get a faint outline: they are drawn opaque. */
    private static final int BE_LINE_ALPHA = 0x80;
    private static final float INFLATE = 0.004f;
    /** Floats per overlay cube: 24 vertices of x, y, z. */
    private static final int CUBE_FLOATS = 72;
    private static final BlockDisplayContext DISPLAY = BlockDisplayContext.create();

    /** Block entities whose renderers draw what matters of a ghost (the rest have special block models). */
    private static final Set<BlockEntityType<?>> BE_TYPES = Set.of(BlockEntityTypes.SIGN, BlockEntityTypes.HANGING_SIGN,
            BlockEntityTypes.LECTERN);

    /** A ghost drawn through a special block model. */
    private record SpecialGhost(BlockPos pos, BlockModelRenderState state) {
    }

    /** A ghost drawn through a block entity renderer. */
    private record BeGhost(BlockPos pos, BlockEntity be) {
    }

    /** A section's recorded geometry: model ghosts, coloured boxes, outlines, and the ghosts drawn some other way. */
    private record SectionMesh(QuadRecorder.Recorded ghosts, float[] cubes, int[] cubeColors, float[] lines, int[] lineColors,
                               List<SpecialGhost> specials, List<BeGhost> blockEntities) {
    }

    /** A filled ghost's short "pop": its model growing and fading out. */
    private record Pop(BlockPos pos, QuadRecorder.Recorded quads, long start) {
    }

    private static final long POP_MS = 260;

    private final Map<Long, SectionMesh> meshes = new HashMap<>();
    private Set<Long> sections = Set.of();
    private final Set<Long> dirty = ConcurrentHashMap.newKeySet();
    /**
     * When each section was last meshed, and the earliest a stale one may be meshed again. The game re-marks every
     * section around a changed block while light settles (up to 27 per block), which AutoBuild does several times a
     * second: those wait {@link #WORLD_GAP_MS} after the section's last mesh, and a section whose own cells changed
     * waits only {@link #CELL_GAP_MS}, so a steady stream of changes is meshed a few times a second, not per block.
     */
    private final Map<Long, Long> builtAt = new ConcurrentHashMap<>(), notBefore = new ConcurrentHashMap<>();
    private static final long WORLD_GAP_MS = 2000, CELL_GAP_MS = 150;
    private Placement placement;
    private long placementVersion = -1, layersVersion = -1;
    private Box box;
    private final List<Pop> pops = new ArrayList<>();
    private List<io.blockcompanion.core.model.BlockPos> highlights = List.of();
    private BlockPos target;
    private BlockModelResolver resolver;

    private static long key(int sx, int sy, int sz) {
        return io.blockcompanion.core.model.BlockPos.pack(sx, sy, sz);
    }

    /** The game marks a world section for re-meshing (a block changed or a chunk loaded): ours there is stale too. */
    public void onWorldSectionDirty(int sx, int sy, int sz) {
        if (placement == null) return;
        long k = key(sx, sy, sz);
        if (sections.contains(k)) defer(k, WORLD_GAP_MS);
    }

    /** A block inside the placement changed: its section is meshed again shortly. */
    public void onCellChanged(int x, int y, int z) {
        if (placement == null) return;
        long k = key(x >> 4, y >> 4, z >> 4);
        if (sections.contains(k)) defer(k, CELL_GAP_MS);
    }

    private void defer(long k, long gapMs) {
        Long built = builtAt.get(k);
        notBefore.merge(k, built == null ? 0L : built + gapMs, Math::min);
        dirty.add(k);
    }

    public void clear() {
        meshes.clear();
        dirty.clear();
        builtAt.clear();
        notBefore.clear();
        sections = Set.of();
        placement = null;
        placementVersion = layersVersion = -1;
        pops.clear();
        highlights = List.of();
        target = null;
    }

    public void invalidate() {
        notBefore.clear();
        dirty.addAll(sections);
    }

    /** A ghost was filled correctly: let it pop. */
    public void pop(int x, int y, int z, io.blockcompanion.core.model.BlockState want) {
        Minecraft mc = Minecraft.getInstance();
        BlockState s = StateMapper.toMc(want);
        if (s == null || s.getRenderShape() != RenderShape.MODEL || mc.level == null || placement == null) return;
        BlockModel model = mc.getModelManager().getBlockModelSet().get(s);
        if (!(model instanceof BlockStateModelWrapper)) return;
        QuadRecorder rec = new QuadRecorder(0.7f, true);
        BlockPos at = new BlockPos(x, y, z);
        ModelBlockRenderer blocks = new ModelBlockRenderer(false, false, mc.getBlockColors());
        blocks.tesselateBlock(rec::putBlockBakedQuad, 0, 0, 0, new SchematicView(mc.level, placement, BlockCompanionClient.layers()), at, s,
                mc.getModelManager().getBlockStateModelSet().get(s), s.getSeed(at));
        QuadRecorder.Recorded q = rec.finish();
        if (q == null) return;
        if (pops.size() > 32) pops.remove(0);
        pops.add(new Pop(at, q, System.currentTimeMillis()));
    }

    public void setHighlights(List<io.blockcompanion.core.model.BlockPos> cells) {
        highlights = cells;
    }

    public void setTarget(BlockPos pos) {
        target = pos;
    }

    private void sync(Placement p, Layers layers) {
        if (p != placement) {
            clear();
            placement = p;
        }
        if (p.version() != placementVersion) {
            placementVersion = p.version();
            box = p.worldBox();
            Set<Long> now = new HashSet<>();
            for (int sx = box.minX() >> 4; sx <= box.maxX() >> 4; sx++)
                for (int sy = box.minY() >> 4; sy <= box.maxY() >> 4; sy++)
                    for (int sz = box.minZ() >> 4; sz <= box.maxZ() >> 4; sz++) now.add(key(sx, sy, sz));
            meshes.keySet().retainAll(now);
            sections = now;
            notBefore.clear();
            dirty.addAll(now);
        }
        if (layers.version() != layersVersion) {
            layersVersion = layers.version();
            notBefore.clear();
            dirty.addAll(sections);
        }
    }

    /** Meshes what is stale (within the frame budget) and submits the visible sections for this frame (the box is {@link BoxRenderer}'s). */
    public void submit(Placement p, Layers layers, SubmitNodeCollector collector, PoseStack poseStack, Vec3 cam,
                       Frustum frustum, CameraRenderState camera) {
        ClientLevel level = Minecraft.getInstance().level;
        if (p == null || level == null) {
            if (placement != null) clear();
            return;
        }
        sync(p, layers);
        rebuildSome(level, layers, cam);
        ClientConfig config = BlockCompanionClient.config();

        List<Map.Entry<Long, SectionMesh>> visible = new ArrayList<>();
        for (var e : meshes.entrySet()) {
            io.blockcompanion.core.model.BlockPos s = io.blockcompanion.core.model.BlockPos.unpack(e.getKey());
            double x = s.x() << 4, y = s.y() << 4, z = s.z() << 4;
            if (tooFar(e.getKey(), cam)) continue;
            if (frustum == null || frustum.isVisible(new AABB(x, y, z, x + 16, y + 16, z + 16))) visible.add(e);
        }
        visible.sort((a, b) -> Double.compare(sectionDist2(b.getKey(), cam), sectionDist2(a.getKey(), cam)));

        float pulse = config.ghostShimmer ? shimmer() : 1f;
        float alphaPulse = config.ghostShimmer ? 0.94f + 0.06f * pulse : 1f;
        RenderType ghostType = RenderTypes.translucentMovingBlock();
        RenderType overlayType = RenderTypes.debugQuads();
        for (var e : visible) {
            io.blockcompanion.core.model.BlockPos s = io.blockcompanion.core.model.BlockPos.unpack(e.getKey());
            SectionMesh mesh = e.getValue();
            poseStack.pushPose();
            poseStack.translate((s.x() << 4) - cam.x, (s.y() << 4) - cam.y, (s.z() << 4) - cam.z);
            if (mesh.ghosts() != null) {
                QuadRecorder.Recorded g = mesh.ghosts();
                collector.submitCustomGeometry(poseStack, ghostType, (pose, out) -> g.replay(pose, out, pulse, alphaPulse, false));
            }
            if (mesh.cubes() != null) {
                float[] o = mesh.cubes();
                int[] colors = mesh.cubeColors();
                collector.submitCustomGeometry(poseStack, overlayType, (pose, out) -> {
                    for (int cube = 0; cube < colors.length; cube++) {
                        int col = colors[cube];
                        for (int i = cube * CUBE_FLOATS, end = i + CUBE_FLOATS; i < end; i += 3) out.addVertex(pose, o[i], o[i + 1], o[i + 2]).setColor(col);
                    }
                });
            }
            if (mesh.lines() != null) {
                float[] ln = mesh.lines();
                int[] colors = mesh.lineColors();
                collector.submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, out) -> {
                    for (int i = 0; i < colors.length; i++) {
                        int o = i * 6;
                        line(out, pose, ln[o], ln[o + 1], ln[o + 2], ln[o + 3], ln[o + 4], ln[o + 5], colors[i], 2f);
                    }
                });
            }
            poseStack.popPose();
        }
        if (config.ghostBlockEntities) submitSpecials(level, visible, collector, poseStack, cam, camera);
        submitPops(collector, poseStack, cam);
        submitHelpers(collector, poseStack, cam);
    }

    /** The ghost tint from the colour settings: red, green and blue from 0 to 1. */
    static float[] tint() {
        return BlockCompanionClient.config().colors.floats(Palette.Entry.GHOST);
    }

    private static int color(Palette.Entry e, int alpha) {
        return BlockCompanionClient.config().colors.argb(e, alpha);
    }

    /** 0.9 to 1: a slow breathing pulse. */
    static float shimmer() {
        double t = (System.currentTimeMillis() % 2600L) / 2600.0;
        return (float) (0.95 + 0.05 * Math.sin(t * Math.PI * 2));
    }

    /** Whether a section lies beyond the ghost distance (measured to its centre, with half a section to spare). */
    private static boolean tooFar(long key, Vec3 cam) {
        int d = BlockCompanionClient.config().ghostDistance;
        return d > 0 && sectionDist2(key, cam) > (d + 8.0) * (d + 8.0);
    }

    private static double sectionDist2(long key, Vec3 cam) {
        io.blockcompanion.core.model.BlockPos s = io.blockcompanion.core.model.BlockPos.unpack(key);
        double dx = (s.x() << 4) + 8 - cam.x, dy = (s.y() << 4) + 8 - cam.y, dz = (s.z() << 4) + 8 - cam.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Chests, heads, banners... through their special models; signs and lecterns through their renderers. Nearest first. */
    private void submitSpecials(ClientLevel level, List<Map.Entry<Long, SectionMesh>> visible, SubmitNodeCollector collector, PoseStack poseStack,
                                Vec3 cam, CameraRenderState camera) {
        Minecraft mc = Minecraft.getInstance();
        int drawn = 0;
        for (int i = visible.size() - 1; i >= 0 && drawn < BE_PER_FRAME; i--) {
            SectionMesh mesh = visible.get(i).getValue();
            for (SpecialGhost g : mesh.specials()) {
                if (drawn >= BE_PER_FRAME) break;
                if (g.pos().distToCenterSqr(cam) > BE_DISTANCE * BE_DISTANCE) continue;
                poseStack.pushPose();
                poseStack.translate(g.pos().getX() - cam.x, g.pos().getY() - cam.y, g.pos().getZ() - cam.z);
                g.state().submit(poseStack, collector, LightCoordsUtil.getLightCoords(level, g.pos()), OverlayTexture.NO_OVERLAY, 0);
                poseStack.popPose();
                drawn++;
            }
            for (BeGhost g : mesh.blockEntities()) {
                if (drawn >= BE_PER_FRAME) break;
                if (g.pos().distToCenterSqr(cam) > BE_DISTANCE * BE_DISTANCE) continue;
                BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer = mc.getBlockEntityRenderDispatcher().getRenderer(g.be());
                if (renderer == null) continue;
                try {
                    BlockEntityRenderState state = renderer.createRenderState();
                    renderer.extractRenderState(g.be(), state, 1f, cam, null);
                    poseStack.pushPose();
                    poseStack.translate(g.pos().getX() - cam.x, g.pos().getY() - cam.y, g.pos().getZ() - cam.z);
                    renderer.submit(state, poseStack, collector, camera);
                    poseStack.popPose();
                } catch (RuntimeException ex) {
                    // A renderer that can't cope with a detached block entity: leave that ghost out.
                }
                drawn++;
            }
        }
    }

    private void submitPops(SubmitNodeCollector collector, PoseStack poseStack, Vec3 cam) {
        if (pops.isEmpty()) return;
        long now = System.currentTimeMillis();
        pops.removeIf(pp -> now - pp.start() > POP_MS);
        RenderType type = RenderTypes.translucentMovingBlock();
        for (Pop pp : pops) {
            float t = (now - pp.start()) / (float) POP_MS;
            float scale = 1.02f + 0.18f * t;
            float fade = (1 - t) * (1 - t);
            poseStack.pushPose();
            poseStack.translate(pp.pos().getX() - cam.x + 0.5, pp.pos().getY() - cam.y + 0.5, pp.pos().getZ() - cam.z + 0.5);
            poseStack.scale(scale, scale, scale);
            poseStack.translate(-0.5, -0.5, -0.5);
            QuadRecorder.Recorded q = pp.quads();
            collector.submitCustomGeometry(poseStack, type, (pose, out) -> q.replay(pose, out, 1f, fade, true));
            poseStack.popPose();
        }
    }

    /** The easy-place target outline and the material helper's marks. */
    private void submitHelpers(SubmitNodeCollector collector, PoseStack poseStack, Vec3 cam) {
        if (target == null && highlights.isEmpty()) return;
        float pulse = (float) (0.5 + 0.5 * Math.sin((System.currentTimeMillis() % 1400L) / 1400.0 * Math.PI * 2));
        int helper = ((int) (0x70 + 0x60 * pulse) << 24) | BlockCompanionClient.config().colors.get(Palette.Entry.HELPER);
        List<io.blockcompanion.core.model.BlockPos> cells = highlights;
        BlockPos t = target;
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        collector.submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, out) -> {
            for (io.blockcompanion.core.model.BlockPos c : cells) {
                if (t != null && c.x() == t.getX() && c.y() == t.getY() && c.z() == t.getZ()) continue;
                float e = 0.02f;
                edges(out, pose, c.x() - e, c.y() - e, c.z() - e, 1 + 2 * e, 1 + 2 * e, 1 + 2 * e, helper, 2f);
            }
            if (t != null) {
                float e = 0.003f;
                edges(out, pose, t.getX() - e, t.getY() - e, t.getZ() - e, 1 + 2 * e, 1 + 2 * e, 1 + 2 * e, 0xE6FFFFFF, 2f);
            }
        });
        poseStack.popPose();
    }

    private static void edges(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z, float sx, float sy, float sz, int color, float width) {
        float x1 = x + sx, y1 = y + sy, z1 = z + sz;
        float[][] c = {{x, y, z}, {x1, y, z}, {x1, y, z1}, {x, y, z1}, {x, y1, z}, {x1, y1, z}, {x1, y1, z1}, {x, y1, z1}};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] e : edges) line(out, pose, c[e[0]][0], c[e[0]][1], c[e[0]][2], c[e[1]][0], c[e[1]][1], c[e[1]][2], color, width);
    }

    private static void line(VertexConsumer out, PoseStack.Pose pose, float ax, float ay, float az, float bx, float by, float bz, int color, float width) {
        float nx = bx - ax, ny = by - ay, nz = bz - az;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0) return;
        nx /= len;
        ny /= len;
        nz /= len;
        out.addVertex(pose, ax, ay, az).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
        out.addVertex(pose, bx, by, bz).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
    }

    // ---- meshing --------------------------------------------------------------------------------------------------

    private void rebuildSome(ClientLevel level, Layers layers, Vec3 cam) {
        if (dirty.isEmpty()) return;
        List<Long> order = new ArrayList<>(dirty);
        int camX = (int) Math.floor(cam.x) >> 4, camY = (int) Math.floor(cam.y) >> 4, camZ = (int) Math.floor(cam.z) >> 4;
        order.sort((a, b) -> Long.compare(dist(a, camX, camY, camZ), dist(b, camX, camY, camZ)));
        long start = System.nanoTime(), now = System.currentTimeMillis();
        for (long k : order) {
            // Far sections stay stale until the player comes near: no work for what isn't drawn.
            if (tooFar(k, cam)) continue;
            Long due = notBefore.get(k);
            if (due != null && due > now) continue;
            dirty.remove(k);
            notBefore.remove(k);
            if (!sections.contains(k)) continue;
            builtAt.put(k, now);
            try {
                build(level, layers, k);
            } catch (RuntimeException ex) {
                BlockCompanionClient.LOG.warn("Could not build ghost section {}", io.blockcompanion.core.model.BlockPos.unpack(k), ex);
            }
            if (System.nanoTime() - start > BUILD_BUDGET_NANOS) break;
        }
    }

    private static long dist(long key, int cx, int cy, int cz) {
        io.blockcompanion.core.model.BlockPos s = io.blockcompanion.core.model.BlockPos.unpack(key);
        long dx = s.x() - cx, dy = s.y() - cy, dz = s.z() - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Growable float and colour buffers for boxes and outlines. */
    private static final class Shapes {
        float[] cubes = new float[CUBE_FLOATS * 16];
        int[] cubeColors = new int[16];
        int cubeCount;
        float[] lines = new float[6 * 48];
        int[] lineColors = new int[48];
        int lineCount;

        void cube(float x, float y, float z, int color) {
            if (cubeCount == cubeColors.length) {
                cubeColors = Arrays.copyOf(cubeColors, cubeColors.length * 2);
                cubes = Arrays.copyOf(cubes, cubes.length * 2);
            }
            GhostRenderer.cube(cubes, cubeCount * CUBE_FLOATS, x, y, z);
            cubeColors[cubeCount++] = color;
        }

        void outline(float x, float y, float z, int color) {
            float a = x - INFLATE, b = y - INFLATE, c = z - INFLATE, s = 1 + 2 * INFLATE;
            float x1 = a + s, y1 = b + s, z1 = c + s;
            float[][] p = {{a, b, c}, {x1, b, c}, {x1, b, z1}, {a, b, z1}, {a, y1, c}, {x1, y1, c}, {x1, y1, z1}, {a, y1, z1}};
            int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
            for (int[] e : edges) {
                if (lineCount == lineColors.length) {
                    lineColors = Arrays.copyOf(lineColors, lineColors.length * 2);
                    lines = Arrays.copyOf(lines, lines.length * 2);
                }
                int o = lineCount * 6;
                lines[o] = p[e[0]][0];
                lines[o + 1] = p[e[0]][1];
                lines[o + 2] = p[e[0]][2];
                lines[o + 3] = p[e[1]][0];
                lines[o + 4] = p[e[1]][1];
                lines[o + 5] = p[e[1]][2];
                lineColors[lineCount++] = color;
            }
        }
    }

    private void build(ClientLevel level, Layers layers, long key) {
        io.blockcompanion.core.model.BlockPos s = io.blockcompanion.core.model.BlockPos.unpack(key);
        int ox = s.x() << 4, oy = s.y() << 4, oz = s.z() << 4;
        int x0 = Math.max(ox, box.minX()), x1 = Math.min(ox + 15, box.maxX());
        int y0 = Math.max(oy, box.minY()), y1 = Math.min(oy + 15, box.maxY());
        int z0 = Math.max(oz, box.minZ()), z1 = Math.min(oz + 15, box.maxZ());

        ClientConfig config = BlockCompanionClient.config();
        Minecraft mc = Minecraft.getInstance();
        ModelBlockRenderer blocks = new ModelBlockRenderer(mc.options.ambientOcclusion().get(), true, mc.getBlockColors());
        BlockStateModelSet models = mc.getModelManager().getBlockStateModelSet();
        FluidRenderer fluids = new FluidRenderer(mc.getModelManager().getFluidStateModelSet());
        if (resolver == null) resolver = new BlockModelResolver(mc.getModelManager());
        SchematicView view = new SchematicView(level, placement, layers);
        QuadRecorder ghosts = new QuadRecorder(config.ghostAlpha, config.ghostShimmer);
        Shapes shapes = new Shapes();
        List<SpecialGhost> specials = new ArrayList<>();
        List<BeGhost> bes = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = y0; y <= y1; y++) {
            if (!layers.isVisible(y - box.minY())) continue;
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    io.blockcompanion.core.model.BlockState want = placement.stateAt(x, y, z);
                    pos.set(x, y, z);
                    BlockState have = level.getBlockState(pos);
                    if (want.isAir() && have.isAir()) continue;
                    float lx = x - ox, ly = y - oy, lz = z - oz;
                    switch (Compare.classify(want, StateMapper.toCore(have))) {
                        case MISSING -> {
                            BlockState ghost = StateMapper.toMc(want);
                            boolean drawn = false;
                            if (ghost != null) {
                                BlockPos at = pos.immutable();
                                FluidState fluid = ghost.getFluidState();
                                if (ghost.getBlock() instanceof LiquidBlock && !fluid.isEmpty()) {
                                    fluids.tesselate(view, at, layer -> ghosts, ghost, fluid);
                                    drawn = true;
                                }
                                if (ghost.getRenderShape() == RenderShape.MODEL) {
                                    BlockModel model = mc.getModelManager().getBlockModelSet().get(ghost);
                                    if (model instanceof BlockStateModelWrapper) {
                                        blocks.tesselateBlock(ghosts::putBlockBakedQuad, lx, ly, lz, view, at, ghost, models.get(ghost), ghost.getSeed(at));
                                        drawn = true;
                                    } else if (config.ghostBlockEntities) {
                                        BlockModelRenderState rs = new BlockModelRenderState();
                                        resolver.update(rs, ghost, DISPLAY);
                                        if (!rs.isEmpty()) {
                                            specials.add(new SpecialGhost(at, rs));
                                            shapes.outline(lx, ly, lz, color(Palette.Entry.BLOCK_ENTITY, BE_LINE_ALPHA));
                                            drawn = true;
                                        }
                                    }
                                }
                                if (config.ghostBlockEntities) {
                                    BlockEntity be = blockEntity(level, at, ghost);
                                    if (be != null) {
                                        bes.add(new BeGhost(at, be));
                                        shapes.outline(lx, ly, lz, color(Palette.Entry.BLOCK_ENTITY, BE_LINE_ALPHA));
                                        drawn = true;
                                    }
                                }
                            }
                            if (!drawn) shapes.cube(lx, ly, lz, NO_MODEL_FILL);
                        }
                        case WRONG -> {
                            shapes.cube(lx, ly, lz, color(Palette.Entry.WRONG, MARK_FILL_ALPHA));
                            shapes.outline(lx, ly, lz, color(Palette.Entry.WRONG, MARK_LINE_ALPHA));
                        }
                        case EXTRA -> {
                            shapes.cube(lx, ly, lz, color(Palette.Entry.EXTRA, MARK_FILL_ALPHA));
                            shapes.outline(lx, ly, lz, color(Palette.Entry.EXTRA, MARK_LINE_ALPHA));
                        }
                        default -> {
                        }
                    }
                }
            }
        }
        QuadRecorder.Recorded g = ghosts.finish();
        if (g == null && shapes.cubeCount == 0 && shapes.lineCount == 0 && specials.isEmpty() && bes.isEmpty()) {
            meshes.remove(key);
            return;
        }
        meshes.put(key, new SectionMesh(g,
                shapes.cubeCount == 0 ? null : Arrays.copyOf(shapes.cubes, shapes.cubeCount * CUBE_FLOATS),
                shapes.cubeCount == 0 ? null : Arrays.copyOf(shapes.cubeColors, shapes.cubeCount),
                shapes.lineCount == 0 ? null : Arrays.copyOf(shapes.lines, shapes.lineCount * 6),
                shapes.lineCount == 0 ? null : Arrays.copyOf(shapes.lineColors, shapes.lineCount),
                specials.isEmpty() ? List.of() : specials, bes.isEmpty() ? List.of() : bes));
    }

    /** A detached block entity for signs and lecterns; null for every other block. */
    private static BlockEntity blockEntity(ClientLevel level, BlockPos at, BlockState ghost) {
        if (!ghost.hasBlockEntity() || !(ghost.getBlock() instanceof EntityBlock eb)) return null;
        BlockEntity be;
        try {
            be = eb.newBlockEntity(at, ghost);
        } catch (RuntimeException e) {
            return null;
        }
        if (be == null || !BE_TYPES.contains(be.getType())) return null;
        be.setLevel(level);
        return be;
    }

    /** A slightly inflated cube: 6 quads, 24 vertices of (x, y, z) written at {@code at}. */
    private static void cube(float[] out, int at, float x, float y, float z) {
        float a = x - INFLATE, b = y - INFLATE, c = z - INFLATE;
        float d = x + 1 + INFLATE, e = y + 1 + INFLATE, f = z + 1 + INFLATE;
        float[][] quads = {
                {a, b, c, d, b, c, d, b, f, a, b, f}, {a, e, c, a, e, f, d, e, f, d, e, c},
                {a, b, c, a, e, c, d, e, c, d, b, c}, {a, b, f, d, b, f, d, e, f, a, e, f},
                {a, b, c, a, b, f, a, e, f, a, e, c}, {d, b, c, d, e, c, d, e, f, d, b, f}};
        int i = at;
        for (float[] q : quads) for (float v : q) out[i++] = v;
    }
}
