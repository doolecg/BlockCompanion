package io.blockcompanion.core.items;

import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.model.StructureEntity;
import io.blockcompanion.core.nbt.CompoundTag;
import io.blockcompanion.core.placement.Placement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Mirrors Resource Tracker's TallyTest, with a placement standing in for a BlockDesigner layer. */
class ItemCountTest {
    private static Map<String, Long> asMap(List<ItemCount.Need> needs) {
        return needs.stream().collect(Collectors.toMap(ItemCount.Need::item, ItemCount.Need::count));
    }

    private static Structure house() {
        Structure s = new Structure();
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) s.set(x, 0, z, BlockState.of("oak_planks"));
        s.set(0, 1, 0, BlockState.parse("oak_door[half=lower,facing=north,hinge=left,open=false,powered=false]"));
        s.set(0, 2, 0, BlockState.parse("oak_door[half=upper,facing=north,hinge=left,open=false,powered=false]"));
        s.set(1, 1, 0, BlockState.parse("stone_slab[type=double,waterlogged=false]"));
        s.addEntity(new StructureEntity(2.5, 1, 2.5, new CompoundTag().putString("id", "minecraft:villager")));
        s.addEntity(new StructureEntity(3.5, 1, 3.5, new CompoundTag().putString("id", "minecraft:armor_stand")));
        return s;
    }

    @Test
    void countsItemsMostFirst() {
        List<ItemCount.Need> needs = ItemCount.count(new Placement("house", house(), BlockPos.ORIGIN), null, false);
        assertThat(needs.getFirst().item()).isEqualTo("minecraft:oak_planks");
        assertThat(asMap(needs)).containsExactlyInAnyOrderEntriesOf(Map.of("minecraft:oak_planks", 16L, "minecraft:oak_door", 1L,
                "minecraft:stone_slab", 2L, "minecraft:armor_stand", 1L));
        assertThat(asMap(ItemCount.count(new Placement("house", house(), BlockPos.ORIGIN), null, true)))
                .containsEntry("minecraft:villager_spawn_egg", 1L);
    }

    @Test
    void onlyInsideTheBox() {
        Placement p = new Placement("house", house(), new BlockPos(10, 0, 0));
        // World x 10..11, y 0..1, z 0: two planks, the door's lower half and the double slab.
        Map<String, Long> m = asMap(ItemCount.count(p, new Box(10, 0, 0, 11, 1, 0), false));
        assertThat(m).containsExactlyInAnyOrderEntriesOf(Map.of("minecraft:oak_planks", 2L, "minecraft:oak_door", 1L, "minecraft:stone_slab", 2L));
    }

    @Test
    void rotationDoesNotChangeTheCount() {
        Placement p = new Placement("house", house(), BlockPos.ORIGIN);
        Map<String, Long> before = asMap(ItemCount.count(p, null, false));
        p.rotate(1);
        p.toggleMirror();
        assertThat(asMap(ItemCount.count(p, p.worldBox(), false))).isEqualTo(before);
    }

    @Test
    void rowsSortByMostMissing() {
        List<ItemCount.Need> needs = List.of(new ItemCount.Need("minecraft:stone", 100, null), new ItemCount.Need("minecraft:glass", 10, null),
                new ItemCount.Need("minecraft:torch", 5, null));
        List<ItemCount.Row> rows = ItemCount.rows(needs, Map.of("minecraft:stone", 95L, "minecraft:torch", 9L));
        assertThat(rows).extracting(ItemCount.Row::item).containsExactly("minecraft:glass", "minecraft:stone", "minecraft:torch");
        assertThat(rows.get(0).missing()).isEqualTo(10);
        assertThat(rows.get(1).missing()).isEqualTo(5);
        assertThat(rows.get(2).missing()).isZero();
    }
}
