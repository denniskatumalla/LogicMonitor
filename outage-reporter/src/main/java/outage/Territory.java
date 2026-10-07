package outage;

import java.util.List;
import java.util.Optional;

/**
 * The fictional service territory of Example Power &amp; Light. ZIP codes are
 * in the 000xx range, below the lowest real ZIP code, so none of them is a
 * real place.
 */
final class Territory {
    private Territory() { }

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
    static final Area TEST_AREA = new Area("test", "Synthetic check", 99, 99, 0);

    static final String FIRST_ZIP = String.format("%05d", AREAS.getFirst().zipFrom());
    static final String LAST_ZIP = String.format("%05d", AREAS.getLast().zipTo());

    static Optional<Area> forZip(String zip) {
        if (TEST_ZIP.equals(zip)) return Optional.of(TEST_AREA);
        if (zip == null || !zip.matches("\\d{5}")) return Optional.empty();
        int z = Integer.parseInt(zip);
        return AREAS.stream().filter(a -> a.contains(z)).findFirst();
    }

    static Optional<Area> byId(String id) {
        if (TEST_AREA.id().equals(id)) return Optional.of(TEST_AREA);
        return AREAS.stream().filter(a -> a.id().equals(id)).findFirst();
    }

    static int customersServed() {
        return AREAS.stream().mapToInt(Area::customersServed).sum();
    }
}
