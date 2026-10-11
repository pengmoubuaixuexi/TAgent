package cn.bugstack.ai.test.security;

import cn.bugstack.ai.domain.agent.service.notification.INotificationStore;
import cn.bugstack.ai.domain.agent.service.notification.NotificationService;
import cn.bugstack.ai.infrastructure.dao.IAdminUserDao;
import cn.bugstack.ai.infrastructure.dao.po.AdminUser;
import cn.bugstack.ai.trigger.http.NotificationController;
import cn.bugstack.ai.trigger.security.AccountService;
import cn.bugstack.ai.trigger.security.AuthenticatedUserFilter;
import cn.bugstack.ai.trigger.security.ConversationAccess;
import cn.bugstack.ai.trigger.security.SiteSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@RunWith(SpringRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = NotificationInboxAccessTest.Config.class)
public class NotificationInboxAccessTest {
    @Configuration @EnableWebMvc
    @Import({SiteSecurityConfig.class, NotificationController.class})
    static class Config {
        @Bean IAdminUserDao users() { return mock(IAdminUserDao.class); }
        @Bean ConversationAccess access() { return mock(ConversationAccess.class); }
        @Bean INotificationStore store() { return mock(INotificationStore.class); }
        @Bean NotificationService notifications(INotificationStore store) { return new NotificationService(store); }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
    }

    @Autowired WebApplicationContext context;
    @Autowired IAdminUserDao users;
    @Autowired ConversationAccess access;
    @Autowired INotificationStore store;
    private MockMvc mvc;

    @Before public void setup() {
        reset(users, access, store);
        when(users.queryByUserId("alice")).thenReturn(AdminUser.builder().userId("alice")
                .username("Alice").role("USER").password("fixture-password").status(1).build());
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .defaultRequest(get("/").accept("application/json").with(request -> {
                    request.getSession().setAttribute("credentialRevision", AccountService.credentialRevision("fixture-password"));
                    return request;
                })).apply(springSecurity()).addFilters(new AuthenticatedUserFilter(users, access)).build();
    }

    @Test public void bulkReadRequiresLoginAndCsrfBeforeTouchingNotifications() throws Exception {
        mvc.perform(post("/api/v1/agent/notifications/ack-all?before=123").with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/agent/notifications/ack-all?before=123").with(user("alice")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }

    @Test public void bulkReadUsesAuthenticatedOwnerAndSnapshotEvenWithSpoofedIdentity() throws Exception {
        when(store.acknowledgeAll(eq("alice"), eq(123L), anyLong())).thenReturn(7);
        mvc.perform(post("/api/v1/agent/notifications/ack-all?before=123&userId=bob")
                        .header("X-User-Id", "bob").with(user("alice")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data").value(7));
        verify(store).acknowledgeAll(eq("alice"), eq(123L), anyLong());
        verifyNoMoreInteractions(store);
    }

    @Test public void bulkReadRequiresExplicitSnapshot() throws Exception {
        mvc.perform(post("/api/v1/agent/notifications/ack-all").with(user("alice")).with(csrf()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
}
