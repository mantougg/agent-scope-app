package space.wlshow.scope.tool;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.observability.Stage;
import space.wlshow.scope.schema.SchemaValidator;
import space.wlshow.scope.schema.ValidationError;
import space.wlshow.scope.spec.AppSpec;
import space.wlshow.scope.spec.DataModelSpec;
import space.wlshow.scope.spec.FieldSpec;
import space.wlshow.scope.spec.ModuleSpec;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.todo.TodoType;
import space.wlshow.scope.util.Json;

import java.util.List;

/**
 * LLM 用这一组工具汇报"应用 / 模块 / 数据模型"的设计结果。
 * 每个工具不直接下发前端（Day 6 才有），只往 TodoManager 排队。
 *
 * 每个工具内置 Schema 兜底：prompt 漂移导致的脏参数会返回 "ERROR: ..." 给 LLM 触发自纠错，
 * 同时拒绝写入 TodoManager。
 */
public class FrontendCreateTools {

    private static final Logger log = LoggerFactory.getLogger(FrontendCreateTools.class);

    private static final SchemaValidator APP_VAL =
            new SchemaValidator("/schemas/app-spec.schema.json");
    private static final SchemaValidator MODULE_VAL =
            new SchemaValidator("/schemas/module-spec.schema.json");
    private static final SchemaValidator MODEL_VAL =
            new SchemaValidator("/schemas/data-model-spec.schema.json");

    private final TodoManager todos;

    public FrontendCreateTools(TodoManager todos) {
        this.todos = todos;
    }

    @Tool(name = "create_app",
            description = "登记一个应用（App）。一次需求分析通常只调用一次。" +
                    "如果不确定 type，写 '23'（业务管理类）。")
    public String createApp(
            @ToolParam(name = "name", description = "英文短名，camelCase，如 leaveMgr") String name,
            @ToolParam(name = "label", description = "中文显示名，如 请假管理") String label,
            @ToolParam(name = "type", description = "应用分类码，缺省 23") String type
    ) {
        return Stage.call(Stage.TOOL_CALL, () -> {
            log.info("[Tool] 调用工具 name=create_app argsHash={}",
                    Stage.argsHash(name, label, type));
            AppSpec spec = new AppSpec(name, label, type);
            JsonNode payload = Json.mapper().valueToTree(spec);

            String err = validate(APP_VAL, payload, "create_app");
            if (err != null) return err;

            TodoItem it = todos.add(TodoType.CREATE_APP, label, payload);
            log.info("[Tool] create_app id={} payload={}", it.id(), payload);
            return "APP 待办已登记：id=" + it.id() + " label=" + label;
        });
    }

    @Tool(name = "create_module",
            description = "登记一个业务模块（Module）。一个 App 通常有多个 Module。")
    public String createModule(
            @ToolParam(name = "moduleName", description = "中文模块名，如 请假申请") String moduleName,
            @ToolParam(name = "moduleId", description = "英文 camelCase，如 leaveApply") String moduleId,
            @ToolParam(name = "moduleDesc", description = "一句话描述模块用途") String moduleDesc
    ) {
        return Stage.call(Stage.TOOL_CALL, () -> {
            log.info("[Tool] 调用工具 name=create_module argsHash={}",
                    Stage.argsHash(moduleName, moduleId, moduleDesc));
            ModuleSpec spec = new ModuleSpec(moduleName, moduleId, moduleDesc);
            JsonNode payload = Json.mapper().valueToTree(spec);

            String err = validate(MODULE_VAL, payload, "create_module");
            if (err != null) return err;

            TodoItem it = todos.add(TodoType.CREATE_MODULE, moduleName, payload);
            log.info("[Tool] create_module id={} payload={}", it.id(), payload);
            return "MODULE 待办已登记：id=" + it.id() + " name=" + moduleName;
        });
    }

    @Tool(name = "create_model",
            description = "登记一个数据模型（DataModel）。fields 用 JSON 字符串传入：" +
                    "[{name,dataType,usage,comment,relateModelType,subs}]。" +
                    "含明细的单据用 type=TASK_MASTER_SLAVE，把明细放进某个 dataType=array 字段的 subs。" +
                    "每个 model 必须包含 name=id, dataType=long, usage=primary 的主键字段。")
    public String createModel(
            @ToolParam(name = "name", description = "英文 camelCase，如 leaveBill") String name,
            @ToolParam(name = "type", description = "ENTITY | TASK | TASK_MASTER_SLAVE") String type,
            @ToolParam(name = "pinyin", description = "全拼，如 qingjiadan") String pinyin,
            @ToolParam(name = "tableName", description = "表名，如 t_leave_bill") String tableName,
            @ToolParam(name = "parentId", description = "通常为空字符串") String parentId,
            @ToolParam(name = "fieldsJson", description = "FieldSpec 数组的 JSON 字符串") String fieldsJson
    ) {
        return Stage.call(Stage.TOOL_CALL, () -> {
            log.info("[Tool] 调用工具 name=create_model argsHash={}",
                    Stage.argsHash(name, type, pinyin, tableName, parentId, fieldsJson));
            List<FieldSpec> fields = Json.readList(fieldsJson, FieldSpec.class);
            DataModelSpec spec = new DataModelSpec(name, type, pinyin, tableName, parentId, fields);
            JsonNode payload = Json.mapper().valueToTree(spec);

            String err = validate(MODEL_VAL, payload, "create_model");
            if (err != null) return err;

            TodoItem it = todos.add(TodoType.CREATE_MODEL, name, payload);
            log.info("[Tool] create_model id={} fieldCount={} payload={}",
                    it.id(), fields.size(), payload);
            return "MODEL 待办已登记：id=" + it.id() + " name=" + name + " fields=" + fields.size();
        });
    }

    /**
     * 工具内 Schema 兜底：合规返回 null（继续落 Todo），否则返回 "ERROR: ..." 给 LLM。
     */
    private static String validate(SchemaValidator validator, JsonNode payload, String tool) {
        List<String> errors = validator.validate(payload).stream()
                .map(ValidationError::message)
                .toList();
        if (errors.isEmpty()) return null;
        log.warn("[Tool] {} rejected: {}", tool, errors);
        return "ERROR: 参数不合规：" + String.join("; ", errors);
    }
}
