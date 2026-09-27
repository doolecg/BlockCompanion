package io.blockcompanion.core.sync;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChunksTest {
    static byte[] random(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void counts() {
        assertThat(Chunks.count(0, 10)).isZero();
        assertThat(Chunks.count(1, 10)).isEqualTo(1);
        assertThat(Chunks.count(10, 10)).isEqualTo(1);
        assertThat(Chunks.count(11, 10)).isEqualTo(2);
    }

    @Test
    void reassemblesOutOfOrderWithDuplicates() {
        byte[] file = random(100_000, 1);
        String hash = Hashes.sha256(file);
        int cs = Protocol.CHUNK_SIZE;
        Chunks.Assembler a = new Chunks.Assembler(hash, file.length, cs);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < a.count(); i++) order.add(i);
        Collections.shuffle(order, new Random(2));
        Chunks.Assembler.Result last = null;
        for (int n = 0; n < order.size(); n++) {
            int i = order.get(n);
            last = a.accept(i, Chunks.slice(file, i, cs));
            // Send every chunk twice: repeats are harmless.
            if (n < order.size() - 1) assertThat(a.accept(i, Chunks.slice(file, i, cs))).isEqualTo(Chunks.Assembler.Result.OK);
        }
        assertThat(last).isEqualTo(Chunks.Assembler.Result.COMPLETE);
        assertThat(a.finish()).isEqualTo(file);
    }

    @Test
    void refusesBadIndexAndLength() {
        byte[] file = random(Protocol.CHUNK_SIZE + 5, 3);
        Chunks.Assembler a = new Chunks.Assembler(Hashes.sha256(file), file.length, Protocol.CHUNK_SIZE);
        assertThat(a.accept(-1, new byte[5])).isEqualTo(Chunks.Assembler.Result.BAD);
        assertThat(a.accept(2, new byte[5])).isEqualTo(Chunks.Assembler.Result.BAD);
        assertThat(a.accept(1, new byte[6])).isEqualTo(Chunks.Assembler.Result.BAD);
        assertThat(a.accept(0, new byte[5])).isEqualTo(Chunks.Assembler.Result.BAD);
        assertThat(a.received()).isZero();
    }

    @Test
    void hashMismatchIsCaught() {
        byte[] file = random(40_000, 4);
        Chunks.Assembler a = new Chunks.Assembler(Hashes.sha256(file), file.length, Protocol.CHUNK_SIZE);
        byte[] tampered = file.clone();
        tampered[20_000] ^= 1;
        for (int i = 0; i < a.count(); i++) a.accept(i, Chunks.slice(tampered, i, Protocol.CHUNK_SIZE));
        assertThat(a.isComplete()).isTrue();
        assertThat(a.finish()).isNull();
        a.reset();
        assertThat(a.received()).isZero();
    }

    @Test
    void resumeBitsetListsWhatArrived() {
        byte[] file = random(5 * Protocol.CHUNK_SIZE, 5);
        Chunks.Assembler a = new Chunks.Assembler(Hashes.sha256(file), file.length, Protocol.CHUNK_SIZE);
        a.accept(1, Chunks.slice(file, 1, Protocol.CHUNK_SIZE));
        a.accept(3, Chunks.slice(file, 3, Protocol.CHUNK_SIZE));
        assertThat(a.have().stream().boxed().toList()).containsExactly(1, 3);
    }

    @Test
    void pagesStayUnderTheMessageLimit() {
        List<SharedPlacement> many = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            many.add(new SharedPlacement(UUID.randomUUID(), ProtocolTest.HASH, "a rather long schematic file name number " + i + ".litematic",
                    "minecraft:overworld", i, 64, -i, i, false, ProtocolTest.A, "SomePlayerName", false, null, "", i));
        }
        List<Message> pages = Chunks.pages(many, Message.PlacementList::new);
        assertThat(pages.size()).isGreaterThan(1);
        int total = 0;
        for (int i = 0; i < pages.size(); i++) {
            Message.PlacementList page = (Message.PlacementList) pages.get(i);
            assertThat(page.reset()).isEqualTo(i == 0);
            assertThat(Protocol.encode(page).length).isLessThanOrEqualTo(Protocol.MAX_MESSAGE);
            total += page.entries().size();
        }
        assertThat(total).isEqualTo(1000);
        assertThat(Chunks.pages(List.<SharedPlacement>of(), Message.PlacementList::new))
                .containsExactly(new Message.PlacementList(true, List.of()));
    }
}
