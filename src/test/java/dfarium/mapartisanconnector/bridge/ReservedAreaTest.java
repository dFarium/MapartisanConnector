package dfarium.mapartisanconnector.bridge;

import dfarium.mapartisanconnector.structure.Bounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReservedAreaTest {

    @Test
    void coversOneSectionFootprintUpToTheBuildLimit() {
        // Superflat overworld: standing on the grass at y=-60, the build limit is y=319.
        Bounds area = ReservedArea.bounds(-60, 319);

        assertEquals(new Bounds(0, 0, 0, 127, 379, 128), area);
        assertEquals(128, area.sizeX());
        assertEquals(380, area.sizeY());
        assertEquals(129, area.sizeZ());
    }

    @Test
    void fitsInThePasteVolumeLimitEvenAtTheBottomOfTheWorld() {
        assertEquals(true, ReservedArea.bounds(-64, 319).volume() <= Limits.MAX_VOLUME);
    }
}
