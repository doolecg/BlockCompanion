package io.blockcompanion.core.easyplace;

import io.blockcompanion.core.easyplace.EasyPlacePlanner.Click;
import io.blockcompanion.core.easyplace.EasyPlacePlanner.Dir;
import io.blockcompanion.core.easyplace.EasyPlacePlanner.Family;
import io.blockcompanion.core.model.BlockState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EasyPlacePlannerTest {
    private static BlockState s(String text) {
        return BlockState.parse(text);
    }

    /** The planner's first click, checked against vanilla's rule written out independently here. */
    private static Click plan(BlockState target) {
        return EasyPlacePlanner.best(target, null, null);
    }

    private static boolean vanillaUpperHalf(Click c) {
        // Stairs, slabs: top when clicking a bottom face, or a side face above the middle.
        return c.face() == Dir.DOWN || (c.face().horizontal() && c.hitY() > 0.5);
    }

    @Test
    void familiesAreRecognised() {
        assertThat(EasyPlacePlanner.family(s("oak_stairs[facing=north,half=bottom,shape=straight]"))).isEqualTo(Family.STAIRS);
        assertThat(EasyPlacePlanner.family(s("stone_slab[type=top]"))).isEqualTo(Family.SLAB);
        assertThat(EasyPlacePlanner.family(s("oak_log[axis=x]"))).isEqualTo(Family.PILLAR);
        assertThat(EasyPlacePlanner.family(s("oak_door[facing=east,half=lower,hinge=left]"))).isEqualTo(Family.DOOR);
        assertThat(EasyPlacePlanner.family(s("spruce_trapdoor[facing=east,half=top]"))).isEqualTo(Family.TRAPDOOR);
        assertThat(EasyPlacePlanner.family(s("furnace[facing=west,lit=false]"))).isEqualTo(Family.FACING_AWAY);
        assertThat(EasyPlacePlanner.family(s("oak_fence_gate[facing=west]"))).isEqualTo(Family.FACING_PLAYER);
        assertThat(EasyPlacePlanner.family(s("observer[facing=up]"))).isEqualTo(Family.LOOK);
        assertThat(EasyPlacePlanner.family(s("piston[facing=down,extended=false]"))).isEqualTo(Family.LOOK_AWAY);
        assertThat(EasyPlacePlanner.family(s("hopper[facing=east]"))).isEqualTo(Family.HOPPER);
        assertThat(EasyPlacePlanner.family(s("oak_sign[rotation=4]"))).isEqualTo(Family.ROTATION_FACING);
        assertThat(EasyPlacePlanner.family(s("creeper_head[rotation=4]"))).isEqualTo(Family.ROTATION_YAW);
        assertThat(EasyPlacePlanner.family(s("stone_button[face=wall,facing=north]"))).isEqualTo(Family.ATTACHED);
        assertThat(EasyPlacePlanner.family(s("wall_torch[facing=north]"))).isEqualTo(Family.WALL);
        assertThat(EasyPlacePlanner.family(s("anvil[facing=north]"))).isEqualTo(Family.FACING_CLOCKWISE);
        assertThat(EasyPlacePlanner.family(s("stone"))).isEqualTo(Family.PLAIN);
    }

    @Test
    void stairsFaceThePlayerDirectionAndPickTheHalfByHeight() {
        for (Dir facing : new Dir[]{Dir.NORTH, Dir.SOUTH, Dir.EAST, Dir.WEST}) {
            for (String half : new String[]{"bottom", "top"}) {
                Click c = plan(s("oak_stairs[facing=" + facing.id() + ",half=" + half + ",shape=straight,waterlogged=false]"));
                assertThat(c.horizontal()).isEqualTo(facing);
                assertThat(vanillaUpperHalf(c)).isEqualTo(half.equals("top"));
            }
        }
    }

    @Test
    void slabsTopBottomAndASecondClickForDouble() {
        assertThat(vanillaUpperHalf(plan(s("stone_slab[type=top,waterlogged=false]")))).isTrue();
        assertThat(vanillaUpperHalf(plan(s("stone_slab[type=bottom,waterlogged=false]")))).isFalse();
        // Double: click the existing bottom slab from above (or its upper side), or the top slab from below.
        BlockState dbl = s("stone_slab[type=double,waterlogged=false]");
        Click onBottom = EasyPlacePlanner.best(dbl, null, s("stone_slab[type=bottom,waterlogged=false]"));
        assertThat(onBottom.face() == Dir.UP || (onBottom.face().horizontal() && onBottom.hitY() > 0.5)).isTrue();
        Click onTop = EasyPlacePlanner.best(dbl, null, s("stone_slab[type=top,waterlogged=false]"));
        assertThat(onTop.face() == Dir.DOWN || (onTop.face().horizontal() && onTop.hitY() <= 0.5)).isTrue();
    }

    @Test
    void logsClickAFaceOnTheirAxis() {
        for (String axis : new String[]{"x", "y", "z"}) {
            Click c = plan(s("oak_log[axis=" + axis + "]"));
            assertThat(String.valueOf(c.face().axis())).isEqualTo(axis);
        }
    }

    @Test
    void doorsFaceThePlayerAndPickTheHingeByClickPosition() {
        for (Dir facing : new Dir[]{Dir.NORTH, Dir.SOUTH, Dir.EAST, Dir.WEST}) {
            for (String hinge : new String[]{"left", "right"}) {
                Click c = plan(s("oak_door[facing=" + facing.id() + ",half=lower,hinge=" + hinge + ",open=false,powered=false]"));
                assertThat(c.horizontal()).isEqualTo(facing);
                // Vanilla's fallback: facing north, the left half of the cell (x below the middle) gives a left hinge.
                double along = switch (facing) {
                    case NORTH -> 0.5 - c.hitX();
                    case SOUTH -> c.hitX() - 0.5;
                    case EAST -> 0.5 - c.hitZ();
                    default -> c.hitZ() - 0.5;
                };
                assertThat(along >= 0 ? "left" : "right").isEqualTo(hinge);
            }
        }
    }

    @Test
    void facingBlocksTurnToThePlayerOrAway() {
        // A furnace faces the player: placed while looking west, it faces east.
        assertThat(plan(s("furnace[facing=east,lit=false]")).horizontal()).isEqualTo(Dir.WEST);
        assertThat(plan(s("oak_fence_gate[facing=east,in_wall=false,open=false,powered=false]")).horizontal()).isEqualTo(Dir.EAST);
        // Anvils turn clockwise from the look direction.
        assertThat(plan(s("anvil[facing=east]")).horizontal()).isEqualTo(Dir.NORTH);
    }

    @Test
    void lookingBlocksUseThePitch() {
        Click up = plan(s("piston[facing=up,extended=false]"));
        assertThat(Dir.nearestLooking(up.yaw(), up.pitch())).isEqualTo(Dir.DOWN);
        Click obs = plan(s("observer[facing=down,powered=false]"));
        assertThat(Dir.nearestLooking(obs.yaw(), obs.pitch())).isEqualTo(Dir.DOWN);
        Click disp = plan(s("dispenser[facing=north,triggered=false]"));
        assertThat(Dir.nearestLooking(disp.yaw(), disp.pitch())).isEqualTo(Dir.SOUTH);
    }

    @Test
    void faceDecidedBlocks() {
        assertThat(plan(s("end_rod[facing=west]")).face()).isEqualTo(Dir.WEST);
        assertThat(plan(s("hopper[enabled=true,facing=east]")).face()).isEqualTo(Dir.WEST);
        assertThat(plan(s("hopper[enabled=true,facing=down]")).face().horizontal()).isFalse();
        Click trap = plan(s("oak_trapdoor[facing=north,half=top,open=false,powered=false,waterlogged=false]"));
        assertThat(trap.face()).isNotEqualTo(Dir.UP);
        assertThat(trap.horizontal()).isEqualTo(Dir.SOUTH);
    }

    @Test
    void rotationsAndWallBlocks() {
        Click sign = plan(s("oak_sign[rotation=4,waterlogged=false]"));
        assertThat(EasyPlacePlanner.segment(sign.yaw() + 180)).isEqualTo(4);
        Click head = plan(s("skeleton_skull[rotation=12]"));
        assertThat(EasyPlacePlanner.segment(head.yaw())).isEqualTo(12);
        Click button = plan(s("stone_button[face=wall,facing=east,powered=false]"));
        assertThat(Dir.nearestLooking(button.yaw(), button.pitch())).isEqualTo(Dir.WEST);
        Click floor = plan(s("lever[face=floor,facing=south,powered=false]"));
        assertThat(Dir.nearestLooking(floor.yaw(), floor.pitch())).isEqualTo(Dir.DOWN);
        assertThat(floor.horizontal()).isEqualTo(Dir.SOUTH);
        Click torch = plan(s("wall_torch[facing=north]"));
        assertThat(Dir.nearestLooking(torch.yaw(), torch.pitch())).isEqualTo(Dir.SOUTH);
    }

    @Test
    void blocksWithoutOrientationKeepThePlayersOwnClick() {
        Click mine = new Click(Dir.EAST, 1, 0.3, 0.6, 37f, 12f, false);
        assertThat(EasyPlacePlanner.best(s("stone"), mine, null)).isEqualTo(mine);
        // Stairs the player already faces right for keep the player's click too.
        Click looking = new Click(Dir.UP, 0.5, 1, 0.5, 180f, 40f, false);
        assertThat(EasyPlacePlanner.best(s("oak_stairs[facing=north,half=bottom,shape=straight]"), looking, null)).isEqualTo(looking);
        // ...but not when they face the wrong way.
        assertThat(EasyPlacePlanner.best(s("oak_stairs[facing=south,half=bottom,shape=straight]"), looking, null)).isNotEqualTo(looking);
    }

    @Test
    void predictionMatchesVanillaForExamples() {
        Click c = Click.on(Dir.NORTH, 0.5, 0.8, 0.5, 90f, 0f);
        Map<String, String> p = EasyPlacePlanner.predict(Family.STAIRS, c, null);
        assertThat(p).containsEntry("facing", "west").containsEntry("half", "top");
        assertThat(EasyPlacePlanner.predict(Family.PILLAR, Click.on(Dir.UP, 0.5, 1, 0.5, 0, 0), null)).containsEntry("axis", "y");
        assertThat(Dir.fromYaw(-90)).isEqualTo(Dir.EAST);
        assertThat(Dir.fromYaw(44)).isEqualTo(Dir.SOUTH);
        assertThat(Dir.fromYaw(46)).isEqualTo(Dir.WEST);
        assertThat(EasyPlacePlanner.candidates(s("stone"), null, null)).isNotEmpty();
    }
}
