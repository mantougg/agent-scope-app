package space.wlshow.scope.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonStripFenceTest {

    @Test
    void plainJson_returnAsIs() {
        String s = "{\"a\":1}";
        assertEquals(s, Json.stripFence(s));
    }

    @Test
    void fencedWithLang_strip() {
        String raw = "```json\n{\"a\":1}\n```";
        assertEquals("{\"a\":1}", Json.stripFence(raw));
    }

    @Test
    void fencedNoLang_strip() {
        String raw = "```\n{\"a\":1}\n```";
        assertEquals("{\"a\":1}", Json.stripFence(raw));
    }

    @Test
    void chitchatBeforeAndAfter_strip() {
        String raw = "好的，以下是 JSON：\n{\"a\":1}\n如有疑问请告诉我";
        assertEquals("{\"a\":1}", Json.stripFence(raw));
    }

    @Test
    void multilineJson_keep() {
        String raw = "```json\n{\n  \"a\": 1,\n  \"b\": [1,2]\n}\n```";
        String got = Json.stripFence(raw);
        assertEquals("{\n  \"a\": 1,\n  \"b\": [1,2]\n}", got);
    }
}