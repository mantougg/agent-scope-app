package space.wlshow.scope.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.observability.Stage;
import space.wlshow.scope.schema.SchemaValidator;
import space.wlshow.scope.schema.ValidationError;
import space.wlshow.scope.spec.FieldSpec;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.todo.TodoStatus;
import space.wlshow.scope.todo.TodoType;
import space.wlshow.scope.util.Json;

import java.util.List;
import java.util.Optional;

public class TodoUpdateTools {

    private static final Logger log = LoggerFactory.getLogger(TodoUpdateTools.class);

    private static final SchemaValidator MODULE_VAL =
            new SchemaValidator("/schemas/module-spec.schema.json");
    private static final SchemaValidator MODEL_VAL =
            new SchemaValidator("/schemas/data-model-spec.schema.json");

    private final TodoManager todos;

    public TodoUpdateTools(TodoManager todos) { this.todos = todos; }

    @Tool(name = "update_module",
            description = "修改一个已存在的 Module 的中文名或描述。" +
                    "通过 moduleId 定位，不能改 moduleId 本身。")
    public String updateModule(
            @ToolParam(name = "moduleId") String moduleId,
            @ToolParam(name = "newModuleName", description = "可选，为空表示不改") String newModuleName,
            @ToolParam(name = "newModuleDesc", description = "可选，为空表示不改") String newModuleDesc
    ) {
        return Stage.call(Stage.TOOL_CALL, () -> {
            log.info("[Tool] 调用工具 name=update_module argsHash={}",
                    Stage.argsHash(moduleId, newModuleName, newModuleDesc));
            Optional<TodoItem> found = findByModuleId(moduleId);
            if (found.isEmpty()) {
                log.warn("[Tool] update_module not-found moduleId={}", moduleId);
                return "ERROR: 未找到 moduleId=" + moduleId;
            }

            TodoItem it = found.get();
            if (it.status() != TodoStatus.PENDING) {
                log.warn("[Tool] update_module rejected id={} status={}", it.id(), it.status());
                return "ERROR: " + it.id() + " 状态为 " + it.status() + "，不可修改";
            }

            ObjectNode p = ((ObjectNode) it.payload()).deepCopy();
            if (newModuleName != null && !newModuleName.isBlank()) p.put("moduleName", newModuleName);
            if (newModuleDesc != null && !newModuleDesc.isBlank()) p.put("moduleDesc", newModuleDesc);

            String err = validate(MODULE_VAL, p, "update_module");
            if (err != null) return err;

            todos.replacePayload(it.id(), p);
            log.info("[Tool] update_module id={} newName={} newDesc={} payload={}",
                    it.id(), newModuleName, newModuleDesc, p);
            return "MODULE 已更新：" + it.id();
        });
    }

    @Tool(name = "update_model",
            description = "向已存在的数据模型追加字段。fieldsJson 是要追加的 FieldSpec 数组。" +
                    "如需删除字段或修改既有字段，先记 warning，本日不实现。")
    public String updateModel(
            @ToolParam(name = "modelName") String modelName,
            @ToolParam(name = "appendFieldsJson") String appendFieldsJson
    ) {
        return Stage.call(Stage.TOOL_CALL, () -> {
            log.info("[Tool] 调用工具 name=update_model argsHash={}",
                    Stage.argsHash(modelName, appendFieldsJson));
            Optional<TodoItem> found = findByModelName(modelName);
            if (found.isEmpty()) {
                log.warn("[Tool] update_model not-found modelName={}", modelName);
                return "ERROR: 未找到 model name=" + modelName;
            }

            TodoItem it = found.get();
            if (it.status() != TodoStatus.PENDING) {
                log.warn("[Tool] update_model rejected id={} status={}", it.id(), it.status());
                return "ERROR: " + it.id() + " 状态为 " + it.status() + "，不可修改";
            }

            List<FieldSpec> appended = Json.readList(appendFieldsJson, FieldSpec.class);
            ObjectNode p = ((ObjectNode) it.payload()).deepCopy();
            var arr = (com.fasterxml.jackson.databind.node.ArrayNode) p.get("fields");
            appended.forEach(f -> arr.add(Json.mapper().valueToTree(f)));

            String err = validate(MODEL_VAL, p, "update_model");
            if (err != null) return err;

            todos.replacePayload(it.id(), p);
            log.info("[Tool] update_model id={} appended={} totalFields={} payload={}",
                    it.id(), appended.size(), arr.size(), p);
            return "MODEL 已追加 " + appended.size() + " 个字段到 " + it.id();
        });
    }

    /** 与 FrontendCreateTools.validate 同形：合规返回 null，否则返回 "ERROR: ..." 字符串。 */
    private static String validate(SchemaValidator v, JsonNode payload, String tool) {
        List<String> errors = v.validate(payload).stream()
                .map(ValidationError::message)
                .toList();
        if (errors.isEmpty()) return null;
        log.warn("[Tool] {} rejected: {}", tool, errors);
        return "ERROR: 参数不合规：" + String.join("; ", errors);
    }

    private Optional<TodoItem> findByModuleId(String moduleId) {
        return todos.snapshot().stream()
                .filter(it -> it.type() == TodoType.CREATE_MODULE)
                .filter(it -> moduleId.equals(it.payload().path("moduleId").asText()))
                .findFirst();
    }

    private Optional<TodoItem> findByModelName(String name) {
        return todos.snapshot().stream()
                .filter(it -> it.type() == TodoType.CREATE_MODEL)
                .filter(it -> name.equals(it.payload().path("name").asText()))
                .findFirst();
    }
}
