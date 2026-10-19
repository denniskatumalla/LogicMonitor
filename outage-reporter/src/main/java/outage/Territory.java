package outage;

import java.util.List;
import java.util.Optional;

/**
 * The fictional service territory of Example Power &amp; Light. ZIP codes are
 * in the 000xx range, below the lowest real ZIP code, so none of them is a
 * real place.
 *
 * <p>Six areas cover ZIPs 00010 to 00069 and 3 million customers. Every
 * report is mapped to an area by its ZIP. The per-area customer counts feed
 * /api/areas and also cap "customers affected" in the business figures.
 */
final class Territory {
    private Territory() { }

    /** One service area: an id used in tickets and the API, a display name, an inclusive ZIP range and the customers it serves. */
    record Area(String id, String name, int zipFrom, int zipTo, int customersServed) {
        boolean contains(int zip) {
            return zip >= zipFrom && zip <= zipTo;
        }

        String zipRange() {
            return String.format("%05d–%05d", zipFrom, zipTo);
        }
    }

    static final List<Area> AREAS = List.of(
            new Area("edwin", "Edwin AI District", 10, 19, 412_000),
            new Area("envision", "Envision Valley", 20, 29, 538_000),
            new Area("collector", "Collector Cove", 30, 39, 621_000),
            new Area("uptime", "Uptime Ridge", 40, 49, 487_000),
            new Area("insights", "Service Insights Park", 50, 59, 356_000),
            new Area("logs", "LM Logs Landing", 60, 69, 586_000));

    /**
     * Synthetic monitoring reports here. Accepted and stored like any other,
     * so a check exercises the whole write path, then closed at once and left
     * out of every business figure.
     */
    static final String TEST_ZIP = "00099";
    // Serves no customers, so it never adds to the territory totals.
    static final Area TEST_AREA = new Area("test", "Synthetic check", 99, 99, 0);

    // "00010" and "00069": quoted in the out-of-territory error message.
    static final String FIRST_ZIP = String.format("%05d", AREAS.getFirst().zipFrom());
    static final String LAST_ZIP = String.format("%05d", AREAS.getLast().zipTo());

    /** The area for a five-digit ZIP, or empty if it is outside the territory. 00099 maps to the test area. */
    static Optional<Area> forZip(String zip) {
        if (TEST_ZIP.equals(zip)) return Optional.of(TEST_AREA);
        if (zip == null || !zip.matches("\\d{5}")) return Optional.empty();
        int z = Integer.parseInt(zip);
        return AREAS.stream().filter(a -> a.contains(z)).findFirst();
    }

    /** The area with this id, including the synthetic test area. */
    static Optional<Area> byId(String id) {
        if (TEST_AREA.id().equals(id)) return Optional.of(TEST_AREA);
        return AREAS.stream().filter(a -> a.id().equals(id)).findFirst();
    }

    /** Customers served across the six real areas. */
    static int customersServed() {
        return AREAS.stream().mapToInt(Area::customersServed).sum();
    }
}
