package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.service.IArmoryService;
import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.ai.domain.agent.service.security.WorkspaceMcpPolicy;
import cn.bugstack.ai.infrastructure.adapter.repository.WorkspaceAccessRepository;
import cn.bugstack.ai.trigger.workspace.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.util.*;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;

/**
 * Opt-in real MySQL contract tests. Only a loopback schema named tagent_workspace_isolation_test
 * is accepted. Fixtures contain synthetic secrets and are removed by exact owner IDs;
 * the test never migrates, truncates, or resets a shared application database.
 * Set TAGENT_TEST_DB_URL / TAGENT_TEST_DB_USER / TAGENT_TEST_DB_PASSWORD to enable.
 */
public class WorkspaceServiceIntegrationTest {
    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate db;
    private WorkspaceService service;
    private final ObjectMapper json = new ObjectMapper();
    private String prefix, alice, bob, admin, apiId, platformModel, privateModel, sourceAgent, sourceClient, sourcePrompt, sourceMcp, sourceAdvisor;

    @Before
    public void initializeIsolatedFixtures() throws Exception {
        String url = System.getenv("TAGENT_TEST_DB_URL");
        assumeTrue("Set TAGENT_TEST_DB_URL to run the isolated MySQL integration suite", url != null && !url.isBlank());
        assertTrue("Only the explicitly isolated loopback test schema is allowed",
                url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?/tagent_workspace_isolation_test(?:\\?.*)?"));
        var datasource = new DriverManagerDataSource(url,
                Objects.requireNonNullElse(System.getenv("TAGENT_TEST_DB_USER"), "root"),
                Objects.requireNonNullElse(System.getenv("TAGENT_TEST_DB_PASSWORD"), ""));
        try (var connection = datasource.getConnection()) {
            assertEquals("tagent_workspace_isolation_test", connection.getCatalog());
        }
        db = new JdbcTemplate(datasource);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> datasource);
        context.registerBean("mysqlJdbcTemplate", JdbcTemplate.class, () -> db);
        context.registerBean(WorkspaceAccessRepository.class, () -> new WorkspaceAccessRepository(db));
        context.registerBean(ObjectMapper.class, () -> json);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(datasource));
        context.registerBean(cn.bugstack.ai.domain.agent.service.workspace.WorkspaceExecutionGuard.class);
        // Already initialized mocks must not be processed for the real classes' @Resource fields.
        context.getBeanFactory().registerSingleton("armoryService", mock(IArmoryService.class));
        context.getBeanFactory().registerSingleton("mcpClientRegistry", mock(McpClientRegistry.class));
        context.getBeanFactory().registerSingleton("mcpToolCatalogService", mock(McpToolCatalogService.class));
        context.registerBean(WorkspaceMcpPolicy.class, () -> new WorkspaceMcpPolicy("mcp.example.test") {
            @Override protected InetAddress[] resolve(String host) throws Exception {
                return new InetAddress[]{InetAddress.getByAddress(new byte[]{1, 1, 1, 1})};
            }
        });
        context.register(Transactions.class, WorkspaceService.class);
        context.refresh();
        service = context.getBean(WorkspaceService.class);
        prefix = "workspace_test_" + UUID.randomUUID().toString().substring(0, 8);
        alice = prefix + "_alice"; bob = prefix + "_bob"; admin = prefix + "_admin";
        apiId = prefix + "_api"; platformModel = prefix + "_model"; privateModel = prefix + "_private_model";
        sourceAgent = prefix + "_public"; sourceClient = prefix + "_client"; sourcePrompt = prefix + "_prompt";
        sourceMcp = prefix + "_mcp"; sourceAdvisor = prefix + "_advisor";
        for (String owner : List.of(alice, bob, admin)) db.update(
                "INSERT INTO admin_user(user_id,username,password,status,role) VALUES(?,?,?,1,?)",
                owner, owner, "synthetic-hash-not-a-real-password", owner.equals(admin) ? "ADMIN" : "USER");
        db.update("""
                INSERT INTO ai_client_api(api_id,base_url,api_key,completions_path,embeddings_path,status,owner_user_id)
                VALUES(?,'https://model.example.test','SYNTHETIC_PLATFORM_SECRET','/v1/chat/completions','/v1/embeddings',1,?)
                """, apiId, admin);
        db.update("""
                INSERT INTO ai_client_model(model_id,api_id,model_name,model_type,model_usage,tier,status,owner_user_id,platform_enabled)
                VALUES(?,?,'fixture-chat','openai','chat','medium',1,?,1)
                """, platformModel, apiId, admin);
        db.update("""
                INSERT INTO ai_client_model(model_id,api_id,model_name,model_type,model_usage,tier,status,owner_user_id,platform_enabled)
                VALUES(?,?,'private-chat','openai','chat','medium',1,?,0)
                """, privateModel, apiId, bob);
        db.update("""
                INSERT INTO ai_agent(agent_id,agent_name,description,channel,strategy,status,owner_user_id,is_public)
                VALUES(?,'Public fixture','Synthetic public agent','agent','fixedAgentExecuteStrategy',1,?,1)
                """, sourceAgent, admin);
        db.update("INSERT INTO ai_client(client_id,client_name,status,owner_user_id) VALUES(?,'Fixture client',1,?)", sourceClient, admin);
        db.update("INSERT INTO ai_client_system_prompt(prompt_id,prompt_name,prompt_content,status,owner_user_id) VALUES(?,'Fixture prompt','ADMIN_PRIVATE_PROMPT',1,?)", sourcePrompt, admin);
        db.update("INSERT INTO ai_client_advisor(advisor_id,advisor_name,advisor_type,ext_param,status,owner_user_id) VALUES(?,'Fixture memory','ChatMemory','{\"maxMessages\":20}',1,?)", sourceAdvisor, admin);
        db.update("""
                INSERT INTO ai_client_tool_mcp(mcp_id,mcp_name,transport_type,transport_config,request_timeout,status,owner_user_id,workspace_editable,is_public)
                VALUES(?,'Fixture platform tool','sse',?,30,1,?,0,1)
                """, sourceMcp, "{\"baseUri\":\"https://mcp.example.test\",\"sseEndpoint\":\"/sse\",\"headers\":{\"Authorization\":\"Bearer SYNTHETIC_MCP_SECRET\"}}", admin);
        relation("client", sourceClient, "model", platformModel);
        relation("client", sourceClient, "prompt", sourcePrompt);
        relation("client", sourceClient, "advisor", sourceAdvisor);
        relation("model", platformModel, "tool_mcp", sourceMcp);
        db.update("""
                INSERT INTO ai_agent_flow_config(agent_id,client_id,client_name,client_type,sequence,step_prompt,status)
                VALUES(?,?,'Fixture answer','DEFAULT',1,'%s',1)
                """, sourceAgent, sourceClient);
        for (String owner : List.of(alice, bob)) db.update(
                "INSERT INTO ai_client_rag_order(rag_id,user_id,rag_name,knowledge_tag,status) VALUES(?,?,'Fixture knowledge',?,1)",
                owner + "_rag", owner, owner + "_knowledge");
    }

    @After
    public void removeOnlyExactTestOwners() {
        try {
            if (db != null && prefix != null) {
                Object[] owners = {alice, bob, admin};
                // Relations have no owner column, so derive their source IDs from exact fixture owners.
                db.update("""
                        DELETE FROM ai_client_config WHERE
                        (source_type='client' AND source_id IN (SELECT client_id FROM ai_client WHERE owner_user_id IN (?,?,?))) OR
                        (source_type='model' AND source_id IN (SELECT model_id FROM ai_client_model WHERE owner_user_id IN (?,?,?)))
                        """, alice, bob, admin, alice, bob, admin);
                db.update("DELETE FROM ai_agent_flow_config WHERE agent_id IN (SELECT agent_id FROM ai_agent WHERE owner_user_id IN (?,?,?))", owners);
                db.update("DELETE FROM ai_mcp_tool_catalog WHERE mcp_id IN (SELECT mcp_id FROM ai_client_tool_mcp WHERE owner_user_id IN (?,?,?))", owners);
                for (String table : List.of("ai_agent", "ai_client", "ai_client_model", "ai_client_system_prompt", "ai_client_advisor", "ai_client_tool_mcp", "ai_client_api"))
                    db.update("DELETE FROM " + table + " WHERE owner_user_id IN (?,?,?)", owners);
                db.update("DELETE FROM ai_client_rag_order WHERE user_id IN (?,?,?)", owners);
                db.update("DELETE FROM admin_user WHERE user_id IN (?,?,?)", owners);
            }
        } finally {
            if (context != null) context.close();
        }
    }

    @Test
    public void privateCrudAndOptimisticVersionDoNotCrossOwners() {
        var input = agentConfig("fixed");
        input.put("ownerUserId", bob).put("userId", bob).put("promptId", sourcePrompt).put("apiKey", "ATTACKER_SUPPLIED_KEY");
        var saved = service.saveAgent(alice, null, input);
        String id = (String) saved.get("agentId");
        assertEquals(alice, db.queryForObject("SELECT owner_user_id FROM ai_agent WHERE agent_id=?", String.class, id));
        assertEquals(1, service.agents(alice).size());
        assertTrue(service.agents(bob).isEmpty());
        assertFalse(json.valueToTree(saved).toString().contains("ADMIN_PRIVATE_PROMPT"));
        assertFalse(json.valueToTree(saved).toString().contains("SYNTHETIC_PLATFORM_SECRET"));
        assertFalse(json.valueToTree(saved).has("apiKey"));
        expectStatus(HttpStatus.NOT_FOUND, () -> service.agent(bob, id));
        expectStatus(HttpStatus.NOT_FOUND, () -> service.saveAgent(bob, id, withVersion(agentConfig("fixed"), 1)));
        expectStatus(HttpStatus.NOT_FOUND, () -> service.archiveAgent(bob, id));
        var update = withVersion(agentConfig("fixed"), 1).put("name", "Updated own agent");
        var updated = service.saveAgent(alice, id, update);
        assertEquals(2, ((Number) updated.get("version")).intValue());
        expectStatus(HttpStatus.CONFLICT, () -> service.saveAgent(alice, id, update));
        assertEquals("Updated own agent", service.agent(alice, id).get("name"));
        assertEquals("ADMIN_PRIVATE_PROMPT", db.queryForObject("SELECT prompt_content FROM ai_client_system_prompt WHERE prompt_id=?", String.class, sourcePrompt));
        service.archiveAgent(alice, id);
        assertTrue(service.agents(alice).isEmpty());
        expectStatus(HttpStatus.NOT_FOUND, () -> service.agent(alice, id));
    }

    @Test
    public void toolSecretsAreWriteOnlyAndVersionsAreEnforced() {
        var created = service.saveMcp(alice, null, mcpConfig().put("bearerToken", "FIRST_SYNTHETIC_TOKEN"));
        String id = (String) created.get("mcpId");
        assertEquals(true, created.get("hasSecret"));
        assertFalse(json.valueToTree(created).toString().contains("FIRST_SYNTHETIC_TOKEN"));
        assertFalse(json.valueToTree(service.mcps(alice)).toString().contains("FIRST_SYNTHETIC_TOKEN"));
        assertTrue(service.mcps(bob).isEmpty());
        expectStatus(HttpStatus.NOT_FOUND, () -> service.saveMcp(bob, id, withVersion(mcpConfig(), 1)));
        expectStatus(HttpStatus.NOT_FOUND, () -> service.deleteMcp(bob, id));
        service.saveMcp(alice, id, withVersion(mcpConfig(), 1));
        assertTrue(transport(id).contains("FIRST_SYNTHETIC_TOKEN"));
        expectStatus(HttpStatus.CONFLICT, () -> service.saveMcp(alice, id, withVersion(mcpConfig(), 1)));
        service.saveMcp(alice, id, withVersion(mcpConfig(), 2).put("bearerToken", "SECOND_SYNTHETIC_TOKEN"));
        assertTrue(transport(id).contains("SECOND_SYNTHETIC_TOKEN"));
        service.saveMcp(alice, id, withVersion(mcpConfig(), 3).put("bearerToken", ""));
        assertFalse(transport(id).contains("Authorization"));
        assertEquals(false, service.mcps(alice).get(0).get("hasSecret"));
        service.deleteMcp(alice, id);
        assertTrue(service.mcps(alice).isEmpty());
    }

    @Test
    public void forgedModelsToolsAndKnowledgeAreRejectedWithoutCreatingGraphs() {
        long before = countOwned("ai_agent", alice);
        expectStatus(HttpStatus.BAD_REQUEST, () -> service.saveAgent(alice, null, agentConfig("fixed").put("modelId", privateModel)));
        var bobMcp = service.saveMcp(bob, null, mcpConfig());
        ObjectNode foreignTool = agentConfig("fixed");
        foreignTool.withArray("mcpIds").add((String) bobMcp.get("mcpId"));
        expectStatus(HttpStatus.BAD_REQUEST, () -> service.saveAgent(alice, null, foreignTool));
        var rag = agentConfig("fixed");
        rag.withArray("advisors").addObject().put("type", "RagAnswer").put("knowledgeTag", bob + "_knowledge").put("topK", 4);
        expectStatus(HttpStatus.BAD_REQUEST, () -> service.saveAgent(alice, null, rag));
        var unsafeAdvisor = agentConfig("fixed");
        unsafeAdvisor.withArray("advisors").addObject().put("type", "SemanticCache");
        expectStatus(HttpStatus.BAD_REQUEST, () -> service.saveAgent(alice, null, unsafeAdvisor));
        assertEquals(before, countOwned("ai_agent", alice));
        assertEquals(0, countOwned("ai_client", alice));
        assertEquals(0, countOwned("ai_client_system_prompt", alice));
        String visible = json.valueToTree(service.options(alice)).toString();
        assertTrue(visible.contains(alice + "_knowledge"));
        assertFalse(visible.contains(bob + "_knowledge"));
        assertFalse(visible.contains(privateModel));
        assertFalse(visible.contains("SYNTHETIC_PLATFORM_SECRET"));
        assertFalse(visible.contains("ADMIN_PRIVATE_PROMPT"));
    }

    @Test
    public void newModelGraphDoesNotInheritPlatformToolsAndOwnToolsCannotBeDeletedInUse() {
        var ownMcp = service.saveMcp(alice, null, mcpConfig());
        String mcpId = (String) ownMcp.get("mcpId");
        var plain = service.saveAgent(alice, null, agentConfig("fixed"));
        String plainModel = agentModel((String) plain.get("agentId"));
        assertNotEquals(platformModel, plainModel);
        assertEquals(0L, scalar("SELECT COUNT(*) FROM ai_client_config WHERE source_type='model' AND source_id=? AND target_type='tool_mcp'", plainModel));
        var config = agentConfig("auto"); config.withArray("mcpIds").add(mcpId);
        config.withArray("advisors").addObject().put("type", "RagAnswer").put("knowledgeTag", alice + "_knowledge").put("topK", 7);
        String agentId = (String) service.saveAgent(alice, null, config).get("agentId");
        assertEquals(4L, scalar("SELECT COUNT(*) FROM ai_agent_flow_config WHERE agent_id=?", agentId));
        assertEquals(List.of(mcpId), db.queryForList("SELECT target_id FROM ai_client_config WHERE source_type='model' AND source_id=? AND target_type='tool_mcp'", String.class, db.queryForObject("SELECT c.target_id FROM ai_agent_flow_config f JOIN ai_client_config c ON c.source_id=f.client_id AND c.source_type='client' AND c.target_type='model' WHERE f.agent_id=? AND f.client_type='PRECISION_EXECUTOR_CLIENT'",String.class,agentId)));
        expectStatus(HttpStatus.CONFLICT, () -> service.deleteMcp(alice, mcpId));
        config.withArray("mcpIds").removeAll(); config.put("version", 1);
        service.saveAgent(alice, agentId, config);
        service.deleteMcp(alice, mcpId);
        assertTrue(service.mcps(alice).isEmpty());
        assertEquals(1L, scalar("SELECT COUNT(*) FROM ai_client_config WHERE source_type='model' AND source_id=? AND target_id=?", platformModel, sourceMcp));
    }

    @Test
    public void publicCopiesHaveIndependentGraphsAndProtectedSecretConnections() {
        String first = (String) service.usePublic(alice, sourceAgent).get("agentId");
        String second = (String) service.usePublic(bob, sourceAgent).get("agentId");
        assertNotEquals(first, second);
        assertEquals(first, service.usePublic(alice, sourceAgent).get("agentId"));
        Set<String> firstGraph = ownedResourceIds(alice), secondGraph = ownedResourceIds(bob);
        assertFalse(firstGraph.isEmpty()); assertFalse(secondGraph.isEmpty());
        assertTrue(Collections.disjoint(firstGraph, secondGraph));
        assertTrue(Collections.disjoint(firstGraph, Set.of(sourceClient, sourcePrompt, platformModel, sourceMcp, sourceAdvisor)));
        assertEquals(1, service.mcps(alice).size());
        var connection = service.mcps(alice).get(0);
        assertEquals(false, connection.get("editable")); assertEquals("", connection.get("url"));
        assertFalse(json.valueToTree(connection).toString().contains("SYNTHETIC_MCP_SECRET"));
        String clonedMcp = (String) connection.get("mcpId");
        assertTrue(transport(clonedMcp).contains("SYNTHETIC_MCP_SECRET"));
        assertEquals(false, service.agent(alice, first).get("editable"));
        assertFalse(service.agent(alice, first).containsKey("systemPrompt"));
        expectStatus(HttpStatus.FORBIDDEN, () -> service.saveAgent(alice, first, withVersion(agentConfig("fixed"), 1)));
        expectStatus(HttpStatus.FORBIDDEN, () -> service.saveMcp(alice, clonedMcp, withVersion(mcpConfig(), 1)));
        expectStatus(HttpStatus.FORBIDDEN, () -> service.deleteMcp(alice, clonedMcp));
        var custom = agentConfig("fixed"); custom.withArray("mcpIds").add(clonedMcp);
        String customId=(String)service.saveAgent(alice, null, custom).get("agentId");
        assertEquals(Set.of(clonedMcp),new WorkspaceAccessRepository(db).agentMcpIds(alice,customId));
        assertEquals(sourceMcp, db.queryForObject("SELECT source_mcp_id FROM ai_client_tool_mcp WHERE mcp_id=?", String.class, clonedMcp));
    }

    @Test
    public void managedToolsAreDiscoverableAndNodeSelectableWithoutSharingCredentialsOrClients() {
        service.usePublic(alice,sourceAgent); service.usePublic(bob,sourceAgent);
        var m=service.mcps(alice).get(0);
        String id=(String)m.get("mcpId"), bobs=(String)service.mcps(bob).get(0).get("mcpId");
        assertNotEquals(id,bobs);assertEquals(true,m.get("usable"));assertEquals(1,m.get("status"));
        assertEquals(false,m.get("editable"));assertEquals("",m.get("url"));
        var catalog=context.getBean(McpToolCatalogService.class);
        var tools=List.of(Map.of("name","web-search","description","Public search"));
        when(catalog.workspaceTools(alice,id,true)).thenReturn(tools);
        assertEquals(tools,service.mcpTools(alice,id,true));
        verify(catalog).workspaceTools(alice,id,true);
        expectStatus(HttpStatus.NOT_FOUND,()->service.mcpTools(bob,id,true));
        expectStatus(HttpStatus.NOT_FOUND,()->service.mcpTools(alice,sourceMcp,true));
        var config=cn.bugstack.ai.trigger.workspace.WorkspaceTemplates.upgrade(agentConfig("fixed"),json);
        ((ObjectNode)config.path("nodes").get(0)).withArray("mcpBindings").addObject()
                .put("mcpId",id).put("allTools",false).putArray("toolNames").add("web-search");
        String agent=(String)service.saveAgent(alice,null,config).get("agentId");
        var stored=json.valueToTree(service.agent(alice,agent));
        assertEquals("web-search",stored.path("nodes").get(0).path("mcpBindings").get(0).path("toolNames").get(0).asText());
        assertFalse(stored.toString().contains("SYNTHETIC_MCP_SECRET"));
        assertEquals(Set.of(id),new WorkspaceAccessRepository(db).agentMcpIds(alice,agent));
        var invalid=agentConfig("fixed");invalid.withArray("mcpIds").add(bobs);
        expectStatus(HttpStatus.BAD_REQUEST,()->service.saveAgent(alice,null,invalid));
        // The original owner can also select their own managed connection, without making it editable.
        var adminConfig=agentConfig("fixed");adminConfig.withArray("mcpIds").add(sourceMcp);
        service.saveAgent(admin,null,adminConfig);
    }

    @Test
    public void revokedManagedToolsCannotBeDiscoveredOrSavedEvenWhenTheirCopiesAreEnabled() {
        service.usePublic(alice,sourceAgent);
        String id=(String)service.mcps(alice).get(0).get("mcpId");
        var config=agentConfig("fixed");config.withArray("mcpIds").add(id);
        String agent=(String)service.saveAgent(alice,null,config).get("agentId");
        db.update("UPDATE ai_client_tool_mcp SET status=0 WHERE mcp_id=?",sourceMcp);
        var view=service.mcps(alice).get(0);
        assertEquals(1,view.get("status"));assertEquals(false,view.get("usable"));
        assertFalse(((String)view.get("unavailableReason")).isBlank());
        expectStatus(HttpStatus.BAD_REQUEST,()->service.mcpTools(alice,id,true));
        expectStatus(HttpStatus.BAD_REQUEST,()->service.saveAgent(alice,null,config));
        assertTrue(new WorkspaceAccessRepository(db).agentMcpIds(alice,agent).isEmpty());
        verify(context.getBean(McpToolCatalogService.class),never()).workspaceTools(anyString(),anyString(),anyBoolean());
        db.update("UPDATE ai_client_tool_mcp SET status=1,is_public=0 WHERE mcp_id=?",sourceMcp);
        assertFalse(new WorkspaceAccessRepository(db).ownsMcp(alice,id));
        expectStatus(HttpStatus.BAD_REQUEST,()->service.mcpTools(alice,id,true));
    }

    @Test public void loginProvisioningIsIdempotentKeepsPrivateToolsOutAndDoesNotConnect() {
        String privateTool=prefix+"_private_tool";
        db.update("INSERT INTO ai_client_tool_mcp(mcp_id,mcp_name,transport_type,transport_config,status,owner_user_id,is_public) VALUES(?,'Private filesystem','stdio','{}',1,?,0)",privateTool,admin);
        relation("model",platformModel,"tool_mcp",privateTool);
        service.provisionPublicResources(alice);service.provisionPublicResources(alice);
        service.provisionPublicResources(bob);
        assertEquals(1,service.agents(alice).size());assertEquals(1,service.mcps(alice).size());
        var access=new WorkspaceAccessRepository(db);
        assertEquals(Set.of(sourceMcp),new HashSet<>(access.publicMcpOrigins(alice).values()));
        assertTrue(Collections.disjoint(access.publicMcpOrigins(alice).keySet(),access.publicMcpOrigins(bob).keySet()));
        assertEquals(0L,scalar("SELECT COUNT(*) FROM ai_client_tool_mcp WHERE owner_user_id=? AND source_mcp_id=?",alice,privateTool));
        String agent=(String)service.agents(alice).get(0).get("agentId");
        service.archiveAgent(alice,agent);service.provisionPublicResources(alice);
        assertTrue(service.agents(alice).isEmpty()); // Login does not undo an intentional archive.
        org.mockito.Mockito.verifyNoInteractions(context.getBean(McpToolCatalogService.class),context.getBean(McpClientRegistry.class));
    }

    @Test public void nodePublicPoolIsServerDerivedAndExplicitToolSelectionStillNarrowsIt() {
        service.provisionPublicResources(alice);
        String tool=(String)service.mcps(alice).get(0).get("mcpId");
        var input=cn.bugstack.ai.trigger.workspace.WorkspaceTemplates.upgrade(agentConfig("fixed"),json);
        var n=(ObjectNode)input.path("nodes").get(0);n.put("usePublicTools",true);n.putArray("publicMcpIds").add("foreign_tool");
        String agent=(String)service.saveAgent(alice,null,input).get("agentId");
        var policy=cn.bugstack.ai.domain.agent.model.valobj.WorkspaceNodePolicy.fromCapabilities(db.queryForObject("SELECT capabilities_json FROM ai_client_model WHERE model_id=?",String.class,agentModel(agent)));
        assertTrue(policy.allows(tool,"web-search"));assertFalse(policy.allows("foreign_tool","web-search"));
        assertEquals(Set.of(tool),new WorkspaceAccessRepository(db).agentMcpIds(alice,agent));
        n.withArray("mcpBindings").addObject().put("mcpId",tool).put("allTools",false).putArray("toolNames").add("web-search");
        input.put("version",1);service.saveAgent(alice,agent,input);
        policy=cn.bugstack.ai.domain.agent.model.valobj.WorkspaceNodePolicy.fromCapabilities(db.queryForObject("SELECT capabilities_json FROM ai_client_model WHERE model_id=?",String.class,agentModel(agent)));
        assertTrue(policy.allows(tool,"web-search"));assertFalse(policy.allows(tool,"delete_files"));
    }

    @Test public void connectionBindingRoundTripsAsLazyAndKeepsAuthorizationDependency() {
        service.provisionPublicResources(alice);
        String tool=(String)service.mcps(alice).get(0).get("mcpId");
        var input=cn.bugstack.ai.trigger.workspace.WorkspaceTemplates.upgrade(agentConfig("fixed"),json);
        var node=(ObjectNode)input.path("nodes").get(0);node.put("usePublicTools",false);
        node.withArray("mcpBindings").addObject().put("mcpId",tool).put("allTools",true)
                .put("loadMode","ON_DEMAND").putArray("toolNames");
        String agent=(String)service.saveAgent(alice,null,input).get("agentId");
        var stored=json.valueToTree(service.agent(alice,agent));
        var policy=cn.bugstack.ai.domain.agent.model.valobj.WorkspaceNodePolicy.fromCapabilities(
                db.queryForObject("SELECT capabilities_json FROM ai_client_model WHERE model_id=?",String.class,agentModel(agent)));
        assertTrue(policy.allows(tool,"any_tool_in_this_connection"));assertFalse(policy.eager(tool));
        assertEquals(Set.of(tool),new WorkspaceAccessRepository(db).agentMcpIds(alice,agent));
        assertEquals(List.of(tool),db.queryForList("SELECT target_id FROM ai_client_config WHERE source_type='model' AND source_id=? AND target_type='tool_mcp'",String.class,agentModel(agent)));
        assertTrue(stored.toString().contains("ON_DEMAND"));
        org.mockito.Mockito.verifyNoInteractions(context.getBean(McpToolCatalogService.class),context.getBean(McpClientRegistry.class));
        ((ObjectNode)node.path("mcpBindings").get(0)).put("loadMode","EAGER_UNKNOWN");
        expectStatus(HttpStatus.BAD_REQUEST,()->service.saveAgent(alice,null,input));
    }

    @Test
    public void incompletePublicTemplateRollsBackEveryClonedRow() {
        relation("client", sourceClient, "prompt", prefix + "_missing_prompt");
        expectStatus(HttpStatus.BAD_REQUEST, () -> service.usePublic(alice, sourceAgent));
        for (String table : List.of("ai_agent", "ai_client", "ai_client_model", "ai_client_system_prompt", "ai_client_advisor", "ai_client_tool_mcp"))
            assertEquals(table + " must roll back", 0, countOwned(table, alice));
        assertTrue(service.agents(alice).isEmpty());
    }

    @Test
    public void multiplePublicAgentsReuseOnlyTheirOwnUsersCopyOfTheSameTool() {
        String otherPublic = prefix + "_other_public";
        db.update("""
                INSERT INTO ai_agent(agent_id,agent_name,description,channel,strategy,status,owner_user_id,is_public)
                VALUES(?,'Other public fixture','Synthetic public agent','agent','fixedAgentExecuteStrategy',1,?,1)
                """, otherPublic, admin);
        db.update("""
                INSERT INTO ai_agent_flow_config(agent_id,client_id,client_name,client_type,sequence,step_prompt,status)
                VALUES(?,?,'Other fixture answer','DEFAULT',1,'%s',1)
                """, otherPublic, sourceClient);
        String first = (String) service.usePublic(alice, sourceAgent).get("agentId");
        String second = (String) service.usePublic(alice, otherPublic).get("agentId");
        String bobs = (String) service.usePublic(bob, otherPublic).get("agentId");
        assertNotEquals(first, second);
        assertNotEquals(agentModel(first), agentModel(second));
        assertEquals(1, service.mcps(alice).size());
        WorkspaceAccessRepository access = new WorkspaceAccessRepository(db);
        assertEquals(access.agentMcpIds(alice, first), access.agentMcpIds(alice, second));
        assertFalse(access.agentMcpIds(alice, first).isEmpty());
        assertTrue(Collections.disjoint(access.agentMcpIds(alice, first), access.agentMcpIds(bob, bobs)));
    }

    @Test
    public void archivedPublicCopyCanOnlyBeRestoredWithinTheAgentLimit() {
        String copy = (String) service.usePublic(alice, sourceAgent).get("agentId");
        service.archiveAgent(alice, copy);
        for (int i = 0; i < 30; i++) {
            db.update("""
                    INSERT INTO ai_agent(agent_id,agent_name,description,channel,strategy,status,owner_user_id)
                    VALUES(?,'Quota fixture','Synthetic quota fixture','agent','fixedAgentExecuteStrategy',1,?)
                    """, prefix + "_quota_" + i, alice);
        }
        expectStatus(HttpStatus.BAD_REQUEST, () -> service.usePublic(alice, sourceAgent));
        assertEquals(1L, scalar("SELECT archived FROM ai_agent WHERE agent_id=?", copy));
        assertEquals(30L, scalar("SELECT COUNT(*) FROM ai_agent WHERE owner_user_id=? AND archived=0", alice));
        service.archiveAgent(alice, prefix + "_quota_0");
        assertEquals(copy, service.usePublic(alice, sourceAgent).get("agentId"));
        assertEquals(30L, scalar("SELECT COUNT(*) FROM ai_agent WHERE owner_user_id=? AND archived=0", alice));
        // An already active copy is idempotent even when the account is at its limit.
        assertEquals(copy, service.usePublic(alice, sourceAgent).get("agentId"));
    }

    @Test
    public void sourceModelAgentAndToolRevocationApplyToExistingCopies() {
        WorkspaceAccessRepository access = new WorkspaceAccessRepository(db);
        String copy = (String) service.usePublic(alice, sourceAgent).get("agentId");
        String privateAgent = (String) service.saveAgent(alice, null, agentConfig("fixed")).get("agentId");
        String tool = (String) service.mcps(alice).get(0).get("mcpId");
        assertEquals(platformModel, db.queryForObject("SELECT source_model_id FROM ai_client_model WHERE model_id=?", String.class, agentModel(copy)));
        assertEquals(platformModel, db.queryForObject("SELECT source_model_id FROM ai_client_model WHERE model_id=?", String.class, agentModel(privateAgent)));
        assertTrue(access.ownsAgent(alice, copy));
        assertTrue(access.ownsAgent(alice, privateAgent));
        assertFalse(access.ownsAgent(bob, copy));
        assertEquals(Set.of(tool), access.agentMcpIds(alice, copy));
        assertTrue(access.agentMcpIds(bob, copy).isEmpty());

        db.update("UPDATE ai_client_model SET status=0 WHERE model_id=?", platformModel);
        assertFalse(access.ownsAgent(alice, copy));
        assertFalse(access.ownsAgent(alice, privateAgent));
        db.update("UPDATE ai_client_model SET status=1,platform_enabled=0 WHERE model_id=?", platformModel);
        assertFalse(access.ownsAgent(alice, copy));
        assertFalse(access.ownsAgent(alice, privateAgent));
        db.update("UPDATE ai_client_model SET platform_enabled=1 WHERE model_id=?", platformModel);
        assertTrue(access.ownsAgent(alice, copy));

        db.update("UPDATE ai_client_api SET status=0 WHERE api_id=?", apiId);
        assertFalse(access.ownsAgent(alice, copy));
        db.update("UPDATE ai_client_api SET status=1 WHERE api_id=?", apiId);
        db.update("UPDATE ai_agent SET is_public=0 WHERE agent_id=?", sourceAgent);
        assertFalse(access.ownsAgent(alice, copy));
        assertTrue(access.ownsAgent(alice, privateAgent));
        db.update("UPDATE ai_agent SET is_public=1,status=0 WHERE agent_id=?", sourceAgent);
        assertFalse(access.ownsAgent(alice, copy));
        db.update("UPDATE ai_agent SET status=1,archived=1 WHERE agent_id=?", sourceAgent);
        assertFalse(access.ownsAgent(alice, copy));
        db.update("UPDATE ai_agent SET archived=0 WHERE agent_id=?", sourceAgent);
        assertTrue(access.ownsAgent(alice, copy));

        db.update("UPDATE ai_client_tool_mcp SET status=0 WHERE mcp_id=?", sourceMcp);
        assertFalse(access.ownsMcp(alice, tool));
        assertTrue(access.agentMcpIds(alice, copy).isEmpty());
        db.update("UPDATE ai_client_tool_mcp SET status=1 WHERE mcp_id=?", sourceMcp);
        assertTrue(access.ownsMcp(alice, tool));
        assertEquals(Set.of(tool), access.agentMcpIds(alice, copy));
    }

    @Test
    public void graphAndToolEditsWaitForRunsAndRollbackReleasesEditGuard() {
        var guard=context.getBean(cn.bugstack.ai.domain.agent.service.workspace.WorkspaceExecutionGuard.class);
        String tool=(String)service.saveMcp(alice,null,mcpConfig()).get("mcpId");
        var config=agentConfig("auto"); config.withArray("mcpIds").add(tool);
        String agent=(String)service.saveAgent(alice,null,config).get("agentId");
        try(var running=guard.startRun(agent)) {
            expectStatus(HttpStatus.CONFLICT,()->service.saveAgent(alice,agent,withVersion(config.deepCopy(),1)));
            expectStatus(HttpStatus.CONFLICT,()->service.archiveAgent(alice,agent));
            expectStatus(HttpStatus.CONFLICT,()->service.saveMcp(alice,tool,withVersion(mcpConfig(),1).put("status",0)));
        }
        service.saveAgent(alice,agent,withVersion(config.deepCopy(),1));
        expectStatus(HttpStatus.CONFLICT,()->service.saveAgent(alice,agent,withVersion(config.deepCopy(),1)));
        // Both commit and failed-version rollback must release the mutation lease.
        try(var running=guard.startRun(agent)) { assertEquals(2,((Number)service.agent(alice,agent).get("version")).intValue()); }
        service.archiveAgent(alice,agent);
    }

    private ObjectNode agentConfig(String strategy) {
        ObjectNode input = json.createObjectNode().put("name", "Private test agent").put("description", "Synthetic fixture")
                .put("strategy", strategy).put("modelId", platformModel).put("systemPrompt", "USER_PRIVATE_PROMPT").put("status", 1);
        input.putArray("mcpIds"); input.putArray("advisors"); return input;
    }

    @Test public void nodesPersistSeparatePromptsAdvisorsAndToolCeilingsAndRoundTrip() throws Exception {
        String mcp=(String)service.saveMcp(alice,null,mcpConfig()).get("mcpId");
        var config=cn.bugstack.ai.trigger.workspace.WorkspaceTemplates.upgrade(agentConfig("flow"),json);
        var nodes=config.withArray("nodes");
        for(int i=0;i<nodes.size();i++) ((ObjectNode)nodes.get(i)).put("systemPrompt","BUSINESS_NODE_"+i);
        ((ObjectNode)nodes.get(0)).withArray("advisors").addObject().put("type","LongTermMemory").put("topK",3);
        ((ObjectNode)nodes.get(2)).put("taskPrompt","ONLY_TASK_INSTRUCTION").put("outputRequirement","ONLY_FINAL_OUTPUT")
                .withArray("mcpBindings").addObject().put("mcpId",mcp).put("allTools",false).putArray("toolNames").add("search_docs");
        String id=(String)service.saveAgent(alice,null,config).get("agentId");
        var rows=db.queryForList("""
            SELECT f.client_id,f.sequence,m.model_id,m.capabilities_json,p.prompt_content
            FROM ai_agent_flow_config f
            JOIN ai_client_config cm ON cm.source_type='client' AND cm.source_id=f.client_id AND cm.target_type='model'
            JOIN ai_client_model m ON m.model_id=cm.target_id
            JOIN ai_client_config cp ON cp.source_type='client' AND cp.source_id=f.client_id AND cp.target_type='prompt'
            JOIN ai_client_system_prompt p ON p.prompt_id=cp.target_id WHERE f.agent_id=? ORDER BY f.sequence
            """,id);
        assertEquals(3,rows.size());assertEquals(3,rows.stream().map(r->r.get("model_id")).distinct().count());
        for(int i=0;i<3;i++) {
            var row=rows.get(i);String content=(String)row.get("prompt_content");
            assertTrue(content.contains("BUSINESS_NODE_"+i));assertFalse(content.contains("BUSINESS_NODE_"+((i+1)%3)));
            assertEquals(i==0?1:0,scalar("SELECT COUNT(*) FROM ai_client_config WHERE source_type='client' AND source_id=? AND target_type='advisor'",row.get("client_id")));
            var policy=cn.bugstack.ai.domain.agent.model.valobj.WorkspaceNodePolicy.fromCapabilities((String)row.get("capabilities_json"));
            assertEquals(id,policy.agentId());assertEquals(row.get("client_id"),policy.clientId());
            assertEquals(i==2,policy.allows(mcp,"search_docs"));assertFalse(policy.allows(mcp,"delete_docs"));
            if(i==2){assertEquals("ONLY_TASK_INSTRUCTION",policy.taskPrompt());assertTrue(content.contains("ONLY_FINAL_OUTPUT"));assertFalse(content.contains("ONLY_TASK_INSTRUCTION"));}
        }
        var saved=json.valueToTree(service.agent(alice,id));assertEquals(config.path("nodes"),saved.path("nodes"));
        service.saveAgent(alice,id,withVersion((ObjectNode)saved,1));
        assertEquals(3,countOwned("ai_client",alice));assertEquals(3,countOwned("ai_client_model",alice));
        assertEquals(1,countOwned("ai_client_advisor",alice));
        expectStatus(HttpStatus.NOT_FOUND,()->service.mcpTools(bob,mcp,true));
    }

    @Test public void invalidRoleOrderAndForeignNodeResourcesFailAtomically() {
        var wrong=cn.bugstack.ai.trigger.workspace.WorkspaceTemplates.upgrade(agentConfig("auto"),json);
        ((ObjectNode)wrong.path("nodes").get(0)).put("role","EXECUTOR_CLIENT");
        expectStatus(HttpStatus.BAD_REQUEST,()->service.saveAgent(alice,null,wrong));
        var foreign=cn.bugstack.ai.trigger.workspace.WorkspaceTemplates.upgrade(agentConfig("fixed"),json);
        ((ObjectNode)foreign.path("nodes").get(0)).put("modelId",privateModel);
        expectStatus(HttpStatus.BAD_REQUEST,()->service.saveAgent(alice,null,foreign));
        var misplaced=cn.bugstack.ai.trigger.workspace.WorkspaceTemplates.upgrade(agentConfig("auto"),json);
        String tool=(String)service.saveMcp(alice,null,mcpConfig()).get("mcpId");
        ((ObjectNode)misplaced.path("nodes").get(0)).withArray("mcpBindings").addObject()
                .put("mcpId",tool).put("allTools",false).putArray("toolNames").add("search");
        expectStatus(HttpStatus.BAD_REQUEST,()->service.saveAgent(alice,null,misplaced));
        assertEquals(0,countOwned("ai_agent",alice));assertEquals(0,countOwned("ai_client_model",alice));
    }
    private ObjectNode mcpConfig() { return json.createObjectNode().put("name", "Own fixture tool").put("transportType", "streamable").put("url", "https://mcp.example.test/mcp").put("status", 1); }
    private ObjectNode withVersion(ObjectNode input, int version) { return input.put("version", version); }
    private String transport(String id) { return db.queryForObject("SELECT transport_config FROM ai_client_tool_mcp WHERE mcp_id=?", String.class, id); }
    private long countOwned(String table, String owner) { return scalar("SELECT COUNT(*) FROM " + table + " WHERE owner_user_id=?", owner); }
    private long scalar(String sql, Object... args) { return Objects.requireNonNull(db.queryForObject(sql, Long.class, args)); }
    private String agentModel(String id) { return db.queryForObject("""
            SELECT c.target_id FROM ai_agent_flow_config f JOIN ai_client_config c ON c.source_type='client' AND c.source_id=f.client_id
            WHERE f.agent_id=? AND c.target_type='model' LIMIT 1
            """, String.class, id); }
    private Set<String> ownedResourceIds(String owner) {
        Set<String> ids = new HashSet<>();
        for (var table : Map.of("ai_client", "client_id", "ai_client_model", "model_id", "ai_client_system_prompt", "prompt_id", "ai_client_advisor", "advisor_id", "ai_client_tool_mcp", "mcp_id").entrySet())
            ids.addAll(db.queryForList("SELECT " + table.getValue() + " FROM " + table.getKey() + " WHERE owner_user_id=?", String.class, owner));
        return ids;
    }
    private void relation(String type, String source, String targetType, String target) {
        db.update("INSERT INTO ai_client_config(source_type,source_id,target_type,target_id,status) VALUES(?,?,?,?,1)", type, source, targetType, target);
    }
    private void expectStatus(HttpStatus status, Runnable operation) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, operation::run);
        assertEquals(status.value(), error.getStatusCode().value());
    }
}
