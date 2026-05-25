package space.wlshow.scope.schema;

import com.networknt.schema.ValidationMessage;

/**
 * 校验错误的中文友好封装。
 *
 * @param path      JSON Pointer，如 "$.models[0].fields[1].dataType"
 * @param keyword   触发的关键字（type/enum/required/pattern/additionalProperties/...）
 * @param message   中文描述
 * @param raw       networknt 原始消息（保留兜底）
 */
public record ValidationError(String path, String keyword, String message, String raw) {

    public static ValidationError from(ValidationMessage m) {
        String path = m.getInstanceLocation() == null
                ? (m.getEvaluationPath() == null ? "$" : m.getEvaluationPath().toString())
                : m.getInstanceLocation().toString();
        String keyword = m.getType();
        String zh = translate(m);
        return new ValidationError(path, keyword, zh, m.getMessage());
    }

    private static String translate(ValidationMessage m) {
        String kw   = m.getType();
        String path = m.getInstanceLocation() == null
                ? (m.getEvaluationPath() == null ? "?" : m.getEvaluationPath().toString())
                : m.getInstanceLocation().toString();
        Object[] args = m.getArguments() == null ? new Object[0] : m.getArguments();

        return switch (kw) {
            case "required" -> "%s 缺少必填字段 %s".formatted(path, joinArgs(args));
            case "type"     -> "%s 类型不正确：期望 %s".formatted(path, joinArgs(args));
            case "enum"     -> "%s 必须是枚举之一：%s".formatted(path, joinArgs(args));
            case "pattern"  -> "%s 不匹配正则 %s".formatted(path, joinArgs(args));
            case "minItems" -> "%s 元素不足：要求至少 %s 项".formatted(path, joinArgs(args));
            case "minLength" -> "%s 字符串过短：要求至少 %s 字符".formatted(path, joinArgs(args));
            case "additionalProperties" ->
                    "%s 出现了未在 Schema 中声明的字段 %s".formatted(path, joinArgs(args));
            case "oneOf"    -> "%s 不满足任何一个允许的形态".formatted(path);
            default         -> "%s [%s] %s".formatted(path, kw, m.getMessage());
        };
    }

    private static String joinArgs(Object[] args) {
        if (args.length == 0) return "";
        var sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i]);
        }
        return sb.toString();
    }
}