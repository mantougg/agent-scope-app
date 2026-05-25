package space.wlshow.scope.spec;

public record ModuleSpec(
        String moduleName,   // 中文显示名，如 "请假申请"
        String moduleId,     // camelCase ID，首字母小写
        String moduleDesc    // 描述
) {}