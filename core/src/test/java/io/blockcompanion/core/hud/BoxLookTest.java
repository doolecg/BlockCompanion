package io.blockcompanion.core.hud;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoxLookTest {
    @Test
    void fadesInAndOutWithinTheFadeTime() {
        double f = 0;
        f = BoxLook.stepFade(f, true, BoxLook.FADE_MS / 2);
        assertThat(f).isBetween(0.49, 0.51);
        f = BoxLook.stepFade(f, true, BoxLook.FADE_MS);
        assertThat(f).isEqualTo(1.0);
        f = BoxLook.stepFade(f, false, BoxLook.FADE_MS * 4);
        assertThat(f).isEqualTo(0.0);
        assertThat(BoxLook.stepFade(0.3, true, -50)).isEqualTo(0.3);
    }

    @Test
    void facesAreFaintAndTheLookedAtOneBrighter() {
        for (long t = 0; t < BoxLook.PULSE_MS; t += 100) {
            int plain = BoxLook.faceArgb(0x4FE3E3, false, t, 1) >>> 24;
            int looked = BoxLook.faceArgb(0x4FE3E3, true, t, 1) >>> 24;
            assertThat(plain).isBetween(8, 40);
            assertThat(looked).isGreaterThan(plain).isLessThan(70);
        }
        assertThat(BoxLook.faceArgb(0x4FE3E3, true, 0, 0) >>> 24).isZero();
        assertThat(BoxLook.faceArgb(0x4FE3E3, false, 0, 1) & 0xFFFFFF).isEqualTo(0x4FE3E3);
    }

    @Test
    void faceIndexMatchesQuadOrder() {
        float[] q = BoxLook.faceQuads(0, 0, 0, 1, 2, 3);
        assertThat(q).hasSize(6 * 4 * 3);
        // +y (axis 1, sign +1) is face 3: every corner at y = 2.
        int f = BoxLook.face(1, 1);
        assertThat(f).isEqualTo(3);
        for (int c = 0; c < 4; c++) assertThat(q[f * 12 + c * 3 + 1]).isEqualTo(2f);
        // -z is face 4: every corner at z = 0.
        for (int c = 0; c < 4; c++) assertThat(q[BoxLook.face(2, -1) * 12 + c * 3 + 2]).isEqualTo(0f);
    }

    @Test
    void bracketsStayOnTheEdgesAndShort() {
        float[] b = BoxLook.brackets(0, 0, 0, 10, 1, 4);
        assertThat(b).hasSize(24 * 6);
        for (int i = 0; i < b.length; i += 6) {
            float len = Math.abs(b[i] - b[i + 3]) + Math.abs(b[i + 1] - b[i + 4]) + Math.abs(b[i + 2] - b[i + 5]);
            assertThat(len).isBetween(0.2f, 0.75f);
        }
        assertThat(BoxLook.edges(0, 0, 0, 1, 1, 1)).hasSize(12 * 6);
    }

    @Test
    void mixBlendsColours() {
        assertThat(Colors.mix(0xFF000000, 0xFFFFFF, 0.5)).isEqualTo(0xFF808080);
        assertThat(Colors.mix(0x123456, 0xFFFFFF, 0)).isEqualTo(0x123456);
    }
}
