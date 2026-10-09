package cn.bugstack.ai.trigger.workspace;

import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallContext;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder;
import cn.bugstack.ai.domain.agent.service.support.OpenAiCompatibleApiSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** LLM produces untrusted text fields only; it cannot create resources or choose credentials. */
@Service
public class WorkspaceDraftService {
    private final WorkspaceService workspace;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final LlmObservationRecorder observations;
    private final Cache<String,AtomicInteger> attempts=Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(10)).build();
    public WorkspaceDraftService(WorkspaceService workspace,@org.springframework.beans.factory.annotation.Qualifier("mysqlJdbcTemplate") JdbcTemplate db,ObjectMapper json,LlmObservationRecorder observations) {
        this.workspace=workspace;this.db=db;this.json=json;this.observations=observations;
    }
    public ObjectNode draft(String user,JsonNode input) {
        if(attempts.get(user,k->new AtomicInteger()).incrementAndGet()>10)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"草稿生成过于频繁，请稍后重试");
        String requirement=WorkspaceService.text(input,"requirement",5,4000);
        String modelId=WorkspaceService.text(input,"modelId",1,64);
        String strategy=input.hasNonNull("strategy")&&!input.path("strategy").asText().isBlank()?input.path("strategy").asText():"fixed";
        WorkspaceTemplates.strategyBean(strategy);
        var rows=db.queryForList("""
                SELECT a.base_url,a.api_key,a.completions_path,a.embeddings_path,m.model_name
                FROM ai_client_model m JOIN ai_client_api a ON a.api_id=m.api_id
                WHERE m.model_id=? AND m.platform_enabled=1 AND m.status=1 AND a.status=1
                """,modelId);
        if(rows.size()!=1) throw WorkspaceService.bad("模型不可用");
        var row=rows.get(0); String base=(String)row.get("base_url");
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var requests = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofSeconds(90));
        var api=OpenAiApi.builder().baseUrl(base).apiKey((String)row.get("api_key"))
                .restClientBuilder(org.springframework.web.client.RestClient.builder().requestFactory(requests))
                .completionsPath(OpenAiCompatibleApiSupport.chatCompletionsPath(base,(String)row.get("completions_path")))
                .embeddingsPath(OpenAiCompatibleApiSupport.embeddingsPath(base,(String)row.get("embeddings_path"))).build();
        ChatClient client=ChatClient.builder(OpenAiChatModel.builder().openAiApi(api)
                .retryTemplate(org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build())
                .defaultOptions(OpenAiChatOptions.builder().model((String)row.get("model_name")).maxTokens(5000).build()).build()).build();
        ChatResponse response=null; Throwable failure=null; long started=System.nanoTime();
        try {
            response=client.prompt().system(WorkspaceTemplates.BUILDER_PROMPT+"""
                    \n本次为节点编辑器生成骨架。输出严格 JSON 对象，仅包含 name（80字内）、description（500字内）、nodes 数组。
                    每个节点仅包含 role、name、systemPrompt、outputRequirement、taskPrompt（仅 Flow 执行节点填写）。
                    Auto / Flow 必须按提供的节点角色顺序逐个生成；Fixed 可根据需求生成 1–8 个 DEFAULT 顺序节点。
                    每个节点有独立的业务提示词，不要把整套要求重复到每个节点。
                    Flow 执行节点 systemPrompt 是最终整合要求，taskPrompt 是独立子任务要求。
                    输出内容须符合运行时阶段职责，不改写 JSON 规划协议、系统权限与固定的执行控制。
                    不生成密钥、连接地址、工具ID、用户ID、代码或发布操作；不要编造已经接入的工具。
                    """).user("执行模式："+strategy+"\n必须生成的节点："+json.writeValueAsString(WorkspaceTemplates.nodes(strategy))+"\n用户需求：\n"+requirement).call().chatResponse();
            ObjectNode draft=normalize(response.getResult().getOutput().getText(),modelId,strategy);
            ObjectNode validated=workspace.validateAgent(user,draft);
            validated.put("explanations","已生成未保存的骨架。请核对提示词，并选择自己的工具和 Advisor 后保存。");
            return validated;
        } catch(ResponseStatusException e) {failure=e;throw e;}
        catch(Exception e) {failure=e;throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"草稿生成失败，未保存任何配置；可重试或直接使用模板");}
        finally { observations.record(LlmCallContext.builder().stepName("workspace_draft").userId(user)
                .model((String)row.get("model_name")).billingScope(LlmObservationRecorder.BILLING_SCOPE_USER_CHARGEABLE)
                .sessionId("workspace-draft:"+user).build(),response,(System.nanoTime()-started)/1_000_000,failure); }
    }
    ObjectNode normalize(String output,String model,String strategy) throws Exception {
        String text=output.trim(); if(text.startsWith("```")) text=text.replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","");
        if(text.length()>60000) throw WorkspaceService.bad("生成的草稿过长");
        JsonNode generated=json.readTree(text);
        ObjectNode result=json.createObjectNode().put("name",WorkspaceService.text(generated,"name",1,80))
                .put("description",WorkspaceService.optionalText(generated,"description",500))
                .put("strategy",strategy).put("status",1).put("schemaVersion",2);
        var steps=WorkspaceTemplates.steps(strategy);
        int count=generated.path("nodes").size();
        if (!generated.path("nodes").isArray() || count<1 || count>8 || (!strategy.equals("fixed") && count!=steps.size()))
            throw WorkspaceService.bad("生成的节点结构不完整，请重新生成");
        var nodes=result.putArray("nodes");
        for (int i=0;i<count;i++) {
            var step=steps.get(strategy.equals("fixed")?0:i); var n=generated.path("nodes").get(i);
            if (!step.type().equals(n.path("role").asText())) throw WorkspaceService.bad("生成的节点角色不匹配");
            var node=nodes.addObject().put("nodeId","step_"+(i+1)).put("role",step.type())
                    .put("name",WorkspaceService.text(n,"name",1,50)).put("modelId",model)
                    .put("systemPrompt",WorkspaceService.text(n,"systemPrompt",1,12000))
                    .put("outputRequirement",WorkspaceService.optionalText(n,"outputRequirement",4000))
                    .put("taskPrompt",step.type().equals("EXECUTOR_CLIENT")?WorkspaceService.optionalText(n,"taskPrompt",12000):"");
            node.putArray("mcpBindings"); node.putArray("advisors");
            node.put("usePublicTools",WorkspaceTemplates.executable(step.type()));
        }
        return result;
    }
}
