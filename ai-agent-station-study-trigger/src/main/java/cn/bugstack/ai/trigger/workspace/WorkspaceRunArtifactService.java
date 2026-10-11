package cn.bugstack.ai.trigger.workspace;

import cn.bugstack.ai.domain.agent.service.execute.common.MeteredToolCallback;
import cn.bugstack.ai.domain.agent.service.execute.common.RunArtifactService;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetExceededException;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Safe built-in bridge; all ownership/provenance comes from the active server-authored run scope. */
@Service
public class WorkspaceRunArtifactService implements RunArtifactService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(WorkspaceRunArtifactService.class);
    private final WorkspaceOutcomeService outcomes;
    private final RunEventPublisher events;
    private final ObjectMapper json;
    public WorkspaceRunArtifactService(WorkspaceOutcomeService outcomes,RunEventPublisher events,ObjectMapper mapper) {
        this.outcomes=outcomes;this.events=events;this.json=mapper.copy();
        json.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        json.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(12).maxStringLength(210_000).maxNumberLength(100).build());
    }

    @Override public ToolDefinition definition() {
        return ToolDefinition.builder().name(TOOL_NAME)
                .description("保存当前任务的完整成果，返回可预览、下载的个人产物卡片。MARKDOWN 文档下载为 .md；TABLE 表格下载为 .csv；CODE 保存单个源码文件，不执行或验证代码。"
                        +"必须通过工具调用提交一个完整 JSON 对象，content 也必须是对象，不能直接把 Markdown 或源码写在冒号后。正文中的换行、双引号和反斜杠须按 JSON 字符串转义，不要在参数外包代码围栏。"
                        +"MARKDOWN 示例：{\"title\":\"行程\",\"format\":\"MARKDOWN\",\"content\":{\"markdown\":\"# 行程\\n正文\"}}。"
                        +"TABLE 示例：{\"title\":\"预算\",\"format\":\"TABLE\",\"content\":{\"columns\":[\"项目\",\"金额\"],\"rows\":[[\"交通\",\"100\"]]}}。"
                        +"CODE 示例：{\"title\":\"示例代码\",\"format\":\"CODE\",\"content\":{\"filename\":\"hello.py\",\"language\":\"python\",\"code\":\"print('hello')\\n\"}}。"
                        +"只填写所选格式的字段，提交完整正文，不要仅写保存声明或复制无关工具日志。来源运行和用户由系统填写，无需提供。相同成果不要重复创建。")
                .inputSchema("""
                    {"type":"object","additionalProperties":false,"required":["title","format","content"],"properties":{
                      "title":{"type":"string","minLength":1,"maxLength":160},
                      "format":{"type":"string","enum":["MARKDOWN","TABLE","CODE"],"description":"MARKDOWN 文档、TABLE 表格或 CODE 单个代码文件"},
                      "content":{"type":"object","additionalProperties":false,"description":"必须是对象。MARKDOWN 必须且只填写 markdown 字符串；TABLE 必须且只填写 columns、rows 字符串数组；CODE 必须且只填写 filename、language、code 字符串。不要混合不同格式的字段。",
                        "properties":{"markdown":{"type":"string","minLength":1,"maxLength":190000,"description":"MARKDOWN 的必填字段，完整 Markdown 正文；使用 JSON 字符串保存换行和引号"},
                          "columns":{"type":"array","minItems":1,"maxItems":30,"items":{"type":"string","minLength":1,"maxLength":160}},
                          "rows":{"type":"array","maxItems":1000,"items":{"type":"array","maxItems":30,"items":{"type":"string","maxLength":4000}}},
                          "filename":{"type":"string","minLength":1,"maxLength":160,"description":"CODE 必填。单个文件名，不含路径，例如 hello.py、Main.java、index.html、query.sql、Dockerfile"},
                          "language":{"type":"string","minLength":1,"maxLength":40,"description":"CODE 必填。语言标识，例如 python、java、javascript、html、sql 或 plaintext"},
                          "code":{"type":"string","minLength":1,"maxLength":190000,"description":"CODE 必填。完整源码字符串，保留缩进与换行，不包 Markdown 代码围栏；双引号、反斜杠和换行必须按 JSON 转义"}}}}}
                    """).build();
    }

    @Override public String create(String toolCallId,String arguments,Map<String,Object> context) {
        if(context==null||Boolean.TRUE.equals(context.get(MeteredToolCallback.DEBUG_READ_ONLY_KEY))
                ||Boolean.TRUE.equals(context.get("agent.eval"))||Boolean.TRUE.equals(context.get("agent.evaluation")))
            return error("ARTIFACT_SCOPE_UNAVAILABLE","调试或评测模式不能创建个人产物");
        String runId=text(context,"agent.run_id"),userId=text(context,"userId"),sessionId=text(context,"sessionId");
        if(runId==null||userId==null||runId.startsWith("eval-")||sessionId!=null&&sessionId.startsWith("eval-"))
            return error("ARTIFACT_SCOPE_UNAVAILABLE","当前调用缺少可保存产物的个人任务上下文");
        try {
            var budget=RunBudgetRegistry.find(Map.of("agent.run_id",runId,"userId",userId));
            if(budget==null)return error("ARTIFACT_SCOPE_UNAVAILABLE","当前任务未运行，不能自动创建产物");
            budget.checkActive();
            if(toolCallId==null||toolCallId.isBlank()||toolCallId.length()>256)
                return error("INVALID_ARGUMENTS","缺少有效工具调用标识");
            if(arguments==null||arguments.length()>220_000)
                return error("INVALID_ARGUMENTS","产物参数不能为空或超过 220000 字符");
            JsonNode body;
            try {body=json.readTree(arguments);}catch(JsonProcessingException malformed){
                var location=malformed.getLocation();
                String at=location==null?"":"（第 "+location.getLineNr()+" 行，第 "+location.getColumnNr()+" 列）";
                return error("INVALID_ARGUMENTS","产物参数必须是单个有效 JSON 对象"+at+"。content 必须为对象；Markdown 示例：{\"content\":{\"markdown\":\"# 标题\\n正文\"}}。正文的换行、引号和反斜杠必须按 JSON 转义，不要输出原始正文或代码围栏。");
            }
            Map<String,Object> saved=outcomes.createFromTool(userId,runId,toolCallId,body);
            Map<String,Object> result=metadata(saved);
            boolean created=Boolean.TRUE.equals(saved.get("created"));result.put("ok",true);result.put("replayed",!created);
            if(created) publishAfterCommit(runId,sessionId,result);
            return encode(result);
        } catch(ResponseStatusException invalid) {
            String reason=invalid.getReason()==null?"产物无法保存，请检查正文和任务归属":invalid.getReason();
            return error(invalid.getStatusCode().value()==409?"ARTIFACT_CONFLICT":"ARTIFACT_REJECTED",reason.substring(0,Math.min(300,reason.length())));
        } catch(SecurityException denied) {
            return error("ARTIFACT_SCOPE_UNAVAILABLE","产物只能保存到当前用户自己的任务");
        } catch(RuntimeException failure) {
            StreamingActivityTracker.rethrowCancellation(failure);
            if(failure instanceof RunBudgetExceededException)throw failure;
            log.warn("Artifact save unavailable runId={} failureType={}",runId,failure.getClass().getSimpleName());
            return error("ARTIFACT_SAVE_FAILED","产物暂时无法保存；正文仍可在回答中提供，请勿声称已经保存成功");
        }
    }

    private Map<String,Object> metadata(Map<String,Object> saved) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:new String[]{"artifactId","revision","title","format","runId","agentId","agentVersion"})result.put(key,saved.get(key));
        if("CODE".equals(saved.get("format"))&&saved.get("content") instanceof JsonNode content) {
            result.put("filename",content.path("filename").asText());result.put("language",content.path("language").asText());
        }
        String id=escape(String.valueOf(saved.get("artifactId"))),run=escape(String.valueOf(saved.get("runId")));
        int revision=((Number)saved.get("revision")).intValue();
        String api="/api/v1/workspace/outcomes/artifacts/"+id;
        result.put("previewUrl","/outcomes.html?runId="+run+"&artifactId="+id+"&revision="+revision);
        result.put("contentUrl",api+"?revision="+revision);
        result.put("downloadUrl",api+"/export?revision="+revision);
        return result;
    }
    private void publishAfterCommit(String runId,String sessionId,Map<String,Object> metadata) {
        var event=new LinkedHashMap<>(metadata);
        Runnable publish=()->{
            try {events.publish(runId,sessionId,"artifact_created",event);}
            catch(RuntimeException unavailable){log.warn("Artifact event unavailable runId={} failureType={}",runId,unavailable.getClass().getSimpleName());}
        };
        if(TransactionSynchronizationManager.isSynchronizationActive()&&TransactionSynchronizationManager.isActualTransactionActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){publish.run();}});
        else publish.run();
    }
    private String error(String code,String message){return encode(Map.of("ok",false,"error",code,"message",message));}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException("产物结果无法编码",failure);}}
    private static String text(Map<String,Object> scope,String key){Object value=scope.get(key);return value instanceof String s&&!s.isBlank()?s:null;}
    private static String escape(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
