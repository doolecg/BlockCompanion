package io.blockcompanion.core.chests;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What linked chests remember: versions, the totals worked out once per version, and items the mod took out. */
class LinkedChestsTest {
    static final LinkedChests.Pos A = new LinkedChests.Pos("minecraft:overworld", 1, 64, 0);
    static final LinkedChests.Pos B = new LinkedChests.Pos("minecraft:overworld", 2, 64, 0);

    @Test
    void totalsAreWorkedOutOncePerVersion() {
        LinkedChests c = new LinkedChests();
        c.link(A);
        c.link(B);
        assertThat(c.unknown()).isEqualTo(2);
        c.setContents(A, Map.of("minecraft:stone", 10L), 1, LinkedChests.Source.OPENED);
        c.setContents(B, Map.of("minecraft:stone", 5L, "minecraft:dirt", 2L), 1, LinkedChests.Source.LIVE);

        Map<String, Long> t = c.totals();
        assertThat(t).containsEntry("minecraft:stone", 15L).containsEntry("minecraft:dirt", 2L);
        assertThat(c.unknown()).isZero();
        // Asked again without a change: the same map, not a new count.
        assertThat(c.totals()).isSameAs(t);

        c.setContents(A, Map.of("minecraft:stone", 1L), 2, LinkedChests.Source.OPENED);
        assertThat(c.totals()).isNotSameAs(t).containsEntry("minecraft:stone", 6L);
        c.unlink(B);
        assertThat(c.totals()).containsOnlyKeys("minecraft:stone");
    }

    @Test
    void theSameContentsSeenAgainDontBumpTheVersion() {
        LinkedChests c = new LinkedChests();
        c.link(A);
        c.setContents(A, Map.of("minecraft:stone", 10L), 1, LinkedChests.Source.LIVE);
        long v = c.version();
        Map<String, Long> t = c.totals();

        // A later look finding the same items (every server list, every reopened chest): nothing changes.
        c.setContents(A, Map.of("minecraft:stone", 10L), 5, LinkedChests.Source.LIVE);
        assertThat(c.version()).isEqualTo(v);
        assertThat(c.totals()).isSameAs(t);
        assertThat(c.seen(A).when()).isEqualTo(5);

        c.setContents(A, Map.of("minecraft:stone", 9L), 6, LinkedChests.Source.LIVE);
        assertThat(c.version()).isGreaterThan(v);
        // Chests that aren't linked are ignored.
        v = c.version();
        c.setContents(B, Map.of("minecraft:stone", 9L), 6, LinkedChests.Source.LIVE);
        assertThat(c.version()).isEqualTo(v);
    }

    @Test
    void removedItemsComeOffTheStoredContents() {
        LinkedChests c = new LinkedChests();
        c.link(A);
        assertThat(c.removed(A, "minecraft:stone", 1)).isFalse(); // not known yet
        c.setContents(A, Map.of("minecraft:stone", 10L, "minecraft:dirt", 3L), 1, LinkedChests.Source.LIVE);
        long v = c.version();

        assertThat(c.removed(A, "minecraft:stone", 4)).isTrue();
        assertThat(c.version()).isGreaterThan(v);
        assertThat(c.totals()).containsEntry("minecraft:stone", 6L).containsEntry("minecraft:dirt", 3L);
        assertThat(c.removed(A, "minecraft:dirt", 3)).isTrue();
        assertThat(c.seen(A).items()).doesNotContainKey("minecraft:dirt");
        assertThat(c.seen(A).source()).isEqualTo(LinkedChests.Source.LIVE);

        // More than it was thought to hold: says so, so the caller reads the chest again.
        assertThat(c.removed(A, "minecraft:stone", 7)).isFalse();
        assertThat(c.totals()).doesNotContainKey("minecraft:stone");
        assertThat(c.removed(B, "minecraft:stone", 1)).isFalse();
    }

    @Test
    void openedContentsStayUntilTheChestIsOpenedAgain() {
        LinkedChests c = new LinkedChests();
        c.link(A);
        // Closed after opening: recorded.
        c.setContents(A, Map.of("minecraft:stone", 10L), 1, LinkedChests.Source.OPENED);
        long v = c.version();
        assertThat(c.seen(A).source()).isEqualTo(LinkedChests.Source.OPENED);
        // Nothing else changes them: asking again is free and leaves the version alone.
        for (int i = 0; i < 100; i++) assertThat(c.totals()).containsEntry("minecraft:stone", 10L);
        assertThat(c.version()).isEqualTo(v);
        // Opened and closed again: the new contents replace the old.
        c.setContents(A, Map.of("minecraft:stone", 4L), 2, LinkedChests.Source.OPENED);
        assertThat(c.totals()).containsEntry("minecraft:stone", 4L);
        // What the mod took comes off without opening it, and it still counts as opened.
        assertThat(c.removed(A, "minecraft:stone", 1)).isTrue();
        assertThat(c.seen(A).source()).isEqualTo(LinkedChests.Source.OPENED);
        assertThat(c.totals()).containsEntry("minecraft:stone", 3L);
    }

    @Test
    void anOlderOpenedLookDoesntReplaceNewerServerContents() {
        LinkedChests c = new LinkedChests();
        c.link(A);
        c.setContents(A, Map.of("minecraft:stone", 10L), 5, LinkedChests.Source.LIVE);
        long v = c.version();
        c.setContents(A, Map.of("minecraft:stone", 99L), 4, LinkedChests.Source.OPENED);
        assertThat(c.version()).isEqualTo(v);
        assertThat(c.totals()).containsEntry("minecraft:stone", 10L);
        c.setContents(A, Map.of("minecraft:stone", 7L), 6, LinkedChests.Source.OPENED);
        assertThat(c.totals()).containsEntry("minecraft:stone", 7L);
    }
}
