package space.wlshow.scope.spec;

import java.util.List;

/**
 * 一次完整需求分析的结果。
 * warnings：LLM 自己不确定但有默认值的（"假设 type=23 表示业务管理类应用"）
 * questions：必须问用户的（"请假明细是否需要附件字段？"）
 */
public record AnalysisResult(
        AppSpec app,
        List<ModuleSpec> modules,
        List<DataModelSpec> models,
        List<String> warnings,
        List<String> questions
) {}