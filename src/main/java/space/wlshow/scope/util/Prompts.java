package space.wlshow.scope.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 加载 classpath 下 prompts/*.md，并缓存避免重复读盘。 */
public final class Prompts {

    private static volatile String analyst;

    private static volatile String analystWithTools;

    private static volatile String analystMultiRound;

    public static String analyst() {
        if (analyst == null) {
            synchronized (Prompts.class) {
                if (analyst == null) {
                    analyst = read("/prompts/analyst.md");
                }
            }
        }
        return analyst;
    }

    private static String read(String resource) {
        try (InputStream in = Prompts.class.getResourceAsStream(resource)) {
            Objects.requireNonNull(in, "prompt 资源不存在: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 prompt 失败: " + resource, e);
        }
    }

    public static String analystWithTools() {
        if (analystWithTools == null) {
            synchronized (Prompts.class) {
                if (analystWithTools == null) {
                    analystWithTools = read("/prompts/analyst-with-tools.md");
                }
            }
        }
        return analystWithTools;
    }

    public static String analystMultiRound() {
        if (analystMultiRound == null) {
            synchronized (Prompts.class) {
                if (analystMultiRound == null) {
                    analystMultiRound = read("/prompts/analyst-multi-round.md");
                }
            }
        }
        return analystMultiRound;
    }

    private Prompts() {}
}