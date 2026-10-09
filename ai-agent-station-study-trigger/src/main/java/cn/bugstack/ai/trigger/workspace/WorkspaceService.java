package cn.bugstack.ai.trigger.workspace;

import cn.bugstack.ai.domain.agent.service.IArmoryService;
import cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository;
import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.ai.domain.agent.service.security.WorkspaceMcpPolicy;
import cn.bugstack.ai.domain.agent.service.workspace.WorkspaceExecutionGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.util.*;

/** Private configuration aggregates. All mutations derive owner from login. */
@Service
public class WorkspaceService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final IArmoryService armory;
    private final McpClientRegistry registry;
    private final McpToolCatalogService catalog;
    private final WorkspaceMcpPolicy policy;
    private final WorkspaceExecutionGuard executions;
    private final IWorkspaceAccessRepository access;
    public WorkspaceService(@org.springframework.beans.factory.annotation.Qualifier("mysqlJdbcTemplate") JdbcTemplate db, ObjectMapper json, IArmoryService armory,
                            McpClientRegistry registry, McpToolCatalogService catalog, WorkspaceMcpPolicy policy,
                            WorkspaceExecutionGuard executions, IWorkspaceAccessRepository access) {
        this.db=db; this.json=json; this.armory=armory; this.registry=registry; this.catalog=catalog; this.policy=policy;
        this.executions=executions;
        this.access=access;
    }
    public Map<String,Object> options(String user) {
        return Map.of("models", models(), "templates", WorkspaceTemplates.templates(),
                "advisors", WorkspaceTemplates.advisors(), "knowledge", db.queryForList("""
                    SELECT knowledge_tag AS knowledgeTag, MAX(rag_name) AS name FROM ai_client_rag_order
                    WHERE user_id=? AND status=1 GROUP BY knowledge_tag ORDER BY knowledge_tag
                    """, user), "mcpAllowedHosts", policy.allowedHosts());
    }
    public List<Map<String,Object>> models() {
        return db.queryForList("""
                SELECT m.model_id AS modelId,m.model_name AS modelName,m.model_type AS modelType,m.tier
                FROM ai_client_model m JOIN ai_client_api a ON a.api_id=m.api_id
                WHERE m.platform_enabled=1 AND m.status=1 AND a.status=1 ORDER BY m.model_id
                """);
    }
    public List<Map<String,Object>> agents(String user) {
        return db.queryForList("""
                SELECT agent_id AS agentId,agent_name AS name,description,
                REPLACE(strategy,'AgentExecuteStrategy','') AS strategy,status,
                (workspace_config IS NOT NULL AND source_agent_id IS NULL) AS editable,
                workspace_version AS version,source_agent_id AS sourceAgentId
                FROM ai_agent WHERE owner_user_id=? AND archived=0 AND (source_agent_id IS NULL OR source_agent_id<>'workspace-builder') ORDER BY create_time DESC
                """, user);
    }
    public Map<String,Object> agent(String user,String id) {
        var row = ownedAgent(user,id,false);
        Map<String,Object> result = summary(row);
        if (row.get("workspace_config") != null && row.get("source_agent_id") == null) {
            result.putAll(json.convertValue(WorkspaceTemplates.upgrade(parse(row.get("workspace_config")),json), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
        }
        return result;
    }
    public List<Map<String,Object>> publicAgents() {
        return db.queryForList("""
                SELECT agent_id AS agentId,agent_name AS name,description,
                REPLACE(strategy,'AgentExecuteStrategy','') AS strategy
                FROM ai_agent WHERE is_public=1 AND status=1 AND archived=0 ORDER BY agent_id
                """);
    }
    /** Creates only database identities; never connects to MCPs during login. */
    @Transactional public void provisionPublicResources(String user) {
        lockUser(user);
        for(String source:db.queryForList("SELECT mcp_id FROM ai_client_tool_mcp WHERE is_public=1 AND status=1 AND source_mcp_id IS NULL AND owner_user_id<>?",String.class,user))
            cloneResource("tool_mcp",source,user,new HashMap<>());
        for(String source:db.queryForList("""
            SELECT a.agent_id FROM ai_agent a WHERE a.is_public=1 AND a.status=1 AND a.archived=0
            AND a.source_agent_id IS NULL AND a.owner_user_id<>?
            AND NOT EXISTS(SELECT 1 FROM ai_agent c WHERE c.owner_user_id=? AND c.source_agent_id=a.agent_id)
            """,String.class,user,user)) copyPublic(user,source,false);
    }
    @Transactional
    public Map<String,Object> saveAgent(String user,String id,JsonNode input) {
        lockUser(user);
        ObjectNode clean = validateAgent(user,input);
        Map<String,Object> existing = id == null ? null : ownedAgent(user,id,true);
        Map<String,Set<String>> previousResources = Map.of();
        if (existing != null && (existing.get("workspace_config") == null || existing.get("source_agent_id") != null))
            throw forbidden("公共和旧版 Agent 不能在个人编辑器中修改，请使用模板新建");
        if (existing == null) {
            checkAgentLimit(user);
            id = id("wa");
            db.update("""
                INSERT INTO ai_agent(agent_id,agent_name,description,channel,strategy,status,owner_user_id,workspace_config)
                VALUES(?,?,?,'agent',?,?,?,?)
                """, id,clean.path("name").asText(),clean.path("description").asText(),
                    WorkspaceTemplates.strategyBean(clean.path("strategy").asText()),clean.path("status").asInt(),user,clean.toString());
        } else {
            holdUpdate(Set.of(id));
            int version = requiredInt(input,"version",1,Integer.MAX_VALUE);
            int changed = db.update("""
                UPDATE ai_agent SET agent_name=?,description=?,strategy=?,status=?,workspace_config=?,
                workspace_version=workspace_version+1,update_time=NOW()
                WHERE agent_id=? AND owner_user_id=? AND workspace_version=? AND archived=0
                """,clean.path("name").asText(),clean.path("description").asText(),
                    WorkspaceTemplates.strategyBean(clean.path("strategy").asText()),clean.path("status").asInt(),clean.toString(),id,user,version);
            if (changed!=1) throw conflict("配置已在其他页面更新，请刷新后重试");
            previousResources = removePrivateGraph(id,user);
        }
        buildGraph(user,id,clean);
        String agentId=id;
        Map<String,Set<String>> obsolete = previousResources;
        afterCommit(() -> armory.invalidateAgent(agentId,obsolete));
        return agent(user,id);
    }
    public ObjectNode validateAgent(String user,JsonNode input) {
        ObjectNode clean = json.createObjectNode();
        clean.put("name",text(input,"name",1,80));
        clean.put("description",optionalText(input,"description",500));
        String strategy=text(input,"strategy",1,8); WorkspaceTemplates.strategyBean(strategy); clean.put("strategy",strategy);
        clean.put("status", input.has("status") ? requiredInt(input,"status",0,1) : 1);
        // Accept previous clients without making their previously shared resources mutable in place.
        if (!input.has("nodes")) {
            // Validate legacy selections before the adapter drops non-executable role bindings.
            validateNodeResources(user, input, models());
            input = WorkspaceTemplates.upgrade(input,json);
        }
        var supplied=input.path("nodes");
        var steps=WorkspaceTemplates.steps(strategy);
        if (!supplied.isArray() || supplied.isEmpty() || supplied.size()>8
                || (!strategy.equals("fixed") && supplied.size()!=steps.size())) throw bad("节点数量与执行模式不匹配");
        clean.put("schemaVersion",2);
        var nodes=clean.putArray("nodes");
        Set<String> ids=new HashSet<>();
        var availableModels=models();
        for (int i=0;i<supplied.size();i++) {
            JsonNode n=supplied.get(i);
            String role=text(n,"role",1,40);
            if (!role.equals(steps.get(strategy.equals("fixed")?0:i).type())) throw bad("节点角色或顺序与执行模式不匹配");
            String nodeId=text(n,"nodeId",1,64);
            if (!nodeId.matches("[a-zA-Z0-9_-]+") || !ids.add(nodeId)) throw bad("节点标识无效或重复");
            ObjectNode node=validateNodeResources(user,n,availableModels);
            node.put("nodeId",nodeId).put("role",role).put("name",text(n,"name",1,50));
            node.put("outputRequirement",optionalText(n,"outputRequirement",4000));
            String taskPrompt=optionalText(n,"taskPrompt",12000);
            if (!taskPrompt.isEmpty() && !role.equals("EXECUTOR_CLIENT")) throw bad("只有 Flow 执行节点支持独立任务指令");
            node.put("taskPrompt",taskPrompt);
            if(n.has("usePublicTools")&&!n.path("usePublicTools").isBoolean())throw bad("公共工具开关必须为布尔值");
            boolean usePublic=n.path("usePublicTools").asBoolean(false);
            if(usePublic&&!WorkspaceTemplates.executable(role))throw bad("仅执行节点可以启用公共工具库");
            node.put("usePublicTools",usePublic);
            if (!WorkspaceTemplates.executable(role) && !node.path("mcpBindings").isEmpty())
                throw bad("分析、规划、质检和总结节点不执行业务工具，请在执行节点配置 MCP");
            nodes.add(node);
        }
        return clean;
    }
    private ObjectNode validateNodeResources(String user,JsonNode input,List<Map<String,Object>> availableModels) {
        ObjectNode clean=json.createObjectNode();
        String model=text(input,"modelId",1,64);
        if (availableModels.stream().noneMatch(m -> model.equals(m.get("modelId")))) throw bad("模型不存在或未向用户开放");
        clean.put("modelId",model);
        clean.put("systemPrompt",text(input,"systemPrompt",1,12000));
        var bindings=clean.putArray("mcpBindings"); Set<String> seen=new HashSet<>();
        boolean legacy=!input.has("mcpBindings");
        JsonNode mcps=input.path(legacy?"mcpIds":"mcpBindings");
        if (!mcps.isMissingNode() && (!mcps.isArray() || mcps.size()>10)) throw bad("最多选择 10 个工具连接");
        for (JsonNode m:mcps) {
            String mcp=legacy?(m.isTextual()?m.asText():""):text(m,"mcpId",1,64);
            if (mcp.isBlank() || !seen.add(mcp)) throw bad("工具连接列表无效或重复");
            // Using a granted platform connection does not grant permission to edit its credentials.
            if (!access.ownsMcp(user,mcp)) throw bad("只能选择本人可用的工具连接；连接或平台来源可能已停用");
            boolean all=legacy || (m.path("allTools").isBoolean() && m.path("allTools").asBoolean());
            var binding=bindings.addObject().put("mcpId",mcp).put("allTools",all);
            if (m.has("loadMode")) {
                if (!"ON_DEMAND".equals(m.path("loadMode").asText())) throw bad("工具连接加载方式无效");
                if (!all) throw bad("按需加载请使用连接级绑定；旧逐工具配置可保留原方式");
                binding.put("loadMode","ON_DEMAND");
            }
            var names=binding.putArray("toolNames");
            var selected=m.path("toolNames");
            if (!legacy && (!selected.isArray() || selected.size()>100)) throw bad("工具名称列表无效，最多选择 100 个工具");
            Set<String> unique=new HashSet<>();
            for (JsonNode name:selected) {
                if (!name.isTextual() || !name.asText().matches("[a-zA-Z0-9_.:/-]{1,128}") || !unique.add(name.asText()))
                    throw bad("工具名称无效或重复");
                names.add(name.asText());
            }
            if (!all && names.isEmpty()) throw bad("请选择至少一个工具，或明确选择连接的全部工具");
        }
        var advisors=clean.putArray("advisors"); seen.clear();
        JsonNode list=input.path("advisors");
        if (!list.isMissingNode() && (!list.isArray() || list.size()>6)) throw bad("Advisor 列表无效");
        for (JsonNode a:list) {
            String type=text(a,"type",1,40);
            if (!WorkspaceTemplates.ADVISORS.contains(type) || !seen.add(type)) throw bad("Advisor 类型无效或重复");
            ObjectNode entry=advisors.addObject().put("type",type);
            if (type.equals("ChatMemory")) entry.put("maxMessages",a.has("maxMessages")?requiredInt(a,"maxMessages",2,100):20);
            if (Set.of("LongTermMemory","EpisodicMemory","RagAnswer").contains(type))
                entry.put("topK",a.has("topK")?requiredInt(a,"topK",1,20):4);
            if (type.equals("RagAnswer")) {
                String tag=text(a,"knowledgeTag",1,128);
                if (!tag.matches("[\\p{L}\\p{N}_ .-]+")) throw bad("知识库标签包含不支持的字符");
                if (db.queryForObject("SELECT COUNT(*) FROM ai_client_rag_order WHERE user_id=? AND knowledge_tag=? AND status=1",Integer.class,user,tag)==0)
                    throw bad("只能使用本人的知识库");
                entry.put("knowledgeTag",tag);
            }
        }
        return clean;
    }
    @Transactional public void archiveAgent(String user,String id) {
        lockUser(user); ownedAgent(user,id,true); holdUpdate(Set.of(id));
        db.update("UPDATE ai_agent SET archived=1,status=0,workspace_version=workspace_version+1 WHERE agent_id=? AND owner_user_id=?",id,user);
        afterCommit(() -> armory.invalidateAgent(id));
    }
    private void buildGraph(String user,String agentId,JsonNode config) {
        int sequence=0;
        var steps=WorkspaceTemplates.steps(config.path("strategy").asText());
        for (JsonNode node:config.path("nodes")) {
            var step=steps.get(config.path("strategy").asText().equals("fixed")?0:sequence);
            String client=id("wc"),prompt=id("wp"),modelId=id("wm");
            copyModel(node.path("modelId").asText(),modelId,user);
            JsonNode sourceCapabilities=parse(db.queryForObject("SELECT capabilities_json FROM ai_client_model WHERE model_id=?",String.class,modelId));
            if (!sourceCapabilities.isObject() && !sourceCapabilities.isNull()) throw bad("平台模型能力配置无效，请联系管理员");
            ObjectNode capabilities=sourceCapabilities.isNull()?json.createObjectNode():(ObjectNode)sourceCapabilities;
            ObjectNode scope=capabilities.putObject("workspaceNode").put("agentId",agentId).put("clientId",client)
                    .put("nodeId",node.path("nodeId").asText()).put("taskPrompt",node.path("taskPrompt").asText());
            scope.set("mcpBindings",node.path("mcpBindings").deepCopy());
            var publicIds=scope.putArray("publicMcpIds");
            if(node.path("usePublicTools").asBoolean(false)) access.publicMcpOrigins(user).keySet().forEach(publicIds::add);
            db.update("UPDATE ai_client_model SET capabilities_json=? WHERE model_id=?",capabilities.toString(),modelId);
            for (JsonNode m:node.path("mcpBindings")) link("model",modelId,"tool_mcp",m.path("mcpId").asText());
            List<String> advisorIds=new ArrayList<>();
            for (JsonNode a:node.path("advisors")) {
                String aid=id("wv"),type=a.path("type").asText(); ObjectNode ext=json.createObjectNode();
                if (a.has("maxMessages")) ext.set("maxMessages",a.get("maxMessages"));
                if (a.has("topK")) ext.set("topK",a.get("topK"));
                if (a.has("knowledgeTag")) ext.put("filterExpression","knowledge == '"+a.path("knowledgeTag").asText()+"'");
                db.update("INSERT INTO ai_client_advisor(advisor_id,advisor_name,advisor_type,order_num,ext_param,status,owner_user_id) VALUES(?,?,?,?,?,1,?)",
                        aid,type,type,advisorIds.size(),ext.toString(),user); advisorIds.add(aid);
            }
            String name=node.path("name").asText();
            db.update("INSERT INTO ai_client(client_id,client_name,description,status,owner_user_id) VALUES(?,?,?,1,?)",client,name,"个人 Agent 节点",user);
            db.update("INSERT INTO ai_client_system_prompt(prompt_id,prompt_name,prompt_content,description,status,owner_user_id) VALUES(?,?,?,'个人 Agent',1,?)",
                    prompt,name,node.path("systemPrompt").asText()+"\n\n输出要求：\n"+node.path("outputRequirement").asText()+"\n\n阶段职责：\n"+step.system(),user);
            link("client",client,"model",modelId); link("client",client,"prompt",prompt);
            for (String a:advisorIds) link("client",client,"advisor",a);
            db.update("INSERT INTO ai_agent_flow_config(agent_id,client_id,client_name,client_type,sequence,step_prompt,status,create_time) VALUES(?,?,?,?,?,?,1,NOW())",
                    agentId,client,name,step.type(),++sequence,step.prompt());
        }
    }
    private void copyModel(String from,String to,String user) {
        if (db.update("""
            INSERT INTO ai_client_model(model_id,api_id,model_usage,model_name,model_type,tier,capabilities_json,status,owner_user_id,source_model_id)
            SELECT ?,api_id,model_usage,model_name,model_type,tier,capabilities_json,status,?,COALESCE(source_model_id,model_id) FROM ai_client_model WHERE model_id=?
            """,to,user,from)!=1) throw bad("源模型不存在");
    }
    private void link(String sourceType,String source,String targetType,String target) {
        db.update("INSERT INTO ai_client_config(source_type,source_id,target_type,target_id,status) VALUES(?,?,?,?,1)",sourceType,source,targetType,target);
    }
    private Map<String,Set<String>> removePrivateGraph(String agentId,String user) {
        List<String> clients=db.queryForList("SELECT client_id FROM ai_agent_flow_config WHERE agent_id=?",String.class,agentId);
        Map<String,Set<String>> resources=new HashMap<>();
        for (String client:clients) {
            for(var relation:db.queryForList("SELECT target_type,target_id FROM ai_client_config WHERE source_type='client' AND source_id=?",client))
                resources.computeIfAbsent((String)relation.get("target_type"),k->new HashSet<>()).add((String)relation.get("target_id"));
            db.update("DELETE FROM ai_client_config WHERE source_type='client' AND source_id=?",client);
            db.update("DELETE FROM ai_client WHERE client_id=? AND owner_user_id=?",client,user);
        }
        for(String model:resources.getOrDefault("model",Set.of())) {
            db.update("DELETE FROM ai_client_config WHERE source_type='model' AND source_id=?",model);
            db.update("DELETE FROM ai_client_model WHERE model_id=? AND owner_user_id=? AND platform_enabled=0",model,user);
        }
        for(String prompt:resources.getOrDefault("prompt",Set.of())) db.update("DELETE FROM ai_client_system_prompt WHERE prompt_id=? AND owner_user_id=?",prompt,user);
        for(String advisor:resources.getOrDefault("advisor",Set.of())) db.update("DELETE FROM ai_client_advisor WHERE advisor_id=? AND owner_user_id=?",advisor,user);
        db.update("DELETE FROM ai_agent_flow_config WHERE agent_id=?",agentId);
        resources.put("client", new HashSet<>(clients));
        return resources;
    }
    @Transactional public Map<String,Object> usePublic(String user,String sourceId) {
        lockUser(user);
        return copyPublic(user,sourceId,true);
    }
    private Map<String,Object> copyPublic(String user,String sourceId,boolean checkQuota) {
        var sources=db.queryForList("SELECT * FROM ai_agent WHERE agent_id=? AND is_public=1 AND status=1 AND archived=0",sourceId);
        if(sources.isEmpty()) throw notFound();
        var copies=db.queryForList("SELECT agent_id,archived FROM ai_agent WHERE owner_user_id=? AND source_agent_id=?",user,sourceId);
        if(!copies.isEmpty()) {
            if (checkQuota && ((Number)copies.get(0).get("archived")).intValue()!=0) checkAgentLimit(user);
            String existing=(String)copies.get(0).get("agent_id");
            db.update("UPDATE ai_agent SET status=1,archived=0 WHERE agent_id=? AND owner_user_id=?",existing,user);
            return Map.of("agentId",existing);
        }
        if(checkQuota)checkAgentLimit(user);
        String target=id("wa"); var source=sources.get(0);
        db.update("INSERT INTO ai_agent(agent_id,agent_name,description,channel,strategy,status,owner_user_id,source_agent_id) VALUES(?,?,?,?,?,1,?,?)",
                target,source.get("agent_name"),source.get("description"),source.get("channel"),source.get("strategy"),user,sourceId);
        Map<String,String> copiesById=new HashMap<>();
        var steps=db.queryForList("SELECT * FROM ai_agent_flow_config WHERE agent_id=? AND status=1 ORDER BY sequence",sourceId);
        if (steps.isEmpty()) throw bad("公共模板没有可用执行步骤，请联系管理员");
        for(var step:steps) {
            String client=cloneResource("client",(String)step.get("client_id"),user,copiesById);
            db.update("INSERT INTO ai_agent_flow_config(agent_id,client_id,client_name,client_type,sequence,step_prompt,status,create_time) VALUES(?,?,?,?,?,?,1,NOW())",
                    target,client,step.get("client_name"),step.get("client_type"),step.get("sequence"),step.get("step_prompt"));
        }
        return Map.of("agentId",target);
    }
    private String cloneResource(String type,String source,String user,Map<String,String> copied) {
        String key=type+":"+source;
        if(copied.containsKey(key)) return copied.get(key);
        if (type.equals("tool_mcp")) {
            // The same user's public agents share their private connection, never another user's client.
            var reusable=db.queryForList("SELECT mcp_id FROM ai_client_tool_mcp WHERE owner_user_id=? AND (source_mcp_id=? OR mcp_id=?)",String.class,user,source,source);
            if (!reusable.isEmpty()) { copied.put(key,reusable.get(0)); return reusable.get(0); }
        }
        String target=id("w"+type.charAt(0)); copied.put(key,target);
        int rows;
        switch(type) {
            case "client" -> rows=db.update("INSERT INTO ai_client(client_id,client_name,description,status,owner_user_id) SELECT ?,client_name,description,status,? FROM ai_client WHERE client_id=?",target,user,source);
            case "model" -> { copyModel(source,target,user); rows=1; }
            case "prompt" -> rows=db.update("INSERT INTO ai_client_system_prompt(prompt_id,prompt_name,prompt_content,description,status,owner_user_id) SELECT ?,prompt_name,prompt_content,description,status,? FROM ai_client_system_prompt WHERE prompt_id=?",target,user,source);
            case "advisor" -> rows=db.update("INSERT INTO ai_client_advisor(advisor_id,advisor_name,advisor_type,order_num,ext_param,status,owner_user_id) SELECT ?,advisor_name,advisor_type,order_num,ext_param,status,? FROM ai_client_advisor WHERE advisor_id=?",target,user,source);
            case "tool_mcp" -> rows=db.update("""
                INSERT INTO ai_client_tool_mcp(mcp_id,mcp_name,transport_type,transport_config,request_timeout,status,owner_user_id,source_mcp_id)
                SELECT ?,mcp_name,transport_type,transport_config,request_timeout,status,?,mcp_id FROM ai_client_tool_mcp WHERE mcp_id=?
                """,target,user,source);
            default -> throw bad("公共模板包含不支持的资源类型");
        }
        if(rows!=1) throw bad("公共模板配置不完整，请联系管理员");
        if(type.equals("client") || type.equals("model")) {
            for(var r:db.queryForList("SELECT target_type,target_id FROM ai_client_config WHERE source_type=? AND source_id=? AND status=1",type,source)) {
                String targetType=(String)r.get("target_type"), targetId=(String)r.get("target_id");
                if(targetType.equals("tool_mcp") && db.queryForObject("SELECT COUNT(*) FROM ai_client_tool_mcp WHERE mcp_id=? AND is_public=1 AND status=1",Integer.class,targetId)==0)continue;
                if(targetType.equals("advisor")) {
                    String advisorType=db.queryForObject("SELECT advisor_type FROM ai_client_advisor WHERE advisor_id=?",String.class,targetId);
                    if(!WorkspaceTemplates.ADVISORS.contains(advisorType)) continue; // global semantic caches are not shareable
                }
                link(type,target,targetType,cloneResource(targetType,targetId,user,copied));
            }
        }
        return target;
    }
    public List<Map<String,Object>> mcps(String user) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(var row:db.queryForList("SELECT * FROM ai_client_tool_mcp WHERE owner_user_id=? ORDER BY create_time DESC",user)) result.add(mcpView(row));
        return result;
    }
    public Object mcpTools(String user,String id,boolean discover) {
        var row=ownedMcp(user,id);
        if (!access.ownsMcp(user,id)) throw bad("连接或平台来源已停用，请检查连接状态或联系管理员");
        try { return catalog.workspaceTools(user,id,discover); }
        catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                enabled(row.get("workspace_editable"))?"无法发现工具，请检查服务地址、令牌和协议后重试":"平台工具暂时无法连接，请重试或联系管理员"); }
    }
    @Transactional public Map<String,Object> saveMcp(String user,String id,JsonNode input) {
        lockUser(user);
        String name=text(input,"name",1,50),type=text(input,"transportType",1,30),url=text(input,"url",1,500);
        int status=input.has("status")?requiredInt(input,"status",0,1):1;
        var existing=id==null?null:ownedMcp(user,id);
        if(existing!=null && !enabled(existing.get("workspace_editable"))) throw forbidden("平台提供的工具连接不可修改");
        if(existing!=null) holdUpdate(affectedAgents(id));
        JsonNode old=existing==null?json.createObjectNode():parse(existing.get("transport_config"));
        boolean onlyDisabling=existing!=null && status==0 && url.equals(mcpView(existing).get("url"))
                && type.equals(mcpView(existing).get("transportType"));
        // A failed DNS lookup or removed allowlist entry must not prevent revocation.
        URI uri=onlyDisabling?URI.create(url):policy.validateRemote(type,url);
        String token=input.has("bearerToken")?optionalText(input,"bearerToken",400):old.path("headers").path("Authorization").asText("").replaceFirst("^Bearer ","");
        if(token.contains("\r") || token.contains("\n")) throw bad("令牌不能包含换行");
        ObjectNode cfg=json.createObjectNode().put("workspaceOwned",true);
        if(type.equals("sse")) { cfg.put("baseUri","https://"+uri.getHost()); cfg.put("sseEndpoint",uri.getRawPath().isEmpty()?"/sse":uri.getRawPath()); }
        else cfg.put("url",url);
        ObjectNode headers=cfg.putObject("headers"); if(!token.isEmpty()) headers.put("Authorization","Bearer "+token);
        if(cfg.toString().length()>1000) throw bad("工具连接配置过长");
        String dbType=type.equals("sse")?"sse":"streamable-http";
        if(existing==null) {
            if(db.queryForObject("SELECT COUNT(*) FROM ai_client_tool_mcp WHERE owner_user_id=? AND workspace_editable=1",Integer.class,user)>=20) throw bad("最多添加 20 个个人工具连接");
            id=id("wt"); db.update("INSERT INTO ai_client_tool_mcp(mcp_id,mcp_name,transport_type,transport_config,request_timeout,status,owner_user_id,workspace_editable) VALUES(?,?,?,?,30,?,?,1)",id,name,dbType,cfg.toString(),status,user);
        } else {
            int version=requiredInt(input,"version",1,Integer.MAX_VALUE);
            if(db.update("UPDATE ai_client_tool_mcp SET mcp_name=?,transport_type=?,transport_config=?,status=?,workspace_version=workspace_version+1,update_time=NOW() WHERE mcp_id=? AND owner_user_id=? AND workspace_version=?",name,dbType,cfg.toString(),status,id,user,version)!=1) throw conflict("连接已更新，请刷新后重试");
        }
        invalidateMcpAfterCommit(id);
        return mcpView(ownedMcp(user,id));
    }
    @Transactional public void deleteMcp(String user,String id) {
        lockUser(user); var mcp=ownedMcp(user,id);
        if(!enabled(mcp.get("workspace_editable"))) throw forbidden("平台工具不能删除");
        if(db.queryForObject("SELECT COUNT(*) FROM ai_client_config WHERE target_type='tool_mcp' AND target_id=?",Integer.class,id)>0)
            throw conflict("仍有 Agent 使用此工具，请先在 Agent 配置中移除");
        db.update("DELETE FROM ai_client_tool_mcp WHERE mcp_id=? AND owner_user_id=?",id,user);
        db.update("DELETE FROM ai_mcp_tool_catalog WHERE mcp_id=?",id);
        invalidateMcpAfterCommit(id);
    }
    private void invalidateMcpAfterCommit(String id) {
        Set<String> affected=affectedAgents(id);
        afterCommit(()-> armory.invalidateAgents(affected,() -> { catalog.invalidateMcp(id); registry.unregister(id); }));
    }
    private Set<String> affectedAgents(String id) {
        return new HashSet<>(db.queryForList("""
            SELECT DISTINCT f.agent_id FROM ai_agent_flow_config f JOIN ai_client_config c ON c.source_type='client' AND c.source_id=f.client_id
            WHERE (c.target_type='tool_mcp' AND c.target_id=?) OR (c.target_type='model' AND EXISTS
            (SELECT 1 FROM ai_client_config m WHERE m.source_type='model' AND m.source_id=c.target_id AND m.target_type='tool_mcp' AND m.target_id=?))
            """,String.class,id,id));
    }
    private void holdUpdate(Collection<String> ids) {
        if (!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Workspace updates require an active transaction");
        final WorkspaceExecutionGuard.Lease lease;
        try { lease=executions.beginUpdate(ids); }
        catch (WorkspaceExecutionGuard.BusyException e) { throw conflict(e.getMessage()); }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { lease.close(); }
        });
    }
    private Map<String,Object> mcpView(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>(); boolean editable=enabled(row.get("workspace_editable"));
        result.put("mcpId",row.get("mcp_id"));result.put("name",row.get("mcp_name"));result.put("status",enabled(row.get("status"))?1:0);
        result.put("version",row.get("workspace_version"));result.put("editable",editable);
        boolean usable=access.ownsMcp((String)row.get("owner_user_id"),(String)row.get("mcp_id"));
        result.put("usable",usable);
        result.put("platformPublic",access.publicMcpOrigins((String)row.get("owner_user_id")).containsKey(row.get("mcp_id")));
        result.put("unavailableReason",usable?"":"连接或平台来源已停用");
        String type=(String)row.get("transport_type"); result.put("transportType",type.startsWith("streamable")?"streamable":type);
        JsonNode cfg=parse(row.get("transport_config"));
        result.put("url",editable?(type.equals("sse")?cfg.path("baseUri").asText()+cfg.path("sseEndpoint").asText():cfg.path("url").asText()):"");
        result.put("hasSecret",editable&&!cfg.path("headers").path("Authorization").asText("").isBlank());
        return result;
    }
    private static boolean enabled(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue()==1 || "1".equals(value);
    }
    private Map<String,Object> ownedAgent(String user,String id,boolean lock) {
        var rows=db.queryForList("SELECT * FROM ai_agent WHERE agent_id=? AND owner_user_id=? AND archived=0"+(lock?" FOR UPDATE":""),id,user);
        if(rows.isEmpty()) throw notFound(); return rows.get(0);
    }
    private Map<String,Object> ownedMcp(String user,String id) {
        var rows=db.queryForList("SELECT * FROM ai_client_tool_mcp WHERE mcp_id=? AND owner_user_id=?",id,user);
        if(rows.isEmpty()) throw notFound(); return rows.get(0);
    }
    private Map<String,Object> summary(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("agentId",row.get("agent_id"));result.put("name",row.get("agent_name"));result.put("description",row.get("description"));
        result.put("strategy",String.valueOf(row.get("strategy")).replace("AgentExecuteStrategy",""));result.put("status",row.get("status"));
        result.put("version",row.get("workspace_version"));result.put("editable",row.get("workspace_config")!=null && row.get("source_agent_id")==null);
        return result;
    }
    private void lockUser(String user) {
        if(user==null || user.isBlank() || db.queryForList("SELECT user_id FROM admin_user WHERE user_id=? AND status=1 FOR UPDATE",String.class,user).size()!=1)
            throw forbidden("账户不可用");
    }
    private void checkAgentLimit(String user) {
        if(db.queryForObject("SELECT COUNT(*) FROM ai_agent WHERE owner_user_id=? AND archived=0 AND source_agent_id IS NULL",Integer.class,user)>=30) throw bad("最多保留 30 个个人 Agent");
    }
    private JsonNode parse(Object value) {
        try { return json.readTree(value==null?"{}":value.toString()); }
        catch(Exception e) { throw bad("配置内容无法读取，请联系管理员"); }
    }
    static String text(JsonNode node,String key,int min,int max) {
        if(!node.path(key).isTextual()) throw bad(key+" 必须为文本");
        String value=node.path(key).asText().trim();
        if(value.length()<min || value.length()>max) throw bad(key+" 长度须在 "+min+"–"+max+" 之间"); return value;
    }
    static String optionalText(JsonNode node,String key,int max) { return node.has(key)?text(node,key,0,max):""; }
    static int requiredInt(JsonNode node,String key,int min,int max) {
        if(!node.path(key).isIntegralNumber() || !node.path(key).canConvertToInt()) throw bad(key+" 必须为整数");
        int value=node.path(key).asInt(); if(value<min||value>max) throw bad(key+" 超出范围"); return value;
    }
    static String id(String prefix) { return prefix+"_"+UUID.randomUUID().toString().replace("-",""); }
    static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    static ResponseStatusException forbidden(String message) { return new ResponseStatusException(HttpStatus.FORBIDDEN,message); }
    static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"配置不存在或无权访问"); }
    static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    static void afterCommit(Runnable action) {
        if(!TransactionSynchronizationManager.isSynchronizationActive()) { action.run(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){action.run();}});
    }
}
