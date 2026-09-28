package io.blockcompanion.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.ClientConfig;
import io.blockcompanion.client.StateMapper;
import io.blockcompanion.core.compare.Compare;
import io.blockcompanion.core.compare.MarkMesh;
import io.blockcompanion.core.hud.Palette;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.nbt.Snbt;
import io.blockcompanion.core.placement.Layers;
import io.blockcompanion.core.placement.Placement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
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
 * <p>Ghosts use the game's own block models, fluids and (for chests, signs, beds, banners, heads, shulker boxes and a
 * few more) block entity renderers, lit by the world at their cell with ambient occlusion, at high opacity with a slight
 * cool tint and a slow pulse. Their quads are sorted back to front per section (re-sorted as the camera moves), and
 * sections are drawn far to near, so overlapping ghosts don't show see-through artefacts.
 *
 * <p>Meshes are kept per world-aligned 16x16x16 section in GPU buffers and rebuilt only when the placement, the slice
 * view, or the world inside that section changes, a few sections per frame, nearest first. Sections outside the view
 * frustum are skipped.
 */
public final class GhostRenderer {
    /** Per-frame time budget for rebuilding meshes. */
    private static final long BUILD_BUDGET_NANOS = 4_000_000L;
    /** Re-sort a section's ghost quads after the camera moved this far (blocks). */
    private static final double RESORT_DISTANCE = 1.0;
    /** Sections re-sorted per frame at most (nearest first). */
    private static final int RESORTS_PER_FRAME = 6;
    /** Block entity ghosts are drawn up to this far (blocks), at most this many per frame. */
    private static final double BE_DISTANCE = 48;
    private static final int BE_PER_FRAME = 256;

    /** Fill and outline opacity over wrong and in-the-way blocks; their colours are in the settings. */
    private static final int MARK_FILL_ALPHA = 0x4C, MARK_LINE_ALPHA = 0xE6;
    /** Blocks nothing can draw (barriers, light blocks, unknown modded blocks) show as a faint box. */
    private static final int NO_MODEL_FILL = 0x40D8E8FF;
    /** Block entity ghosts get a faint outline so they read as not built even when drawn opaque. */
    private static final int BE_LINE_ALPHA = 0x80;
    private static final float INFLATE = 0.004f;
    /** Mark kinds in the {@link MarkMesh}: touching marks of one kind are merged into one shape. */
    private static final int MARK_WRONG = 1, MARK_EXTRA = 2;

    /** Block entities whose renderers draw the block itself (or the part that matters), safe to draw for a ghost. */
    private static final Set<BlockEntityType<?>> BE_TYPES = Set.of(BlockEntityType.CHEST, BlockEntityType.TRAPPED_CHEST,
            BlockEntityType.ENDER_CHEST, BlockEntityType.SIGN, BlockEntityType.HANGING_SIGN, BlockEntityType.BED, BlockEntityType.BANNER,
            BlockEntityType.SKULL, BlockEntityType.SHULKER_BOX, BlockEntityType.DECORATED_POT, BlockEntityType.BELL,
            BlockEntityType.LECTERN, BlockEntityType.CONDUIT, BlockEntityType.ENCHANTING_TABLE);

    /** A block entity drawn as a ghost. */
    private record BeGhost(BlockPos pos, BlockEntity be) {
    }

    private static final class SectionMesh {
        VertexBuffer ghosts;
        MeshData.SortState sort;
        double sortX = Double.NaN, sortY, sortZ;
        VertexBuffer overlays;
        VertexBuffer lines;
        List<BeGhost> blockEntities = List.of();

        void close() {
            if (ghosts != null) ghosts.close();
            if (overlays != null) overlays.close();
            if (lines != null) lines.close();
            ghosts = overlays = lines = null;
            sort = null;
            blockEntities = List.of();
        }
    }

    /** A filled ghost's short "pop": the ghost model growing and fading out over {@link #POP_MS}. */
    private record Pop(BlockPos pos, BlockState state, long start) {
    }

    private static final long POP_MS = 260;

    private final Map<Long, SectionMesh> meshes = new HashMap<>();
    /** Sections the placement's box touches, packed section coordinates. */
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
    /** Cells the material helper marks, and the ghost easy place would fill. */
    private List<io.blockcompanion.core.model.BlockPos> highlights = List.of();
    private BlockPos target;


    private ByteBufferBuilder ghostBytes, overlayBytes, lineBytes, sortBytes;
    private final RandomSource random = RandomSource.create();

    private static long key(int sx, int sy, int sz) {
        return io.blockcompanion.core.model.BlockPos.pack(sx, sy, sz);
    }

    /** Called (on the render thread) when the game marks a world section for re-meshing: a block changed or a chunk loaded. */
    public void onWorldSectionDirty(int sx, int sy, int sz) {
        if (placement == null) return;
        long k = key(sx, sy, sz);
        if (sections.contains(k)) defer(k, WORLD_GAP_MS);
    }

    /** A block inside the placement changed: its section is meshed again shortly, and the neighbours its marks join. */
    public void onCellChanged(int x, int y, int z) {
        if (placement == null) return;
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        for (int dx = (x & 15) == 0 ? -1 : 0; dx <= ((x & 15) == 15 ? 1 : 0); dx++)
            for (int dy = (y & 15) == 0 ? -1 : 0; dy <= ((y & 15) == 15 ? 1 : 0); dy++)
                for (int dz = (z & 15) == 0 ? -1 : 0; dz <= ((z & 15) == 15 ? 1 : 0); dz++) {
                    long k = key(sx + dx, sy + dy, sz + dz);
                    if (sections.contains(k)) defer(k, CELL_GAP_MS);
                }
    }

    private void defer(long k, long gapMs) {
        Long built = builtAt.get(k);
        notBefore.merge(k, built == null ? 0L : built + gapMs, Math::min);
        dirty.add(k);
    }

    /** Frees every mesh (placement unloaded, world left, resources reloaded). */
    public void clear() {
        meshes.values().forEach(SectionMesh::close);
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

    /** Marks every section for rebuilding, e.g. after a resource reload or a config change. */
    public void invalidate() {
        notBefore.clear();
        dirty.addAll(sections);
    }

    /** A ghost was filled correctly: let it pop. */
    public void pop(int x, int y, int z, io.blockcompanion.core.model.BlockState want) {
        BlockState s = StateMapper.toMc(want);
        if (s == null || s.getRenderShape() != RenderShape.MODEL) return;
        if (pops.size() > 32) pops.remove(0);
        pops.add(new Pop(new BlockPos(x, y, z), s, System.currentTimeMillis()));
    }

    /** The cells the material helper marks (may be empty). */
    public void setHighlights(List<io.blockcompanion.core.model.BlockPos> cells) {
        highlights = cells;
    }

    /** The ghost cell easy place would fill, outlined; null for none. */
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
            meshes.entrySet().removeIf(e -> {
                if (now.contains(e.getKey())) return false;
                e.getValue().close();
                return true;
            });
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

    /**
     * Draws the placement. {@code modelView} is the camera rotation (no translation), as the level renderer uses it.
     */
    public void render(Placement p, Layers layers, Matrix4f modelView, Matrix4f projection, Vec3 cam,
                       Frustum frustum, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
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
        // Far to near, so nearer translucent ghosts blend over farther ones.
        visible.sort((a, b) -> Double.compare(sectionDist2(b.getKey(), cam), sectionDist2(a.getKey(), cam)));
        resort(visible, cam);

        // Missing blocks: the game's own models, near-solid, with a gentle pulse.
        RenderType ghostType = RenderType.translucent();
        ghostType.setupRenderState();
        ShaderInstance ghostShader = GameRenderer.getRendertypeTranslucentShader();
        if (ghostShader != null) {
            if (ghostShader.CHUNK_OFFSET != null) ghostShader.CHUNK_OFFSET.set(0f, 0f, 0f);
            float pulse = config.ghostShimmer ? shimmer() : 1f;
            RenderSystem.setShaderColor(pulse, pulse, pulse, config.ghostShimmer ? 0.94f + 0.06f * pulse : 1f);
            for (var e : visible) {
                VertexBuffer vb = e.getValue().ghosts;
                if (vb == null) continue;
                vb.bind();
                vb.drawWithShader(sectionMatrix(modelView, e.getKey(), cam), projection, ghostShader);
            }
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            renderPops(mc, cam, ghostShader, modelView, projection);
        }
        VertexBuffer.unbind();
        ghostType.clearRenderState();

        if (config.ghostBlockEntities) renderBlockEntities(mc, level, visible, cam, partialTick);

        // Wrong / extra / model-less blocks: tinted boxes and outlines, then the helpers (the bounding box is BoxRenderer's).
        ShaderInstance colorShader = GameRenderer.getPositionColorShader();
        if (colorShader == null) return;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        for (var e : visible) {
            VertexBuffer vb = e.getValue().overlays;
            if (vb == null) continue;
            vb.bind();
            vb.drawWithShader(sectionMatrix(modelView, e.getKey(), cam), projection, colorShader);
        }
        RenderSystem.lineWidth(2f);
        for (var e : visible) {
            VertexBuffer vb = e.getValue().lines;
            if (vb == null) continue;
            vb.bind();
            vb.drawWithShader(sectionMatrix(modelView, e.getKey(), cam), projection, colorShader);
        }
        VertexBuffer.unbind();
        drawHelpers(cam);
        RenderSystem.lineWidth(1f);
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
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

    /** Re-sorts the quads of the nearest sections whose sort is stale (the camera moved). */
    private void resort(List<Map.Entry<Long, SectionMesh>> visibleFarToNear, Vec3 cam) {
        int done = 0;
        for (int i = visibleFarToNear.size() - 1; i >= 0 && done < RESORTS_PER_FRAME; i--) {
            SectionMesh m = visibleFarToNear.get(i).getValue();
            if (m.ghosts == null || m.sort == null) continue;
            double dx = cam.x - m.sortX, dy = cam.y - m.sortY, dz = cam.z - m.sortZ;
            if (!Double.isNaN(m.sortX) && dx * dx + dy * dy + dz * dz < RESORT_DISTANCE * RESORT_DISTANCE) continue;
            io.blockcompanion.core.model.BlockPos s = io.blockcompanion.core.model.BlockPos.unpack(visibleFarToNear.get(i).getKey());
            ByteBufferBuilder.Result indices = m.sort.buildSortedIndexBuffer(bytes(3),
                    VertexSorting.byDistance((float) (cam.x - (s.x() << 4)), (float) (cam.y - (s.y() << 4)), (float) (cam.z - (s.z() << 4))));
            if (indices != null) {
                m.ghosts.bind();
                m.ghosts.uploadIndexBuffer(indices);
            }
            m.sortX = cam.x;
            m.sortY = cam.y;
            m.sortZ = cam.z;
            done++;
        }
    }

    private static Matrix4f sectionMatrix(Matrix4f modelView, long key, Vec3 cam) {
        io.blockcompanion.core.model.BlockPos s = io.blockcompanion.core.model.BlockPos.unpack(key);
        return new Matrix4f(modelView).translate((float) ((s.x() << 4) - cam.x), (float) ((s.y() << 4) - cam.y), (float) ((s.z() << 4) - cam.z));
    }

    /** The easy-place target outline and the material helper's marks, drawn fresh each frame (camera-relative). */
    private void drawHelpers(Vec3 cam) {
        if (target == null && highlights.isEmpty()) return;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        float pulse = (float) (0.5 + 0.5 * Math.sin((System.currentTimeMillis() % 1400L) / 1400.0 * Math.PI * 2));
        int helper = ((int) (0x70 + 0x60 * pulse) << 24) | BlockCompanionClient.config().colors.get(Palette.Entry.HELPER);
        for (io.blockcompanion.core.model.BlockPos c : highlights) {
            if (target != null && c.x() == target.getX() && c.y() == target.getY() && c.z() == target.getZ()) continue;
            float e = 0.02f;
            edges(b, (float) (c.x() - cam.x) - e, (float) (c.y() - cam.y) - e, (float) (c.z() - cam.z) - e, 1 + 2 * e, 1 + 2 * e, 1 + 2 * e, helper);
        }
        if (target != null) {
            float e = 0.003f;
            edges(b, (float) (target.getX() - cam.x) - e, (float) (target.getY() - cam.y) - e, (float) (target.getZ() - cam.z) - e,
                    1 + 2 * e, 1 + 2 * e, 1 + 2 * e, 0xE6FFFFFF);
        }
        MeshData m = b.build();
        if (m != null) BufferUploader.drawWithShader(m);
    }

    /** Filled ghosts growing and fading out for a moment. */
    private void renderPops(Minecraft mc, Vec3 cam, ShaderInstance shader, Matrix4f modelView, Matrix4f projection) {
        if (pops.isEmpty()) return;
        long now = System.currentTimeMillis();
        pops.removeIf(pp -> now - pp.start() > POP_MS);
        if (pops.isEmpty()) return;
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        BufferBuilder b = new BufferBuilder(bytes(0), VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        PoseStack pose = new PoseStack();
        for (Pop pp : pops) {
            float t = (now - pp.start()) / (float) POP_MS;
            float scale = 1.02f + 0.18f * t;
            GhostConsumer out = new GhostConsumer(b, 0.7f * (1 - t) * (1 - t), true, true);
            pose.pushPose();
            pose.translate(pp.pos().getX() - cam.x + 0.5, pp.pos().getY() - cam.y + 0.5, pp.pos().getZ() - cam.z + 0.5);
            pose.scale(scale, scale, scale);
            pose.translate(-0.5, -0.5, -0.5);
            dispatcher.renderBatched(pp.state(), pp.pos(), mc.level, pose, out, false, random);
            pose.popPose();
        }
        MeshData data = b.build();
        if (data == null) return;
        VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        vb.bind();
        vb.upload(data);
        vb.drawWithShader(modelView, projection, shader);
        vb.close();
    }

    /** Chests, signs, beds, banners, heads... through their block entity renderers, nearest first. */
    private void renderBlockEntities(Minecraft mc, Level level, List<Map.Entry<Long, SectionMesh>> visible, Vec3 cam, float partialTick) {
        MultiBufferSource.BufferSource source = mc.renderBuffers().bufferSource();
        boolean tint = BlockCompanionClient.config().ghostShimmer;
        float alpha = BlockCompanionClient.config().ghostAlpha;
        MultiBufferSource ghostly = type -> new GhostConsumer(source.getBuffer(type), alpha, tint, false);
        PoseStack pose = new PoseStack();
        int drawn = 0;
        for (int i = visible.size() - 1; i >= 0 && drawn < BE_PER_FRAME; i--) {
            for (BeGhost g : visible.get(i).getValue().blockEntities) {
                if (drawn >= BE_PER_FRAME) break;
                if (g.pos().distToCenterSqr(cam) > BE_DISTANCE * BE_DISTANCE) continue;
                BlockEntityRenderer<BlockEntity> renderer = mc.getBlockEntityRenderDispatcher().getRenderer(g.be());
                if (renderer == null) continue;
                pose.pushPose();
                pose.translate(g.pos().getX() - cam.x, g.pos().getY() - cam.y, g.pos().getZ() - cam.z);
                try {
                    renderer.render(g.be(), partialTick, pose, ghostly, LevelRenderer.getLightColor(level, g.pos()), OverlayTexture.NO_OVERLAY);
                } catch (RuntimeException ex) {
                    // A renderer that can't cope with a detached block entity: leave that ghost out.
                }
                pose.popPose();
                drawn++;
            }
        }
        source.endBatch();
    }

    // ---- meshing --------------------------------------------------------------------------------------------------

    /** 0 ghosts, 1 overlay quads, 2 lines, 3 sorted indices. */
    private ByteBufferBuilder bytes(int which) {
        return switch (which) {
            case 0 -> ghostBytes == null ? ghostBytes = new ByteBufferBuilder(1 << 18) : ghostBytes;
            case 1 -> overlayBytes == null ? overlayBytes = new ByteBufferBuilder(1 << 16) : overlayBytes;
            case 2 -> lineBytes == null ? lineBytes = new ByteBufferBuilder(1 << 14) : lineBytes;
            default -> sortBytes == null ? sortBytes = new ByteBufferBuilder(1 << 16) : sortBytes;
        };
    }

    private void rebuildSome(Level level, Layers layers, Vec3 cam) {
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
                build(level, layers, k, cam);
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

    private void build(Level level, Layers layers, long key, Vec3 cam) {
        io.blockcompanion.core.model.BlockPos s = io.blockcompanion.core.model.BlockPos.unpack(key);
        int ox = s.x() << 4, oy = s.y() << 4, oz = s.z() << 4;
        int x0 = Math.max(ox, box.minX()), x1 = Math.min(ox + 15, box.maxX());
        int y0 = Math.max(oy, box.minY()), y1 = Math.min(oy + 15, box.maxY());
        int z0 = Math.max(oz, box.minZ()), z1 = Math.min(oz + 15, box.maxZ());

        ClientConfig config = BlockCompanionClient.config();
        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        SchematicView view = new SchematicView(level, placement, layers);
        BufferBuilder ghosts = new BufferBuilder(bytes(0), VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        BufferBuilder overlays = new BufferBuilder(bytes(1), VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        BufferBuilder lines = new BufferBuilder(bytes(2), VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        GhostConsumer ghostOut = new GhostConsumer(ghosts, config.ghostAlpha, config.ghostShimmer, false);
        PoseStack pose = new PoseStack();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<BeGhost> bes = new ArrayList<>();
        MarkMesh marks = new MarkMesh();

        for (int y = y0; y <= y1; y++) {
            if (!layers.isVisible(y - box.minY())) continue;
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    io.blockcompanion.core.model.BlockState want = placement.stateAt(x, y, z);
                    pos.set(x, y, z);
                    BlockState have = level.getBlockState(pos);
                    if (want.isAir() && have.isAir()) continue;
                    Compare.Result r = Compare.classify(want, StateMapper.toCore(have));
                    float lx = x - ox, ly = y - oy, lz = z - oz;
                    switch (r) {
                        case MISSING -> {
                            BlockState ghost = StateMapper.toMc(want);
                            boolean drawn = false;
                            if (ghost != null) {
                                BlockPos at = pos.immutable();
                                FluidState fluid = ghost.getFluidState();
                                if (ghost.getBlock() instanceof LiquidBlock && !fluid.isEmpty()) {
                                    dispatcher.renderLiquid(at, view, ghostOut, ghost, fluid);
                                    drawn = true;
                                }
                                if (ghost.getRenderShape() == RenderShape.MODEL) {
                                    pose.pushPose();
                                    pose.translate(lx, ly, lz);
                                    dispatcher.renderBatched(ghost, at, view, pose, ghostOut, true, random);
                                    pose.popPose();
                                    drawn = true;
                                }
                                if (config.ghostBlockEntities) {
                                    BlockEntity be = blockEntity(level, at, ghost, x, y, z);
                                    if (be != null) {
                                        bes.add(new BeGhost(at, be));
                                        edges(lines, lx - INFLATE, ly - INFLATE, lz - INFLATE, 1 + 2 * INFLATE, 1 + 2 * INFLATE, 1 + 2 * INFLATE, color(Palette.Entry.BLOCK_ENTITY, BE_LINE_ALPHA));
                                        drawn = true;
                                    }
                                }
                            }
                            if (!drawn) cube(overlays, lx, ly, lz, NO_MODEL_FILL);
                        }
                        case WRONG -> marks.set(x - ox, y - oy, z - oz, MARK_WRONG);
                        case EXTRA -> marks.set(x - ox, y - oy, z - oz, MARK_EXTRA);
                        default -> {
                        }
                    }
                }
            }
        }
        if (!marks.isEmpty()) mergeMarks(level, layers, marks, ox, oy, oz, overlays, lines);

        SectionMesh mesh = meshes.computeIfAbsent(key, k -> new SectionMesh());
        MeshData ghostData = ghosts.build();
        mesh.sort = null;
        mesh.sortX = Double.NaN;
        if (ghostData != null) {
            mesh.sort = ghostData.sortQuads(bytes(3), VertexSorting.byDistance((float) (cam.x - ox), (float) (cam.y - oy), (float) (cam.z - oz)));
            mesh.sortX = cam.x;
            mesh.sortY = cam.y;
            mesh.sortZ = cam.z;
        }
        mesh.ghosts = upload(mesh.ghosts, ghostData);
        mesh.overlays = upload(mesh.overlays, overlays.build());
        mesh.lines = upload(mesh.lines, lines.build());
        mesh.blockEntities = bes.isEmpty() ? List.of() : bes;
        if (mesh.ghosts == null && mesh.overlays == null && mesh.lines == null && bes.isEmpty()) meshes.remove(key);
    }

    /**
     * Joins the section's wrong and in-the-way marks into one shell and outline per touching group, reading the marks
     * in the cells just outside the section so groups join across its borders.
     */
    private void mergeMarks(Level level, Layers layers, MarkMesh marks, int ox, int oy, int oz, VertexConsumer overlays, VertexConsumer lines) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int n = MarkMesh.SIZE;
        for (int ly = -1; ly <= n; ly++)
            for (int lz = -1; lz <= n; lz++)
                for (int lx = -1; lx <= n; lx++)
                    if (lx < 0 || lx == n || ly < 0 || ly == n || lz < 0 || lz == n)
                        marks.set(lx, ly, lz, markAt(level, layers, ox + lx, oy + ly, oz + lz, pos));
        int wrongFill = color(Palette.Entry.WRONG, MARK_FILL_ALPHA), wrongLine = color(Palette.Entry.WRONG, MARK_LINE_ALPHA);
        int extraFill = color(Palette.Entry.EXTRA, MARK_FILL_ALPHA), extraLine = color(Palette.Entry.EXTRA, MARK_LINE_ALPHA);
        // Grid line 16 of an axis is the next section's, unless the placement ends before it.
        marks.emit(INFLATE, ox + n > box.maxX(), oy + n > box.maxY(), oz + n > box.maxZ(), new MarkMesh.Sink() {
            @Override
            public void quad(int kind, float[] c) {
                int argb = kind == MARK_WRONG ? wrongFill : extraFill;
                for (int i = 0; i < 12; i += 3) overlays.addVertex(c[i], c[i + 1], c[i + 2]).setColor(argb);
            }

            @Override
            public void line(int kind, float ax, float ay, float az, float bx, float by, float bz) {
                int argb = kind == MARK_WRONG ? wrongLine : extraLine;
                lines.addVertex(ax, ay, az).setColor(argb);
                lines.addVertex(bx, by, bz).setColor(argb);
            }
        });
    }

    /** The mark a cell shows: wrong, in the way, or none (outside the placement or its shown layers). */
    private int markAt(Level level, Layers layers, int x, int y, int z, BlockPos.MutableBlockPos pos) {
        if (x < box.minX() || x > box.maxX() || y < box.minY() || y > box.maxY() || z < box.minZ() || z > box.maxZ()) return 0;
        if (!layers.isVisible(y - box.minY())) return 0;
        pos.set(x, y, z);
        return switch (Compare.classify(placement.stateAt(x, y, z), StateMapper.toCore(level.getBlockState(pos)))) {
            case WRONG -> MARK_WRONG;
            case EXTRA -> MARK_EXTRA;
            default -> 0;
        };
    }

    /**
     * A detached block entity to draw a ghost with, carrying the schematic's own data (sign text, banner patterns, head
     * owner) when it loads; null for blocks drawn without one.
     */
    private BlockEntity blockEntity(Level level, BlockPos at, BlockState ghost, int x, int y, int z) {
        if (!ghost.hasBlockEntity() || !(ghost.getBlock() instanceof EntityBlock eb)) return null;
        BlockEntity be;
        try {
            be = eb.newBlockEntity(at, ghost);
        } catch (RuntimeException e) {
            return null;
        }
        if (be == null || !BE_TYPES.contains(be.getType())) return null;
        be.setLevel(level);
        // Sign text comes along when it is in this version's format; vanilla would log an error for every other sign.
        if (be instanceof SignBlockEntity) {
            io.blockcompanion.core.model.BlockPos local = placement.toLocal(x, y, z);
            io.blockcompanion.core.nbt.CompoundTag data = placement.structure().blockEntity(local);
            if (data != null && !data.isEmpty()) {
                try {
                    CompoundTag tag = TagParser.parseTag(Snbt.write(data));
                    if (signTextLoads(tag, "front_text", level) && signTextLoads(tag, "back_text", level)) {
                        be.loadWithComponents(tag, level.registryAccess());
                    }
                } catch (Exception e) {
                    // Old or foreign data: draw the ghost without it.
                }
            }
        }
        return be;
    }

    /** True if the sign side is absent or parses cleanly, checked without logging. */
    private static boolean signTextLoads(CompoundTag tag, String key, Level level) {
        if (!tag.contains(key)) return true;
        var ops = level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        return SignText.DIRECT_CODEC.parse(ops, tag.getCompound(key)).result().isPresent();
    }

    /** Uploads a mesh into a (reused) buffer; null when there is nothing to draw. */
    private static VertexBuffer upload(VertexBuffer vb, MeshData data) {
        if (data == null) {
            if (vb != null) vb.close();
            return null;
        }
        if (vb == null) vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();
        vb.upload(data);
        VertexBuffer.unbind();
        return vb;
    }

    /** The 12 edges of a box from {@code (x, y, z)} with size {@code (sx, sy, sz)}, as line pairs. */
    private static void edges(VertexConsumer b, float x, float y, float z, float sx, float sy, float sz, int argb) {
        float x1 = x + sx, y1 = y + sy, z1 = z + sz;
        float[][] c = {{x, y, z}, {x1, y, z}, {x1, y, z1}, {x, y, z1}, {x, y1, z}, {x1, y1, z}, {x1, y1, z1}, {x, y1, z1}};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] e : edges) {
            b.addVertex(c[e[0]][0], c[e[0]][1], c[e[0]][2]).setColor(argb);
            b.addVertex(c[e[1]][0], c[e[1]][1], c[e[1]][2]).setColor(argb);
        }
    }

    /** A slightly inflated cube, all six faces facing out. */
    private static void cube(VertexConsumer b, float x, float y, float z, int argb) {
        float a = x - INFLATE, bY = y - INFLATE, c = z - INFLATE;
        float d = x + 1 + INFLATE, e = y + 1 + INFLATE, f = z + 1 + INFLATE;
        quad(b, argb, a, bY, c, d, bY, c, d, bY, f, a, bY, f);
        quad(b, argb, a, e, c, a, e, f, d, e, f, d, e, c);
        quad(b, argb, a, bY, c, a, e, c, d, e, c, d, bY, c);
        quad(b, argb, a, bY, f, d, bY, f, d, e, f, a, e, f);
        quad(b, argb, a, bY, c, a, bY, f, a, e, f, a, e, c);
        quad(b, argb, d, bY, c, d, e, c, d, e, f, d, bY, f);
    }

    private static void quad(VertexConsumer b, int argb, float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4) {
        b.addVertex(x1, y1, z1).setColor(argb);
        b.addVertex(x2, y2, z2).setColor(argb);
        b.addVertex(x3, y3, z3).setColor(argb);
        b.addVertex(x4, y4, z4).setColor(argb);
    }
}
