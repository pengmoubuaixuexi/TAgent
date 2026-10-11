package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.api.dto.AiClientToolMcpRequestDTO;
import cn.bugstack.ai.domain.agent.service.IArmoryService;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.ai.infrastructure.adapter.repository.AgentRepository;
import cn.bugstack.ai.infrastructure.dao.IAiClientConfigDao;
import cn.bugstack.ai.infrastructure.dao.IAiClientToolMcpDao;
import cn.bugstack.ai.infrastructure.dao.po.AiClientConfig;
import cn.bugstack.ai.trigger.http.admin.AiClientToolMcpAdminController;
import cn.bugstack.ai.trigger.workspace.McpConfigurationInvalidator;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real mapper and local H2 only. No provider request, remote MCP connection or application database. */
public class PublicMcpRuntimeConfigurationTest {
    private JdbcTemplate db;
    private IAiClientToolMcpDao dao;
    private AgentRepository repository;
    private McpToolCatalogService catalog;
    private IArmoryService armory;
    private McpConfigurationInvalidator invalidator;
    private AiClientToolMcpAdminController controller;
    private TransactionTemplate transactions;
    private static final String OLD = "{\"url\":\"https://old.example/mcp\",\"headers\":{\"Authorization\":\"Bearer old-test-token\"}}";
    private static final String NEW = "{\"url\":\"https://new.example/mcp\",\"headers\":{\"Authorization\":\"Bearer new-test-token\"}}";

    @Before public void setup() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:mcp_runtime_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(source);
        db.execute("""
                CREATE TABLE ai_client_tool_mcp(id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_user_id VARCHAR(64),
                  source_mcp_id VARCHAR(64), is_public INT DEFAULT 0, mcp_id VARCHAR(64) UNIQUE, mcp_name VARCHAR(128),
                  transport_type VARCHAR(32), transport_config VARCHAR(2000), request_timeout INT, status INT,
                  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        db.execute("CREATE TABLE ai_agent_flow_config(agent_id VARCHAR(64),client_id VARCHAR(64))");
        db.execute("CREATE TABLE ai_client_config(source_type VARCHAR(32),source_id VARCHAR(64),target_type VARCHAR(32),target_id VARCHAR(64))");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mybatis/mapper/ai_client_tool_mcp_mapper.xml"));
        dao = new SqlSessionTemplate(factory.getObject()).getMapper(IAiClientToolMcpDao.class);
        repository = new AgentRepository();
        ReflectionTestUtils.setField(repository, "aiClientToolMcpDao", dao);
        insert("platform", "admin", null, 1);
        insert("alice-search", "alice", "platform", 0);
        insert("bob-search", "bob", "platform", 0);
        insert("alice-private", "alice", null, 0);
        db.update("INSERT INTO ai_agent_flow_config VALUES('alice-agent','alice-client'),('bob-agent','bob-client'),('private-agent','private-client')");
        db.update("INSERT INTO ai_client_config VALUES('client','alice-client','tool_mcp','alice-search'),('client','bob-client','model','bob-model'),('model','bob-model','tool_mcp','bob-search'),('client','private-client','tool_mcp','alice-private')");
        catalog = mock(McpToolCatalogService.class);
        armory = mock(IArmoryService.class);
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; }).when(armory).invalidateAgents(anySet(), any());
        invalidator = new McpConfigurationInvalidator(db, armory, catalog);
        controller = new AiClientToolMcpAdminController();
        ReflectionTestUtils.setField(controller, "aiClientToolMcpDao", dao);
        ReflectionTestUtils.setField(controller, "configurationInvalidator", invalidator);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @After public void cleanup() { db.execute("DROP ALL OBJECTS"); }

    private void insert(String id, String user, String source, int publicFlag) {
        db.update("INSERT INTO ai_client_tool_mcp(mcp_id,mcp_name,owner_user_id,source_mcp_id,is_public,transport_type,transport_config,request_timeout,status) VALUES(?,?,?,?,?,'streamable-http',?,30,1)",
                id, id, user, source, publicFlag, OLD);
    }

    @Test public void rotationUsesCurrentSourceButKeepsDifferentPerUserConnectionIdentities() {
        assertEquals(OLD, repository.queryAiClientToolMcpVOByMcpId("alice-search").getTransportConfig());
        db.update("UPDATE ai_client_tool_mcp SET transport_config=?,request_timeout=45 WHERE mcp_id='platform'", NEW);
        for (String id : List.of("alice-search", "bob-search")) {
            var actual = repository.queryAiClientToolMcpVOByMcpId(id);
            assertEquals(id, actual.getMcpId());
            assertEquals(NEW, actual.getTransportConfig());
            assertEquals("https://new.example/mcp", actual.getTransportConfigStreamableHttp().getUrl());
            assertEquals("Bearer new-test-token", actual.getTransportConfigStreamableHttp().getHeaders().get("Authorization"));
            assertEquals(Integer.valueOf(45), actual.getRequestTimeout());
            assertEquals("The persisted copied secret must not be the runtime authority", OLD, dao.queryByMcpId(id).getTransportConfig());
        }
        assertEquals("alice", dao.queryByMcpId("alice-search").getOwnerUserId());
        assertEquals("bob", dao.queryByMcpId("bob-search").getOwnerUserId());
        assertEquals(OLD, repository.queryAiClientToolMcpVOByMcpId("alice-private").getTransportConfig());
    }

    @Test public void revokedDisabledDeletedAndChainedSourcesCannotFallBackToCopiedCredentials() {
        db.update("UPDATE ai_client_tool_mcp SET is_public=0 WHERE mcp_id='platform'");
        assertNull(repository.queryAiClientToolMcpVOByMcpId("alice-search"));
        db.update("UPDATE ai_client_tool_mcp SET is_public=1,status=0 WHERE mcp_id='platform'");
        assertNull(repository.queryAiClientToolMcpVOByMcpId("alice-search"));
        db.update("UPDATE ai_client_tool_mcp SET status=1,source_mcp_id='alice-private' WHERE mcp_id='platform'");
        assertNull(repository.queryAiClientToolMcpVOByMcpId("alice-search"));
        db.update("DELETE FROM ai_client_tool_mcp WHERE mcp_id='platform'");
        assertNull(repository.queryAiClientToolMcpVOByMcpId("alice-search"));
        assertNotNull(repository.queryAiClientToolMcpVOByMcpId("alice-private"));
    }

    @Test public void disabledCloneStaysDisabledEvenWhenSourceIsPublic() {
        db.update("UPDATE ai_client_tool_mcp SET status=0 WHERE mcp_id='alice-search'");
        assertNull(repository.queryAiClientToolMcpVOByMcpId("alice-search"));
        assertNotNull(repository.queryAiClientToolMcpVOByMcpId("bob-search"));
    }

    @Test public void changedStdioNameAndTransportAreReadAsOneSourceConfiguration() {
        db.update("UPDATE ai_client_tool_mcp SET mcp_name='renamed-calculator',transport_type='stdio',transport_config=? WHERE mcp_id='platform'",
                "{\"renamed-calculator\":{\"command\":\"node\",\"args\":[\"calculator.js\"]}}");
        var actual = repository.queryAiClientToolMcpVOByMcpId("alice-search");
        assertEquals("alice-search", actual.getMcpId());
        assertEquals("renamed-calculator", actual.getMcpName());
        assertEquals("stdio", actual.getTransportType());
        assertEquals("node", actual.getTransportConfigStdio().getStdio().get(actual.getMcpName()).getCommand());
    }

    @Test public void eagerAssemblyAlsoUsesCurrentSourceAndDropsRevokedClones() {
        IAiClientConfigDao links = mock(IAiClientConfigDao.class);
        ReflectionTestUtils.setField(repository, "aiClientConfigDao", links);
        when(links.queryBySourceTypeAndId("client", "client")).thenReturn(List.of(AiClientConfig.builder()
                .targetType("model").targetId("model").status(1).build()));
        when(links.queryBySourceTypeAndId("model", "model")).thenReturn(List.of(AiClientConfig.builder()
                .targetType("tool_mcp").targetId("alice-search").status(1).build()));
        db.update("UPDATE ai_client_tool_mcp SET transport_config=? WHERE mcp_id='platform'", NEW);
        assertEquals(NEW, repository.AiClientToolMcpVOByClientIds(List.of("client")).get(0).getTransportConfig());
        db.update("UPDATE ai_client_tool_mcp SET is_public=0 WHERE mcp_id='platform'");
        assertTrue(repository.AiClientToolMcpVOByClientIds(List.of("client")).isEmpty());
        assertEquals(Set.of("platform", "alice-private"), repository.queryEnabledAiClientToolMcpVOList().stream()
                .map(item -> item.getMcpId()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test public void adminUpdateRetiresSourceAndEveryDerivedPoolAndAffectedAgent() {
        var request = updateRequest();
        assertTrue(controller.updateAiClientToolMcpByMcpId(request).getData());
        verify(catalog).invalidateMcp("platform");
        verify(catalog).invalidateMcp("alice-search");
        verify(catalog).invalidateMcp("bob-search");
        verify(catalog, never()).invalidateMcp("alice-private");
        verify(armory).invalidateAgents(eq(Set.of("alice-agent", "bob-agent")), any());
        assertEquals(NEW, repository.queryAiClientToolMcpVOByMcpId("alice-search").getTransportConfig());
    }

    @Test public void adminDeleteByNumericIdStillRetiresSurvivingClonePools() {
        assertTrue(controller.deleteAiClientToolMcpById(dao.queryByMcpId("platform").getId()).getData());
        verify(catalog).invalidateMcp("platform");
        verify(catalog).invalidateMcp("alice-search");
        verify(catalog).invalidateMcp("bob-search");
        assertNull(repository.queryAiClientToolMcpVOByMcpId("alice-search"));
    }

    @Test public void invalidationWaitsForCommitAndDoesNotRunAfterRollback() {
        transactions.execute(status -> {
            invalidator.invalidateAfterCommit("platform");
            verifyNoInteractions(catalog);
            status.setRollbackOnly(); return null;
        });
        verifyNoInteractions(catalog);
        transactions.execute(status -> {
            invalidator.invalidateAfterCommit("platform");
            verifyNoInteractions(catalog); return null;
        });
        verify(catalog).invalidateMcp("alice-search");
    }

    private AiClientToolMcpRequestDTO updateRequest() {
        AiClientToolMcpRequestDTO request = new AiClientToolMcpRequestDTO();
        request.setMcpId("platform"); request.setMcpName("Platform Search");
        request.setTransportType("streamable-http"); request.setTransportConfig(NEW);
        request.setRequestTimeout(45); request.setStatus(1); return request;
    }
}
