package space.wlshow.scope.spec;

public record AppSpec(
        String name,    // camelCase 标识，如 "leaveSystem"
        String label,   // 中文显示名，如 "请假管理"
        String type     // App 分类码（题面给定）
) {}