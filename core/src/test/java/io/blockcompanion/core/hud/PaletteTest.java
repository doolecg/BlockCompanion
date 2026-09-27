package io.blockcompanion.core.hud;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class PaletteTest {
    @Test
    void readsHexInSeveralForms() {
        assertThat(Palette.parseHex("#FF3030")).isEqualTo(0xFF3030);
        assertThat(Palette.parseHex("ff3030")).isEqualTo(0xFF3030);
        assertThat(Palette.parseHex("0x00ff00")).isEqualTo(0x00FF00);
        assertThat(Palette.parseHex(" #f0a ")).isEqualTo(0xFF00AA);
        assertThat(Palette.parseHex("#12345")).isNull();
        assertThat(Palette.parseHex("#GGGGGG")).isNull();
        assertThat(Palette.parseHex(null)).isNull();
        assertThat(Palette.hex(0x0A0B0C)).isEqualTo("#0A0B0C");
    }

    @Test
    void roundTripsThroughProperties() {
        Palette a = new Palette();
        a.set(Palette.Entry.WRONG, 0x123456);
        Properties p = new Properties();
        a.write(p);
        assertThat(p.getProperty("color.wrong")).isEqualTo("#123456");
        p.setProperty("color.extra", "not a colour");
        Palette b = new Palette();
        b.read(p);
        assertThat(b.get(Palette.Entry.WRONG)).isEqualTo(0x123456);
        assertThat(b.get(Palette.Entry.EXTRA)).isEqualTo(Palette.Entry.EXTRA.defaultRgb);
        assertThat(b.argb(Palette.Entry.WRONG, 0x4C)).isEqualTo(0x4C123456);
    }

    @Test
    void presetsApplyAndAreRecognised() {
        Palette p = new Palette();
        assertThat(p.matchingPreset()).isSameAs(Palette.DEFAULT);
        p.apply(Palette.COLOR_BLIND);
        assertThat(p.matchingPreset()).isSameAs(Palette.COLOR_BLIND);
        assertThat(p.isDefault(Palette.Entry.WRONG)).isFalse();
        p.set(Palette.Entry.GHOST, 0x010203);
        assertThat(p.matchingPreset()).isNull();
        for (Palette.Preset preset : Palette.PRESETS) assertThat(preset.rgb()).hasSize(Palette.Entry.values().length);
    }
}
