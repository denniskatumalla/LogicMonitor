package outage;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A customer's outage report, validated and normalised. Error messages are
 * written for customers, because the web page shows them as they are.
 *
 * <p>The JSON API and the HTML form both build reports here, so both apply
 * the same rules. Only the ZIP is required (taken from the end of the address
 * if left blank) and it must be in the {@link Territory}. The phone is
 * optional and kept as 10 digits. Normalising every field also keeps tabs
 * and newlines out of the tab-separated ticket log.
 */
record Report(String zip, String address, String phone, String notes) {

    static final int MAX_ADDRESS = 120;
    // Length caps bound what a single report can store.
    static final int MAX_NOTES = 500;
    private static final Pattern ZIP = Pattern.compile("\\d{5}(-\\d{4})?");
    // ZIP or ZIP+4; only the first five digits are kept.
    private static final Pattern ZIP_AT_END = Pattern.compile("(?:^|\\D)(\\d{5})(?:-\\d{4})?$");
// A ZIP (optionally ZIP+4) at the very end of the address, for example "12 Main St 00012".

    /** A field the customer has to fix, with the message to show next to it. */
    static final class Invalid extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String field;

        Invalid(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    /** From a parsed JSON object or a decoded form. Absent, null and blank fields are all "not given". */
    static Report from(Map<String, ?> in) {
        String address = text(in, "address", MAX_ADDRESS, "Street address");
        String zip = text(in, "zip", 10, "ZIP code");
        if (zip.isEmpty()) zip = zipAtEnd(address);
        if (zip.isEmpty()) throw new Invalid("zip", "Enter the ZIP code where the power is out.");
        if (!ZIP.matcher(zip).matches()) throw new Invalid("zip", "Enter a 5-digit ZIP code, for example 00012.");
        zip = zip.substring(0, 5);
        if (Territory.forZip(zip).isEmpty()) {
            throw new Invalid("zip", "ZIP " + zip + " isn't in our service area. We serve ZIP codes "
                    + Territory.FIRST_ZIP + " to " + Territory.LAST_ZIP + ".");
        }
        String phone = phone(text(in, "phone", 30, "Phone number"));
        String notes = text(in, "notes", MAX_NOTES, "Details");
        return new Report(zip, address, phone, notes);
    }

    /** "•••-•••-0123", so a ticket lookup never shows a whole phone number. */
    static String maskPhone(String digits) {
        if (digits == null || digits.length() < 4) return null;
        return "•••-•••-" + digits.substring(digits.length() - 4);
    }

    private static String text(Map<String, ?> in, String field, int max, String label) {
        /** One optional text field: "" if absent, an error if not a string, otherwise normalised and length-checked. */
        Object v = in.get(field);
        if (v == null) return "";
        if (!(v instanceof String s)) throw new Invalid(field, label + " must be text.");
        String clean = normalise(s);
        if (clean.length() > max) throw new Invalid(field, label + " must be " + max + " characters or fewer.");
        return clean;
    }

    /** Control characters become spaces and runs of whitespace collapse, so stored fields never hold a tab or newline. */
    static String normalise(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ' || c == 0x7f || Character.isWhitespace(c)) {
                space = sb.length() > 0;
                continue;
            }
            if (space) sb.append(' ');
            space = false;
            sb.append(c);
        }
        return sb.toString();
    }

    private static String zipAtEnd(String address) {
        /** Lets a customer type the ZIP as part of the address. */
        Matcher m = ZIP_AT_END.matcher(address);
        return m.find() ? m.group(1) : "";
    }

    private static String phone(String raw) {
        /** Strips spaces, dots, dashes, parentheses and plus signs, drops a leading country code 1, and requires 10 digits. */
        if (raw.isEmpty()) return "";
        String digits = raw.replaceAll("[\\s().+-]", "");
        if (!digits.matches("\\d+")) throw new Invalid("phone", "Enter a 10-digit phone number, or leave it blank.");
        if (digits.length() == 11 && digits.startsWith("1")) digits = digits.substring(1);
        if (digits.length() != 10) throw new Invalid("phone", "Enter a 10-digit phone number, or leave it blank.");
        return digits;
    }
}
