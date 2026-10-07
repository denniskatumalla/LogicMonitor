package outage;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A customer's outage report, validated and normalised. Error messages are
 * written for customers, because the web page shows them as they are.
 */
record Report(String zip, String address, String phone, String notes) {

    static final int MAX_ADDRESS = 120;
    static final int MAX_NOTES = 500;
    private static final Pattern ZIP = Pattern.compile("\\d{5}(-\\d{4})?");
    private static final Pattern ZIP_AT_END = Pattern.compile("(?:^|\\D)(\\d{5})(?:-\\d{4})?$");

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
        Matcher m = ZIP_AT_END.matcher(address);
        return m.find() ? m.group(1) : "";
    }

    private static String phone(String raw) {
        if (raw.isEmpty()) return "";
        String digits = raw.replaceAll("[\\s().+-]", "");
        if (!digits.matches("\\d+")) throw new Invalid("phone", "Enter a 10-digit phone number, or leave it blank.");
        if (digits.length() == 11 && digits.startsWith("1")) digits = digits.substring(1);
        if (digits.length() != 10) throw new Invalid("phone", "Enter a 10-digit phone number, or leave it blank.");
        return digits;
    }
}
