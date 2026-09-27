package io.blockcompanion.core.compare;

import io.blockcompanion.core.model.BlockState;
import org.junit.jupiter.api.Test;

import static io.blockcompanion.core.compare.Compare.Result.CORRECT;
import static io.blockcompanion.core.compare.Compare.Result.EMPTY;
import static io.blockcompanion.core.compare.Compare.Result.EXTRA;
import static io.blockcompanion.core.compare.Compare.Result.MISSING;
import static io.blockcompanion.core.compare.Compare.Result.WRONG;
import static org.assertj.core.api.Assertions.assertThat;

class CompareTest {
    private static Compare.Result cmp(String expected, String actual) {
        return Compare.classify(BlockState.parse(expected), BlockState.parse(actual));
    }

    @Test
    void basics() {
        assertThat(cmp("air", "air")).isEqualTo(EMPTY);
        assertThat(cmp("stone", "stone")).isEqualTo(CORRECT);
        assertThat(cmp("stone", "air")).isEqualTo(MISSING);
        assertThat(cmp("stone", "dirt")).isEqualTo(WRONG);
        assertThat(cmp("air", "dirt")).isEqualTo(EXTRA);
    }

    @Test
    void clutterCountsAsEmpty() {
        assertThat(cmp("air", "short_grass")).isEqualTo(EMPTY);
        assertThat(cmp("air", "water[level=0]")).isEqualTo(EMPTY);
        assertThat(cmp("stone", "short_grass")).isEqualTo(MISSING);
        assertThat(cmp("water[level=0]", "water[level=0]")).isEqualTo(CORRECT);
    }

    @Test
    void facingMattersConnectionsDoNot() {
        assertThat(cmp("oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
                "oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]")).isEqualTo(WRONG);
        assertThat(cmp("oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
                "oak_stairs[facing=north,half=bottom,shape=inner_left,waterlogged=true]")).isEqualTo(CORRECT);
        assertThat(cmp("oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]",
                "oak_fence[east=true,north=false,south=true,waterlogged=false,west=false]")).isEqualTo(CORRECT);
        assertThat(cmp("oak_log[axis=y]", "oak_log[axis=x]")).isEqualTo(WRONG);
        assertThat(cmp("stone_slab[type=top,waterlogged=false]", "stone_slab[type=bottom,waterlogged=false]")).isEqualTo(WRONG);
    }
}
