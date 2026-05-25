package space.wlshow.scope.spec;

import java.util.List;

/**
 * 字段定义。subs 用于 dataType=array 的子字段（递归）。
 * 叶子字段的 subs 应该是 null 而不是 List.of()，与题面保持一致。
 */
public record FieldSpec(
        String comment,
        String name,
        String dataType,          // long/int/double/string/boolean/date/array
        String usage,             // primary/foreign/""
        String relateModelType,   // collection/""
        List<FieldSpec> subs      // 仅 dataType=array 时有值；叶子节点为 null
) {}