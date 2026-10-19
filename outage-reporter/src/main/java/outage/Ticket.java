package outage;

import java.util.Locale;

/**
 * One outage report and where it is in its life. Immutable: every status
 * change is a new instance, so a reader always sees a consistent ticket.
 * Times are epoch milliseconds, 0 for "not yet".
 *
 * <p>Lifecycle: REPORTED (filed, waiting for triage), CONFIRMED (customers
 * affected and a first ETR set), CREW_ASSIGNED (ETR possibly revised), then
 * RESTORED (closed). Only the {@link Dispatcher} advances a ticket, and
 * {@link TicketStore} persists each step.
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
        // Estimated time of restoration; 0 until confirmed.
        long etrAt,
        // How many times the ETR has been pushed out.
        int etrRevisions,
        // 0 until confirmed.
        int customersAffected) {

    /** WEB: a customer, through the page or the API. STORM: the surge generator. TEST: a synthetic check. */
    enum Source { WEB, STORM, TEST }

    /** The lifecycle stages, in order. The label is the customer-facing text in the API and on the status page. */
    enum Status {
        REPORTED("Reported"), CONFIRMED("Confirmed"), CREW_ASSIGNED("Crew assigned"), RESTORED("Restored");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        /** Lower-case name used in the JSON API and in logs, for example crew_assigned. */
        String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        String label() {
            return label;
        }
    }

    /** A newly filed ticket: REPORTED, with no ETR or impact yet. */
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

    /** When the ticket entered status {@code s}, or 0 if it hasn't yet. */
    long at(Status s) {
        return switch (s) {
            case REPORTED -> reportedAt;
            case CONFIRMED -> confirmedAt;
            case CREW_ASSIGNED -> crewAssignedAt;
            case RESTORED -> restoredAt;
        };
    }

    /** When the current status was entered. This is the time written in S records. */
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
