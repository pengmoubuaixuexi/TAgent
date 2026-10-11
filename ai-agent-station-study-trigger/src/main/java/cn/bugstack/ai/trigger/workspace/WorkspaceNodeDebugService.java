package cn.bugstack.ai.trigger.workspace;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientVO;
import cn.bugstack.ai.domain.agent.service.IArmoryService;
import cn.bugstack.ai.domain.agent.service.execute.common.*;
import cn.bugstack.ai.domain.agent.service.prompt.ContextEnvelopeRenderAdvisor;
import cn.bugstack.ai.domain.agent.service.prompt.SystemPolicyComposer;
import cn.bugstack.ai.domain.agent.service.router.AgentToolRegistry;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.ai.domain.agent.service.workspace.WorkspaceExecutionGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs one owned, active node without invoking an Agent strategy or plan parser. */
@Service
public class WorkspaceNodeDebugService {
    private final WorkspaceService workspace;
    private final IArmoryService armory;
    private final IAgentRepository repository;
    private final ApplicationContext beans;
    private final AgentToolRegistry tools;
    private final McpToolCatalogService catalog;
    private final ToolResultReferenceService resultReferences;
    private final ToolCallLedger ledger;
    private final WorkspaceExecutionGuard executions;
    private final LlmObservationRecorder observations;
    private final ObjectMapper json;
    private final Cache<String,AtomicInteger> attempts=Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(10)).build();
    private final Set<String> activeUsers=java.util.concurrent.ConcurrentHashMap.newKeySet();

    public WorkspaceNodeDebugService(WorkspaceService workspace,IArmoryService armory,IAgentRepository repository,
                                    ApplicationContext beans,AgentToolRegistry tools,McpToolCatalogService catalog,
                                    ToolResultReferenceService resultReferences,ToolCallLedger ledger,
                                    WorkspaceExecutionGuard executions,LlmObservationRecorder observations,ObjectMapper json) {
        this.workspace=workspace;this.armory=armory;this.repository=repository;this.beans=beans;this.tools=tools;
        this.catalog=catalog;this.resultReferences=resultReferences;this.ledger=ledger;this.executions=executions;
        this.observations=observations;this.json=json;
    }

    record Prepared(String agentId,int version,String nodeId,int nodeIndex,String role,String name,
                    String input,String upstream,boolean allowTools,JsonNode config) {}

    Prepared prepare(String user,String id,String nodeId,JsonNode input) {
        Map<String,Object> active=workspace.agent(user,id); // Performs the same owner check as the editor.
        if (!Boolean.TRUE.equals(active.get("editable"))) throw WorkspaceService.bad("只有本人的可编辑 Agent 支持节点调试");
        JsonNode config=json.valueToTree(active);
        int version=WorkspaceService.requiredInt(input,"version",1,Integer.MAX_VALUE);
        if (version!=config.path("version").asInt()) throw WorkspaceService.conflict("已启用版本已变化，请重新加载后调试");
        if (config.path("status").asInt()!=1) throw WorkspaceService.bad("请先启用 Agent 再调试节点");
        if(input.has("allowReadOnlyTools")&&!input.path("allowReadOnlyTools").isBoolean()) throw WorkspaceService.bad("工具开关必须是布尔值");
        int index=-1;
        for(int i=0;i<config.path("nodes").size();i++) if(nodeId.equals(config.path("nodes").get(i).path("nodeId").asText()))index=i;
        if(index<0) throw WorkspaceService.bad("选中的节点不属于当前已启用版本");
        JsonNode node=config.path("nodes").get(index);
        return new Prepared(id,version,nodeId,index,node.path("role").asText(),node.path("name").asText(),
                WorkspaceService.text(input,"input",1,12000),WorkspaceService.optionalText(input,"upstream",12000),
                input.path("allowReadOnlyTools").asBoolean(false),config);
    }

    public Map<String,Object> debug(String user,String id,String nodeId,JsonNode input) {
        if(attempts.get(user,k->new AtomicInteger()).incrementAndGet()>10)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"节点调试过于频繁，请稍后再试");
        if(!activeUsers.add(user))throw new ResponseStatusException(HttpStatus.CONFLICT,"已有节点调试正在运行");
        Map<String,String> previous=MDC.getCopyOfContextMap();
        String runId="debug-"+UUID.randomUUID(),sessionId=runId;
        RunBudgetRegistry.State budget=null;
        try (WorkspaceExecutionGuard.Lease ignored=executions.startRun(id)) {
            Prepared prepared=prepare(user,id,nodeId,input);
            MDC.put("userId",user);MDC.put("agentId",id);MDC.put("runId",runId);MDC.put("agent.run_id",runId);
            MDC.put("sessionId",sessionId);MDC.put("tenantId","default");MDC.put("step","workspace_node_debug");
            budget=RunBudgetRegistry.start(runId,user,new RunBudgetRegistry.Limits(Duration.ofSeconds(120),6,12,60000));
            long start=System.nanoTime();
            armory.ensureArmed(id);
            var flow=repository.queryAiAgentClientsByAgentId(id).stream()
                    .sorted(Comparator.comparing(c->c.getSequence()==null?Integer.MAX_VALUE:c.getSequence())).toList();
            if(prepared.nodeIndex()>=flow.size())throw WorkspaceService.bad("节点装配不完整，请重新启用配置");
            String clientId=flow.get(prepared.nodeIndex()).getClientId();
            var nodePolicy=repository.queryWorkspaceNodePolicy(clientId);
            if(nodePolicy==null||!id.equals(nodePolicy.agentId()))throw WorkspaceService.bad("节点权限与已启用配置不匹配");
            var clients=repository.AiClientVOByClientIds(List.of(clientId));
            if(clients.size()!=1)throw WorkspaceService.bad("节点客户端不可用");
            AiClientVO client=clients.get(0);
            DebugCapture capture=new DebugCapture();
            Map<String,Object> scope=new LinkedHashMap<>();
            scope.put("agent.run_id",runId);scope.put("userId",user);scope.put("agentId",id);scope.put("clientId",clientId);
            scope.put("sessionId",sessionId);scope.put("stepLabel","调试 · "+prepared.name());
            scope.put(MeteredToolCallback.DEBUG_READ_ONLY_KEY,true);scope.put(ToolCallLedger.CTX_ENABLED_KEY,true);
            boolean executeTools=prepared.allowTools()&&WorkspaceTemplates.executable(prepared.role());
            scope.put(ToolCapabilities.TOOL_CONTEXT_KEY,(executeTools?ToolCapabilityProfile.ALL:ToolCapabilityProfile.NONE).name());
            var manager=new RobustToolCallingManager(ToolCallingManager.builder().build(),null,null,false,null,3);
            manager.setWorkspaceNodePolicy(nodePolicy);manager.setMcpToolCatalogService(catalog);
            manager.setRequestToolEnabled(executeTools);manager.setRequestToolMaxCalls(2);manager.setToolResultReferenceService(resultReferences);
            // This manager has no ask_user gate: the debugger accepts input through its own form.
            OpenAiChatModel original=beans.getBean(client.getModelBeanName(),OpenAiChatModel.class);
            OpenAiChatModel debugModel=original.mutate().toolCallingManager(manager)
                    .retryTemplate(org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build()).build();
            List<Advisor> advisors=new ArrayList<>();
            for(String name:client.getAdvisorBeanNameList())advisors.add(beans.getBean(name,Advisor.class));
            advisors.add(new ContextEnvelopeRenderAdvisor());advisors.add(capture);
            var prompts=repository.queryAiClientSystemPromptMapByClientIds(List.of(clientId));
            StringBuilder system=new StringBuilder("单节点调试：直接展示本节点输出，不驱动其他节点，不解析或执行你输出的计划。\n");
            client.getPromptIdList().stream().sorted().forEach(pid->{if(prompts.containsKey(pid))system.append(prompts.get(pid).getPromptContent()).append('\n');});
            if(prepared.role().equals("EXECUTOR_CLIENT"))system.append("\n子任务指令：\n").append(nodePolicy.taskPrompt());
            String userPrompt="测试输入：\n"+prepared.input()+(prepared.upstream().isBlank()?"":"\n\n用户提供的上游输出示例（仅供本节点参考）：\n"+prepared.upstream());
            ChatClient debugClient=ChatClient.builder(debugModel).defaultSystem(SystemPolicyComposer.prepend(system.toString()))
                    .defaultAdvisors(advisors).defaultToolCallbacks(executeTools?tools.getToolCallbacks(clientId):List.of()).build();
            ChatResponse response=null;Throwable failure=null;String output="",error="";
            try {
                ChatClientResponse result=invoke(debugClient,userPrompt,scope,"default:"+user+":"+sessionId);
                response=result==null?null:result.chatResponse();budget.checkActive();
                output=response==null||response.getResult()==null?"":response.getResult().getOutput().getText();
                if(output==null||output.isBlank())throw new IllegalStateException("模型没有返回节点输出");
            } catch (RuntimeException e) {
                failure=e;error=e instanceof RunBudgetExceededException?e.getMessage():"节点调试未完成（"+e.getClass().getSimpleName()+"），未自动重试；请检查配置或用量。";
            } finally {
                observations.record(LlmCallContext.builder().stepName("workspace_node_debug").userId(user).agentId(id)
                        .clientId(clientId).runId(runId).sessionId(sessionId).prompt(userPrompt)
                        .billingScope(LlmObservationRecorder.BILLING_SCOPE_USER_CHARGEABLE).build(),response,(System.nanoTime()-start)/1_000_000,failure);
            }
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("runId",runId);result.put("version",prepared.version());result.put("nodeName",prepared.name());
            result.put("status",failure==null?"COMPLETED":"FAILED");result.put("output",bounded(output,50000));result.put("error",error);
            result.put("elapsedMs",(System.nanoTime()-start)/1_000_000);result.put("systemPrompt",capture.system);
            result.put("userPrompt",capture.user);result.put("sources",capture.sources);result.put("tools",ledger.snapshot(runId));
            result.put("usage",budget.snapshot());return result;
        } finally {
            RunBudgetRegistry.finish(runId);ledger.clear(runId);activeUsers.remove(user);
            try { catalog.cleanupRun(runId); }
            finally { if(previous==null)MDC.clear();else MDC.setContextMap(previous); }
        }
    }

    protected ChatClientResponse invoke(ChatClient client,String input,Map<String,Object> scope,String conversationId) {
        Map<String,Object> advisorContext=new LinkedHashMap<>(scope);advisorContext.put(ChatMemory.CONVERSATION_ID,conversationId);
        return client.prompt().user(input).toolContext(scope).options(OpenAiChatOptions.builder().maxTokens(4096).build())
                .advisors(a->a.params(advisorContext)).call().chatClientResponse();
    }

    static String bounded(String value,int max) {return value==null?"":value.length()<=max?value:value.substring(0,max)+"\n[显示已截断]";}
    /** Capture after retrieval and prompt rendering, excluding provider keys and arbitrary context values. */
    static class DebugCapture implements BaseAdvisor {
        String system="",user=""; List<Map<String,Object>> sources=new ArrayList<>();
        public ChatClientRequest before(ChatClientRequest request,AdvisorChain chain) {
            system=bounded(request.prompt().getInstructions().stream().filter(m->m.getMessageType()==org.springframework.ai.chat.messages.MessageType.SYSTEM)
                    .map(m->m.getText()==null?"":m.getText()).collect(java.util.stream.Collectors.joining("\n")),60000);
            user=bounded(request.prompt().getUserMessage()==null?"":request.prompt().getUserMessage().getText(),60000);
            if(request.context().get("qa_retrieved_documents") instanceof List<?> docs)for(Object item:docs.stream().limit(20).toList()) {
                if(!(item instanceof Document doc))continue;
                Map<String,Object> source=new LinkedHashMap<>();source.put("excerpt",bounded(doc.getText(),2000));
                for(String key:List.of("source","file_name","title","knowledge"))if(doc.getMetadata().get(key)!=null)source.put(key,bounded(String.valueOf(doc.getMetadata().get(key)),500));
                sources.add(source);
            }
            return request;
        }
        public ChatClientResponse after(ChatClientResponse response,AdvisorChain chain) {return response;}
        public ChatClientResponse adviseCall(ChatClientRequest request,CallAdvisorChain chain) {return chain.nextCall(before(request,chain));}
        public Flux<ChatClientResponse> adviseStream(ChatClientRequest request,StreamAdvisorChain chain) {return chain.nextStream(before(request,chain));}
        public int getOrder(){return Integer.MAX_VALUE-100;}
        public String getName(){return "WorkspaceDebugCapture";}
    }
}
