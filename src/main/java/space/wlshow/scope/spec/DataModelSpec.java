package space.wlshow.scope.spec;

import java.util.List;

public record DataModelSpec(
        String name,
        String type,              // TASK_MASTER_SLAVE / TASK / ENTITY
        String pinyin,
        String tableName,
        String parentId,          // 顶层是 "" 而非 null
        List<FieldSpec> fields
) {}