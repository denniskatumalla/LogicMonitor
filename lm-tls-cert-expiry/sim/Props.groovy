/**
 * Property maps as the collector presents them: names are case-insensitive.
 * Scripts receive these as hostProps and instanceProps and call get() on them.
 */
class Props {
    /** Returns a case-insensitive copy of the given map. */
    static Map<String, String> of(Map<String, ?> source) {
        TreeMap<String, String> m = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        source?.each { k, v -> m.put(k as String, v == null ? null : v as String) }
        return m
    }
}
