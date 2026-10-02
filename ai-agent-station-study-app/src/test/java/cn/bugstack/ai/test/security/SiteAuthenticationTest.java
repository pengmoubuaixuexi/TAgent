package cn.bugstack.ai.test.security;

import cn.bugstack.ai.infrastructure.dao.IAdminUserDao;
import cn.bugstack.ai.infrastructure.dao.po.AdminUser;
import cn.bugstack.ai.trigger.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@RunWith(SpringRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = SiteAuthenticationTest.Config.class)
public class SiteAuthenticationTest {
    @Configuration @EnableWebMvc
    @Import({SiteSecurityConfig.class, AccountService.class, AccountController.class,
            AuthenticatedBodyAdvice.class, ProbeController.class})
    static class Config {
        @Bean IAdminUserDao users() { return mock(IAdminUserDao.class); }
        @Bean ConversationAccess access() { return mock(ConversationAccess.class); }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
    }
    @RestController static class ProbeController {
        @GetMapping({"/observe.html", "/observe-mcp.html", "/agent-config.html", "/eval.html",
                "/api/v1/observe/test", "/api/v1/admin/test", "/api/v1/eval/test"})
        String admin() { return "ok"; }
        @PostMapping({"/api/v1/agent/probe", "/api/v1/agent/auto_agent"}) Map<String, Object> echo(@RequestBody Map<String,Object> body,
                @RequestHeader("X-User-Id") String header, @RequestParam("userId") String query) {
            return Map.of("body", body.get("userId"), "header", header, "query", query);
        }
    }
    @Autowired WebApplicationContext context;
    @Autowired IAdminUserDao users;
    @Autowired ConversationAccess access;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    MockMvc mvc;
    @Before public void setup() {
        reset(users, access);
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .defaultRequest(get("/").accept("application/json"))
                .apply(springSecurity()).addFilters(new AuthenticatedUserFilter(users, access)).build();
        when(users.queryByUserId("u1")).thenReturn(account("u1", "tester", "USER"));
        when(users.queryByUserId("10001")).thenReturn(account("10001", "admin", "ADMIN"));
    }
    private AdminUser account(String id, String name, String role) {
        return AdminUser.builder().id(1L).userId(id).username(name).role(role).password("legacy-password").status(1).build();
    }
    @Test public void anonymousCannotReadAdminApiOrPage() throws Exception {
        mvc.perform(get("/api/v1/observe/test")).andExpect(status().isUnauthorized());
        mvc.perform(get("/observe-mcp.html")).andExpect(status().is3xxRedirection());
    }
    @Test public void ordinaryUserCannotEnterAnyManagementSurface() throws Exception {
        for (String path : new String[]{"/observe.html", "/observe-mcp.html", "/agent-config.html",
                "/api/v1/observe/test", "/api/v1/admin/test"}) {
            mvc.perform(get(path).with(user("u1").roles("USER"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("10001").roles("ADMIN"))).andExpect(status().isOk());
        }
    }
    @Test public void ordinaryUserCanUseEvalOps() throws Exception {
        mvc.perform(get("/eval.html").with(user("u1"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/eval/test").with(user("u1"))).andExpect(status().isOk());
    }
    @Test public void registrationCannotSelectAdminRoleOrUserId() throws Exception {
        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType("application/json")
                .content("{\"username\":\"newtester\",\"password\":\"long-password-123\",\"role\":\"ADMIN\",\"userId\":\"10001\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("USER"));
        var captor = org.mockito.ArgumentCaptor.forClass(AdminUser.class);
        verify(users).insert(captor.capture());
        assertEquals("USER", captor.getValue().getRole());
        assertNotEquals("10001", captor.getValue().getUserId());
        assertTrue(passwords.matches("long-password-123", captor.getValue().getPassword()));
    }
    @Test public void registrationRejectsReservedDuplicateAndWeakCredentials() throws Exception {
        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType("application/json")
                .content("{\"username\":\"ADMIN\",\"password\":\"long-password-123\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType("application/json")
                .content("{\"username\":\"tester\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
        when(users.queryByUsername("taken")).thenReturn(account("u2", "taken", "USER"));
        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType("application/json")
                .content("{\"username\":\"taken\",\"password\":\"long-password-123\"}"))
                .andExpect(status().isConflict());
    }
    @Test public void loginUpgradesLegacyPasswordRotatesSessionAndLogoutRevokesIt() throws Exception {
        AdminUser owner = account("10001", "admin", "ADMIN");
        when(users.queryByUsername("admin")).thenReturn(owner);
        MockHttpSession session = new MockHttpSession(); String oldId = session.getId();
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf()).contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"legacy-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("ADMIN"));
        assertNotEquals(oldId, session.getId());
        assertEquals("10001", session.getAttribute("userId"));
        assertEquals("ADMIN", session.getAttribute("role"));
        assertEquals(true, session.getAttribute("isAdmin"));
        verify(users).updateById(owner);
        assertTrue(passwords.matches("legacy-password", owner.getPassword()));
        mvc.perform(get("/api/v1/observe/test").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
        mvc.perform(get("/api/v1/observe/test")).andExpect(status().isUnauthorized());
    }
    @Test public void csrfIsRequiredAndSpoofedIdentityIsReplaced() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/agent/probe?userId=10001").with(user("u1")).with(csrf())
                .header("X-User-Id", "10001").contentType("application/json").content("{\"userId\":\"10001\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body").value("u1"))
                .andExpect(jsonPath("$.header").value("u1")).andExpect(jsonPath("$.query").value("u1"));
    }
    @Test public void disabledAccountAndCrossUserSessionAreDenied() throws Exception {
        when(users.queryByUserId("u1")).thenReturn(null);
        mvc.perform(get("/api/v1/auth/me").with(user("u1"))).andExpect(status().isUnauthorized());
        when(users.queryByUserId("u1")).thenReturn(account("u1", "tester", "USER"));
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN))
                .when(access).session("someone-elses-session", "u1", false);
        mvc.perform(get("/api/v1/agent/conversation_messages?conversationId=someone-elses-session")
                .with(user("u1"))).andExpect(status().isForbidden());
    }
    @Test public void newChatReservesClientGeneratedRunInsteadOfRequiringAnExistingSnapshot() throws Exception {
        mvc.perform(post("/api/v1/agent/auto_agent").with(user("u1")).with(csrf())
                .contentType("application/json").content("{\"sessionId\":\"new-session\",\"runId\":\"new-run\"}"))
                .andExpect(status().isOk());
        verify(access).session("new-session", "u1", true);
        verify(access).run("new-run", "u1", true);
    }
    @Test public void changingRoleRevokesPreviouslyAuthenticatedSession() throws Exception {
        when(users.queryByUserId("10001")).thenReturn(account("10001", "admin", "USER"));
        mvc.perform(get("/api/v1/observe/test").with(user("10001").roles("ADMIN")))
                .andExpect(status().isUnauthorized());
    }
}
