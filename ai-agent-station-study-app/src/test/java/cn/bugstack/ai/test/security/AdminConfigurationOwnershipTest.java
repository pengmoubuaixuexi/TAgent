package cn.bugstack.ai.test.security;

import cn.bugstack.ai.api.dto.*;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.infrastructure.dao.*;
import cn.bugstack.ai.infrastructure.dao.po.*;
import cn.bugstack.ai.trigger.http.admin.*;
import cn.bugstack.ai.trigger.http.admin.util.AdminConfigurationOwnership;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Legacy admin editors must create usable private resources, never accept an owner from JSON. */
public class AdminConfigurationOwnershipTest {
    private static final String[] RESOURCE_NAMES = {
            "AiClient", "AiClientApi", "AiClientModel", "AiClientSystemPrompt", "AiClientAdvisor", "AiClientToolMcp"};
    private static final String GRAPH = """
            {"nodes":[
              {"id":"a","type":"agent","data":{"inputsValues":{"agentName":"Owned agent","strategy":"auto"}}},
              {"id":"c","type":"client","data":{"inputsValues":{"clientId":"client-own","clientName":"Client","sequence":1}}},
              {"id":"m","type":"tool_mcp","data":{"inputsValues":{"toolMcpName":[{"value":"mcp-own"}]}}}
            ],"edges":[{"sourceNodeID":"m","targetNodeID":"c"}]}
            """;

    @After public void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test public void everyAdminCreateUsesServerIdentityEvenWithForgedOwner() throws Exception {
        authenticate("admin-a", "ROLE_ADMIN");
        ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for (String resource : RESOURCE_NAMES) {
            AtomicReference<Object> inserted = new AtomicReference<>();
            Class<?> controllerType = Class.forName("cn.bugstack.ai.trigger.http.admin." + resource + "AdminController");
            Class<?> dtoType = Class.forName("cn.bugstack.ai.api.dto." + resource + "RequestDTO");
            Class<?> daoType = Class.forName("cn.bugstack.ai.infrastructure.dao.I" + resource + "Dao");
            Object controller = controllerType.getConstructor().newInstance();
            Object dao = mock(daoType, invocation -> {
                if ("insert".equals(invocation.getMethod().getName())) {
                    inserted.set(invocation.getArgument(0));
                    if (invocation.getMethod().getReturnType() == void.class) return null;
                    return 1;
                }
                return RETURNS_DEFAULTS.answer(invocation);
            });
            ReflectionTestUtils.setField(controller, Character.toLowerCase(resource.charAt(0)) + resource.substring(1) + "Dao", dao);
            Object request = json.readValue("{\"ownerUserId\":\"victim\",\"owner_user_id\":\"victim\"}", dtoType);
            Response<?> response = (Response<?>) controllerType.getMethod("create" + resource, dtoType).invoke(controller, request);
            assertEquals(resource, Boolean.TRUE, response.getData());
            assertEquals(resource, "admin-a", inserted.get().getClass().getMethod("getOwnerUserId").invoke(inserted.get()));
        }
    }

    @Test public void createIsFailClosedForMissingIdentityAndOrdinaryUsers() throws Exception {
        for (String resource : RESOURCE_NAMES) {
            Class<?> controllerType = Class.forName("cn.bugstack.ai.trigger.http.admin." + resource + "AdminController");
            Class<?> dtoType = Class.forName("cn.bugstack.ai.api.dto." + resource + "RequestDTO");
            Object controller = controllerType.getConstructor().newInstance();
            Object request = dtoType.getConstructor().newInstance();
            SecurityContextHolder.clearContext();
            InvocationTargetException anonymous = assertThrows(InvocationTargetException.class,
                    () -> controllerType.getMethod("create" + resource, dtoType).invoke(controller, request));
            assertEquals(HttpStatus.UNAUTHORIZED, ((ResponseStatusException) anonymous.getCause()).getStatusCode());
            authenticate("ordinary", "ROLE_USER");
            InvocationTargetException ordinary = assertThrows(InvocationTargetException.class,
                    () -> controllerType.getMethod("create" + resource, dtoType).invoke(controller, request));
            assertEquals(HttpStatus.FORBIDDEN, ((ResponseStatusException) ordinary.getCause()).getStatusCode());
        }
    }

    @Test public void modelIsPrivateByDefaultAndRequiresExplicitPlatformOptIn() {
        authenticate("admin-a", "ROLE_ADMIN");
        AiClientModelAdminController controller = new AiClientModelAdminController();
        IAiClientModelDao dao = mock(IAiClientModelDao.class);
        ReflectionTestUtils.setField(controller, "aiClientModelDao", dao);
        when(dao.insert(any())).thenReturn(1);
        assertTrue(controller.createAiClientModel(new AiClientModelRequestDTO()).getData());
        var captor = ArgumentCaptor.forClass(AiClientModel.class);
        verify(dao).insert(captor.capture());
        assertEquals(Integer.valueOf(0), captor.getValue().getPlatformEnabled());
        reset(dao);
        AiClientModelRequestDTO invalid = AiClientModelRequestDTO.builder().platformEnabled(2).build();
        assertFalse(controller.createAiClientModel(invalid).getData());
        verifyNoInteractions(dao);
        when(dao.insert(any())).thenReturn(1);
        assertTrue(controller.createAiClientModel(AiClientModelRequestDTO.builder().platformEnabled(1).build()).getData());
        verify(dao).insert(captor.capture());
        assertEquals(Integer.valueOf(1), captor.getValue().getPlatformEnabled());
    }

    @Test public void drawCreatesOwnedAgentAndOverwritesSpoofedAuditIdentity() {
        authenticate("admin-a", "ROLE_ADMIN");
        var fixture = new DrawFixture();
        AiAgentDrawConfigRequestDTO request = drawRequest();
        Response<String> result = fixture.controller.saveDrawConfig(request);
        assertEquals("0000", result.getCode());
        var agent = ArgumentCaptor.forClass(AiAgent.class);
        verify(fixture.agents).insert(agent.capture());
        assertEquals("admin-a", agent.getValue().getOwnerUserId());
        assertTrue(agent.getValue().getAgentId().startsWith("ad_"));
        assertNotEquals("victim-agent", agent.getValue().getAgentId());
        var draw = ArgumentCaptor.forClass(AiAgentDrawConfig.class);
        verify(fixture.draws).insert(draw.capture());
        assertEquals("admin-a", draw.getValue().getCreateBy());
        assertEquals("admin-a", draw.getValue().getUpdateBy());
        verify(fixture.ownership).requireOwned("admin-a", "tool_mcp", "mcp-own");
        verify(fixture.ownership, atLeastOnce()).requireOwned("admin-a", "client", "client-own");
    }

    @Test public void drawRejectsForeignMcpBeforeAnyGraphWrite() {
        authenticate("admin-a", "ROLE_ADMIN");
        var fixture = new DrawFixture();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(fixture.ownership)
                .requireOwned("admin-a", "tool_mcp", "mcp-own");
        assertThrows(ResponseStatusException.class, () -> fixture.controller.saveDrawConfig(drawRequest()));
        verifyNoInteractions(fixture.agents, fixture.draws, fixture.relations, fixture.flows);
    }

    @Test public void drawCannotReplaceAnotherUsersConfiguration() {
        authenticate("admin-a", "ROLE_ADMIN");
        var fixture = new DrawFixture();
        AiAgentDrawConfig previous = new AiAgentDrawConfig();
        previous.setAgentId("victim-agent");
        when(fixture.draws.queryByConfigId("victim-draw")).thenReturn(previous);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(fixture.ownership)
                .requireOwned("admin-a", "agent", "victim-agent");
        AiAgentDrawConfigRequestDTO request = drawRequest();
        request.setConfigId("victim-draw");
        assertThrows(ResponseStatusException.class, () -> fixture.controller.saveDrawConfig(request));
        verifyNoInteractions(fixture.agents, fixture.relations, fixture.flows);
        verify(fixture.draws, never()).insert(any());
        verify(fixture.draws, never()).updateByConfigId(any());
    }

    @Test public void relationLookupUsesExactOwnerAndUnknownResourceTypesFailClosed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var ownership = new AdminConfigurationOwnership(jdbc);
        String sql = "SELECT COUNT(*) FROM ai_client_tool_mcp WHERE mcp_id=? AND owner_user_id=?";
        when(jdbc.queryForObject(sql, Integer.class, "mcp-own", "admin-a")).thenReturn(1);
        ownership.requireOwned("admin-a", "tool_mcp", "mcp-own");
        assertThrows(ResponseStatusException.class, () -> ownership.requireOwned("other", "tool_mcp", "mcp-own"));
        assertThrows(ResponseStatusException.class, () -> ownership.requireOwned("admin-a", "tool_mcp WHERE 1=1", "mcp-own"));
        verify(jdbc).queryForObject(sql, Integer.class, "mcp-own", "admin-a");
    }

    @Test public void allOwnedMappersPersistOwnerButNeverTransferItOnUpdates() throws Exception {
        String[][] mappings = {
                {"ai_agent", "AiAgent"}, {"ai_client", "AiClient"}, {"ai_client_api", "AiClientApi"},
                {"ai_client_model", "AiClientModel"}, {"ai_client_system_prompt", "AiClientSystemPrompt"},
                {"ai_client_advisor", "AiClientAdvisor"}, {"ai_client_tool_mcp", "AiClientToolMcp"}};
        for (String[] mapping : mappings) {
            String path = "mybatis/mapper/" + mapping[0] + "_mapper.xml";
            Configuration configuration = new Configuration();
            try (var xml = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(path, xml);
                new XMLMapperBuilder(xml, configuration, path, configuration.getSqlFragments()).parse();
            }
            String namespace = "cn.bugstack.ai.infrastructure.dao.I" + mapping[1] + "Dao";
            Object po = Class.forName("cn.bugstack.ai.infrastructure.dao.po." + mapping[1]).getConstructor().newInstance();
            var insert = configuration.getMappedStatement(namespace + ".insert").getBoundSql(po);
            assertTrue(mapping[0], insert.getSql().contains("owner_user_id"));
            assertTrue(mapping[0], insert.getParameterMappings().stream().anyMatch(p -> "ownerUserId".equals(p.getProperty())));
            var update = configuration.getMappedStatement(namespace + ".updateById").getBoundSql(po);
            assertFalse(mapping[0], update.getSql().contains("owner_user_id"));
            assertTrue(mapping[0], configuration.getMappedStatement(namespace + ".queryById").getResultMaps().get(0)
                    .getResultMappings().stream().anyMatch(m -> "ownerUserId".equals(m.getProperty())));
        }
    }

    private static AiAgentDrawConfigRequestDTO drawRequest() {
        return AiAgentDrawConfigRequestDTO.builder().configName("Own graph").configData(GRAPH)
                .agentId("victim-agent").createBy("victim").updateBy("victim").build();
    }

    private static void authenticate(String user, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user, "unused", List.of(new SimpleGrantedAuthority(role))));
    }

    private static class DrawFixture {
        final AiAgentDrawAdminController controller = new AiAgentDrawAdminController();
        final IAiAgentDao agents = mock(IAiAgentDao.class);
        final IAiAgentDrawConfigDao draws = mock(IAiAgentDrawConfigDao.class);
        final IAiClientConfigDao relations = mock(IAiClientConfigDao.class);
        final IAiAgentFlowConfigDao flows = mock(IAiAgentFlowConfigDao.class);
        final AdminConfigurationOwnership ownership = mock(AdminConfigurationOwnership.class);
        DrawFixture() {
            ReflectionTestUtils.setField(controller, "aiAgentDao", agents);
            ReflectionTestUtils.setField(controller, "aiAgentDrawConfigDao", draws);
            ReflectionTestUtils.setField(controller, "aiClientConfigDao", relations);
            ReflectionTestUtils.setField(controller, "aiAgentFlowConfigDao", flows);
            ReflectionTestUtils.setField(controller, "configurationOwnership", ownership);
            when(agents.insert(any())).thenReturn(1);
            when(draws.insert(any())).thenReturn(1);
        }
    }
}
