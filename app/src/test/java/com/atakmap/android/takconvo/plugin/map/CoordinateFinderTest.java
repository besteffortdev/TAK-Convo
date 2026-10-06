package com.atakmap.android.takconvo.plugin.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.utils.TakConvoCompat;

import org.junit.Test;

import java.util.List;

public class CoordinateFinderTest {

    /** Ottawa, the user manual's decimal example. */
    private static final double LAT = 45.4215;
    private static final double LON = -75.6972;
    /** The manual's MGRS example, 18T VR 44690 31520: 1.5 km north-west of it (UTM by hand). */
    private static final double MGRS_LAT = 45.4350;
    private static final double MGRS_LON = -75.7071;
    /** About 10 m. */
    private static final double NEAR = 0.0001;

    private static TakConvoCompat.Coordinates only(final String text) {
        final List<TakConvoCompat.Coordinates> found = CoordinateFinder.find(text);
        assertEquals(text, 1, found.size());
        return found.get(0);
    }

    @Test
    public void mgrsWithAndWithoutSpaces() {
        for (final String mgrs : new String[] {"18T VR 44690 31520", "18TVR4469031520",
                "18tvr 44690 31520"}) {
            final TakConvoCompat.Coordinates c = only("at " + mgrs + " now");
            assertEquals(mgrs, MGRS_LAT, c.latitude(), NEAR);
            assertEquals(mgrs, MGRS_LON, c.longitude(), NEAR);
            assertEquals(3, c.start());
            assertEquals(3 + mgrs.length(), c.end());
        }
    }

    @Test
    public void mgrsAsAtakWritesIt() {
        // a left-to-right mark before each space
        final TakConvoCompat.Coordinates c = only("18T‎ VR‎ 44690‎ 31520");
        assertEquals(MGRS_LAT, c.latitude(), NEAR);
    }

    @Test
    public void signedDecimalDegrees() {
        final TakConvoCompat.Coordinates c = only("rally 45.4215, -75.6972");
        assertEquals(LAT, c.latitude(), NEAR);
        assertEquals(LON, c.longitude(), NEAR);
        // the end of a sentence
        final TakConvoCompat.Coordinates end = only("Rally at 45.4215, -75.6972.");
        assertEquals(LON, end.longitude(), NEAR);
        assertEquals("Rally at 45.4215, -75.6972".length(), end.end());
        // a longer number isn't cut
        assertTrue(CoordinateFinder.find("45.4215, -75.6972.5").isEmpty());
    }

    @Test
    public void decimalDegreesWithHemispheres() {
        final TakConvoCompat.Coordinates c = only("45.30N 75.88W");
        assertEquals(45.30, c.latitude(), NEAR);
        assertEquals(-75.88, c.longitude(), NEAR);
        assertEquals(-33.9, only("33.90S, 151.20E").latitude(), NEAR);
    }

    @Test
    public void ordinaryNumbersAreNotPositions() {
        for (final String text : new String[] {"meet at 10.30, room 4.5", "v2.1, 3.14",
                "call 613-555-0100", "18T", "", "version 45.4215"}) {
            assertTrue(text, CoordinateFinder.find(text).isEmpty());
        }
    }

    @Test
    public void invalidOnesAreSkipped() {
        // latitude out of range; MGRS zone 61 doesn't exist; odd digit count
        for (final String text : new String[] {"95.1234, 10.1234", "61T VR 44690 31520",
                "18TVR446903152"}) {
            assertTrue(text, CoordinateFinder.find(text).isEmpty());
        }
    }

    @Test
    public void severalInOneMessage() {
        final List<TakConvoCompat.Coordinates> found =
                CoordinateFinder.find("from 18T VR 44690 31520 to 45.30N 75.88W");
        assertEquals(2, found.size());
    }

    @Test
    public void plainSpaces() {
        assertEquals("18T VR 44690 31520",
                CoordinateFinder.plainSpaces(" 18T‎ VR‎  44690‎ 31520 "));
    }
}
