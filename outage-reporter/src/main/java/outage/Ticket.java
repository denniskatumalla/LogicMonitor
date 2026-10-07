package outage;

import java.util.Locale;

/**
 * One outage report and where it is in its life. Immutable: every status
 * change is a new instance, so a reader always sees a consistent ticket.
 * Times are epoch milliseconds, 0 for "not yet".
 */
record Ticket(
        String id,
        Source source,
        String zip,
        String areaId,
        String address,
        String phone,
        String notes,
        Status status,
        long reportedAt,
        long confirmedAt,
        long crewAssignedAt,
        long restoredAt,
        long etrAt,
        int etrRevisions,
        int customersAffected) {

    /** WEB: a customer, through the page or the API. STORM: the surge generator. TEST: a synthetic check. */
    enum Source { WEB, STORM, TEST }

    enum Status {
        REPORTED("Reported"), CONFIRMED("Confirmed"), CREW_ASSIGNED("Crew assigned"), RESTORED("Restored");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        String label() {
            return label;
        }
    }

    static Ticket reported(String id, Source source, Report r, String areaId, long at) {
        return new Ticket(id, source, r.zip(), areaId, r.address(), r.phone(), r.notes(),
                Status.REPORTED, at, 0, 0, 0, 0, 0, 0);
    }

    /** The ticket after moving to {@code next} at {@code at}, with the ETR and impact known then. */
    Ticket advance(Status next, long at, long etr, int customers, int revisions) {
        return new Ticket(id, source, zip, areaId, address, phone, notes, next, reportedAt,
                next == Status.CONFIRMED ? at : confirmedAt,
                next == Status.CREW_ASSIGNED ? at : crewAssignedAt,
                next == Status.RESTORED ? at : restoredAt,
                etr, revisions, customers);
    }

    long at(Status s) {
        return switch (s) {
            case REPORTED -> reportedAt;
            case CONFIRMED -> confirmedAt;
            case CREW_ASSIGNED -> crewAssignedAt;
            case RESTORED -> restoredAt;
        };
    }

    long updatedAt() {
        return at(status);
    }

    boolean open() {
        return status != Status.RESTORED;
    }

    /** Synthetic-check tickets are kept out of every business figure. */
    boolean counts() {
        return source != Source.TEST;
    }
}
