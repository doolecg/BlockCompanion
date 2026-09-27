package io.blockcompanion.core.formats;

/**
 * Export settings shared by all formats.
 *
 * @param dataVersion   Minecraft DataVersion written into the file (3955 is 1.21.1)
 * @param includeAir    write air inside the bounding box explicitly, so placing the schematic clears whatever is there;
 *                      when false, air is left out and existing terrain shows through
 * @param spongeVersion Sponge schematic revision for {@code .schem} (2 = WorldEdit 7.2, 3 = WorldEdit 7.3+)
 */
public record WriteOptions(int dataVersion, boolean includeAir, int spongeVersion) {
    /** DataVersion of Minecraft 1.21.1. */
    public static final int MC_1_21_1 = 3955;

    public static WriteOptions defaults(int dataVersion) {
        return new WriteOptions(dataVersion, true, 3);
    }

    public static WriteOptions defaults() {
        return defaults(MC_1_21_1);
    }

    public WriteOptions withIncludeAir(boolean includeAir) {
        return new WriteOptions(dataVersion, includeAir, spongeVersion);
    }

    public WriteOptions withSpongeVersion(int v) {
        return new WriteOptions(dataVersion, includeAir, v);
    }
}
