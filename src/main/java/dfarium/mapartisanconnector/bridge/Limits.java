package dfarium.mapartisanconnector.bridge;

import dfarium.mapartisanconnector.structure.BuildStructure;

/** Protocol limits from docs/litematica-live-preview-spec.md, section 10.3. */
public final class Limits {
    public static final int MAX_MESSAGE_BYTES = 256 * 1024;
    public static final int MAX_TRANSFER_BYTES = 16 * 1024 * 1024;
    public static final int MAX_POSITIONS = BuildStructure.MAX_POSITIONS;
    public static final long MAX_VOLUME = BuildStructure.MAX_VOLUME;
    public static final int MAX_PALETTE = 4096;
    public static final int MAX_STATE_LENGTH = 512;
    /** A section is 128 wide and 129 deep; height is bounded by the world. */
    public static final int MAX_SIZE_XZ = 256;
    public static final int MAX_SIZE_Y = 4096;
    public static final long TRANSFER_TIMEOUT_NANOS = 10_000_000_000L;
    public static final long HELLO_TIMEOUT_NANOS = 10_000_000_000L;

    private Limits() {
    }
}
