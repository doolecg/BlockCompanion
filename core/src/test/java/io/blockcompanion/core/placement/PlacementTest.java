package io.blockcompanion.core.placement;

import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.model.Structure;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlacementTest {
    /** A 3 wide (x), 2 tall, 5 deep (z) block with a marker stair at the local origin. */
    private static Structure sample() {
        Structure s = new Structure();
        for (int x = 0; x < 3; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 5; z++) s.set(x, y, z, BlockState.of("stone"));
        s.set(0, 0, 0, BlockState.parse("oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"));
        return s;
    }

    @Test
    void worldAndLocalAreInverse() {
        Placement p = new Placement("s", sample(), new BlockPos(100, 64, -20));
        for (int r = 0; r < 4; r++) {
            for (boolean mirror : new boolean[]{false, true}) {
                p.setOrientation(r, mirror);
                Box box = p.worldBox();
                for (int x = 0; x < 3; x++) {
                    for (int z = 0; z < 5; z++) {
                        BlockPos w = p.toWorld(x, 1, z);
                        assertThat(box.contains(w.x(), w.y(), w.z())).isTrue();
                        assertThat(p.toLocal(w.x(), w.y(), w.z())).isEqualTo(new BlockPos(x, 1, z));
                    }
                }
                assertThat(box.min()).isEqualTo(new BlockPos(100, 64, -20));
            }
        }
    }

    @Test
    void quarterTurnSwapsTheBoxAndTurnsStates() {
        Placement p = new Placement("s", sample(), BlockPos.ORIGIN);
        assertThat(p.worldBox()).isEqualTo(new Box(0, 0, 0, 2, 1, 4));
        p.setOrientation(1, false);
        Box b = p.worldBox();
        assertThat(b.sizeX()).isEqualTo(5);
        assertThat(b.sizeZ()).isEqualTo(3);
        // Clockwise: local north-west corner goes to the north-east, and the stair now faces east.
        BlockPos stair = p.toWorld(0, 0, 0);
        assertThat(stair).isEqualTo(new BlockPos(4, 0, 0));
        assertThat(p.stateAt(stair.x(), stair.y(), stair.z()).get("facing")).isEqualTo("east");
    }

    @Test
    void rotateKeepsTheCentre() {
        Placement p = new Placement("s", sample(), new BlockPos(10, 0, 10));
        Box before = p.worldBox();
        p.rotate(1);
        Box after = p.worldBox();
        assertThat(Math.abs((after.minX() + after.maxX()) - (before.minX() + before.maxX()))).isLessThanOrEqualTo(1);
        assertThat(Math.abs((after.minZ() + after.maxZ()) - (before.minZ() + before.maxZ()))).isLessThanOrEqualTo(1);
        p.rotate(3);
        assertThat(p.worldBox()).isEqualTo(before);
    }

    @Test
    void mirrorFlipsEastWest() {
        Placement p = new Placement("s", sample(), BlockPos.ORIGIN);
        p.toggleMirror();
        assertThat(p.worldBox()).isEqualTo(new Box(0, 0, 0, 2, 1, 4));
        assertThat(p.toWorld(0, 0, 0)).isEqualTo(new BlockPos(2, 0, 0));
        BlockState east = BlockState.parse("oak_stairs[facing=east,half=bottom,shape=inner_left,waterlogged=false]");
        Structure s = new Structure();
        s.set(0, 0, 0, east);
        Placement q = new Placement("q", s, BlockPos.ORIGIN);
        q.toggleMirror();
        BlockState m = q.stateAt(0, 0, 0);
        assertThat(m.get("facing")).isEqualTo("west");
        assertThat(m.get("shape")).isEqualTo("inner_right");
    }

    @Test
    void outsideTheBoxIsAir() {
        Placement p = new Placement("s", sample(), BlockPos.ORIGIN);
        assertThat(p.stateAt(-1, 0, 0)).isSameAs(BlockState.AIR);
        assertThat(p.stateAt(0, 2, 0)).isSameAs(BlockState.AIR);
        assertThat(p.hasBlockAt(1, 1, 1)).isTrue();
    }

    @Test
    void editsBumpTheVersion() {
        Placement p = new Placement("s", sample(), BlockPos.ORIGIN);
        long v = p.version();
        p.move(1, 0, 0);
        assertThat(p.version()).isGreaterThan(v);
        v = p.version();
        p.move(0, 0, 0);
        assertThat(p.version()).isEqualTo(v);
        assertThat(p.origin()).isEqualTo(new BlockPos(1, 0, 0));
    }

    @Test
    void unnormalisedStructuresAreShifted() {
        Structure s = new Structure();
        s.set(5, 7, 9, BlockState.of("stone"));
        Placement p = new Placement("s", s, BlockPos.ORIGIN);
        assertThat(p.worldBox()).isEqualTo(new Box(0, 0, 0, 0, 0, 0));
        assertThat(p.stateAt(0, 0, 0).path()).isEqualTo("stone");
    }
}
