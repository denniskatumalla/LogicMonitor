package outage;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static outage.Assert.eq;
import static outage.Assert.fails;

/**
 * The project's own JSON parser and writer (Json.java), written to keep the
 * build free of third-party jars. Numbers parse as Double.
 */
class JsonTest {

    @TestRunner.Test
    void parsesObjectsArraysAndScalars() {
        Object v = Json.parse(" {\"url\": \"https://x.io/?a=1\", \"n\": -1.5e2, \"ok\": true, \"none\": null, \"l\": [1, \"two\"]} ");
        Map<?, ?> m = (Map<?, ?>) v;
        eq("https://x.io/?a=1", m.get("url"));
        eq(-150.0, m.get("n"));
        eq(true, m.get("ok"));
        eq(true, m.containsKey("none"));
        eq(List.of(1.0, "two"), m.get("l"));
    }

    @TestRunner.Test
    void decodesEscapes() {
        eq("a\"b\\c/\n\té", Json.parse("\"a\\\"b\\\\c\\/\\n\\t\\u00e9\""));
    }

    // Includes a trailing comma, an unquoted key, trailing text and an unknown escape.
    @TestRunner.Test
    void rejectsMalformedInput() {
        for (String bad : List.of("", "{", "{\"a\" 1}", "{\"a\":1,}", "[1 2]", "\"open", "tru", "{} x", "{a:1}", "\"\\x\"")) {
            fails(Json.ParseException.class, () -> Json.parse(bad));
        }
    }

    // A round trip. Control characters are escaped; NaN, which JSON cannot express, becomes null.
    @TestRunner.Test
    void writesWhatItParses() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "q\"uote\nline\u0001");
        m.put("i", 42L);
        m.put("d", 1.5);
        m.put("nan", Double.NaN);
        m.put("b", false);
        m.put("n", null);
        m.put("a", Arrays.asList(1, "x"));
        String json = Json.write(m);
        eq("{\"s\":\"q\\\"uote\\nline\\u0001\",\"i\":42,\"d\":1.5,\"nan\":null,\"b\":false,\"n\":null,\"a\":[1,\"x\"]}", json);
        eq("q\"uote\nline\u0001", ((Map<?, ?>) Json.parse(json)).get("s"));
    }
}
