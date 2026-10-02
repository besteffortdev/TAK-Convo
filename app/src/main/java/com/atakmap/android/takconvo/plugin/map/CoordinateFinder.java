package com.atakmap.android.takconvo.plugin.map;

import com.atakmap.coremap.maps.coords.Ellipsoid;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.MGRSPoint;

import eu.siacs.conversations.utils.TakConvoCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds positions written in a message: MGRS grid references ({@code 18T WL 12345 67890},
 * {@code 18TWL1234567890}) and decimal latitude/longitude ({@code 45.4215, -75.6972},
 * {@code 45.4215N 75.6972W}).
 */
final class CoordinateFinder {

    /**
     * Any space, and invisible format characters: ATAK writes MGRS with a left-to-right mark
     * (U+200E) before each space, and copies of it keep them.
     */
    private static final String SP = "[\\s\\p{Z}\\p{Cf}]";

    /** Zone and band, 100 km square, then the digits: together or as two equal groups. */
    private static final Pattern MGRS = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\d{1,2}[C-HJ-NP-X])" + SP + "{0,2}([A-HJ-NP-Z]{2})" + SP
                    + "{0,2}(\\d{1,5}" + SP + "{1,2}\\d{1,5}|\\d{2,10})(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE);

    /** At least three decimals each: fewer would match ordinary numbers. */
    private static final Pattern SIGNED_DECIMAL = Pattern.compile(
            "(?<![\\p{N}.+-])([+-]?\\d{1,2}\\.\\d{3,})" + SP + "*," + SP
                    + "*([+-]?\\d{1,3}\\.\\d{3,})(?![\\p{N}.])");

    private static final Pattern HEMISPHERE_DECIMAL = Pattern.compile(
            "(?<![\\p{N}.])(\\d{1,2}\\.\\d{2,})" + SP + "?°?" + SP + "?([NS])(?:" + SP + "|,)+"
                    + "(\\d{1,3}\\.\\d{2,})" + SP + "?°?" + SP + "?([EW])(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE);

    private CoordinateFinder() {
    }

    /** The text with each run of spaces of any kind as one plain space. */
    static String plainSpaces(final String text) {
        return text.replaceAll(SP + "+", " ").trim();
    }

    static List<TakConvoCompat.Coordinates> find(final String text) {
        final List<TakConvoCompat.Coordinates> found = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return found;
        }
        final Matcher mgrs = MGRS.matcher(text);
        while (mgrs.find()) {
            final double[] latLon = decodeMgrs(mgrs.group(1), mgrs.group(2), mgrs.group(3));
            if (latLon != null) {
                add(found, mgrs.start(), mgrs.end(), latLon[0], latLon[1]);
            }
        }
        final Matcher signed = SIGNED_DECIMAL.matcher(text);
        while (signed.find()) {
            add(found, signed.start(), signed.end(), Double.parseDouble(signed.group(1)),
                    Double.parseDouble(signed.group(2)));
        }
        final Matcher hemisphere = HEMISPHERE_DECIMAL.matcher(text);
        while (hemisphere.find()) {
            final double lat = Double.parseDouble(hemisphere.group(1));
            final double lon = Double.parseDouble(hemisphere.group(3));
            add(found, hemisphere.start(), hemisphere.end(),
                    "S".equalsIgnoreCase(hemisphere.group(2)) ? -lat : lat,
                    "W".equalsIgnoreCase(hemisphere.group(4)) ? -lon : lon);
        }
        return found;
    }

    /** Null if it isn't a valid grid reference. */
    private static double[] decodeMgrs(final String zone, final String square,
            final String digits) {
        final String easting;
        final String northing;
        final String[] groups = digits.split(SP + "+");
        if (groups.length == 2) {
            easting = groups[0];
            northing = groups[1];
        } else {
            if (digits.length() % 2 != 0) {
                return null;
            }
            easting = digits.substring(0, digits.length() / 2);
            northing = digits.substring(digits.length() / 2);
        }
        final int zoneNumber = Integer.parseInt(zone.substring(0, zone.length() - 1));
        if (easting.length() != northing.length() || zoneNumber < 1 || zoneNumber > 60) {
            return null;
        }
        try {
            final MGRSPoint point = MGRSPoint.decode(zone.toUpperCase(Locale.ROOT),
                    square.toUpperCase(Locale.ROOT), easting, northing, Ellipsoid.WGS_84, null);
            return point == null ? null : point.toLatLng(null);
        } catch (final IllegalArgumentException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    /** Adds a valid position that doesn't overlap one found already. */
    private static void add(final List<TakConvoCompat.Coordinates> found, final int start,
            final int end, final double lat, final double lon) {
        if (!GeoPoint.isValid(lat, lon)) {
            return;
        }
        for (final TakConvoCompat.Coordinates c : found) {
            if (start < c.end() && c.start() < end) {
                return;
            }
        }
        found.add(new TakConvoCompat.Coordinates(start, end, lat, lon));
    }
}
