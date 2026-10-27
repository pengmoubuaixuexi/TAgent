package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.adapter.repository.IRunHistoryRepository;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.infrastructure.adapter.repository.JdbcRunHistoryRepository;
import cn.bugstack.ai.infrastructure.dao.IConversationOwnerDao;
import cn.bugstack.ai.trigger.security.ConversationAccess;
import cn.bugstack.ai.trigger.workspace.RunHistoryController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP mapping, ownership mapper and durable repository; no application, Redis or external DB. */
public class RunHistorySessionAccessTest {
    private static final String SESSION="eval-failed-without-chat";
    private JdbcTemplate db;
    private SqlSession sql;
    private JdbcRunHistoryRepository history;
    private ConversationAccess access;
    private MockMvc mvc;

    @Before @SuppressWarnings("unchecked") public void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;VARIABLE_BINARY=TRUE;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(source);
        db.execute("CREATE TABLE auth_conversation_owner(conversation_id VARCHAR PRIMARY KEY,user_id VARCHAR)");
        db.execute("CREATE TABLE ai_chat_memory(conversation_id VARCHAR,user_id VARCHAR)");
        db.execute("CREATE TABLE ai_run_history(run_id VARCHAR PRIMARY KEY,user_id VARCHAR,session_id VARCHAR,agent_id VARCHAR,agent_version INT,strategy VARCHAR,status VARCHAR,question CLOB,answer CLOB,events_json CLOB,events_truncated INT DEFAULT 0,last_error VARCHAR,instance_id VARCHAR,heartbeat_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        var config=new Configuration(new Environment("test",new JdbcTransactionFactory(),source));
        config.addMapper(IConversationOwnerDao.class);
        sql=new SqlSessionFactoryBuilder().build(config).openSession(true);
        access=new ConversationAccess(sql.getMapper(IConversationOwnerDao.class),mock(ObjectProvider.class));
        history=new JdbcRunHistoryRepository(db,new ObjectMapper());
        mvc=MockMvcBuilders.standaloneSetup(new RunHistoryController(history,access,null)).build();
    }

    @After public void close(){sql.close();}

    private UsernamePasswordAuthenticationToken auth(String user,String role) {
        return new UsernamePasswordAuthenticationToken(user,"unused",List.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }
    private void run(String id,String owner,String session,String status) {
        history.start(ExecuteCommandEntity.builder().runId(id).userId(owner).sessionId(session).message("question").build());
        history.status(id,status,"FAILED".equals(status)?"provider failed":null);
        db.update("UPDATE ai_run_history SET created_at=? WHERE run_id=?",new Timestamp(1000),id);
        sql.clearCache();
    }

    @Test public void failedAndCancelledRunsAreDiscoverableBeforeAnyChatTurnAndAfterRestart() throws Exception {
        run("r1","alice",SESSION,"FAILED");run("r2","alice",SESSION,"CANCELLED");
        mvc=MockMvcBuilders.standaloneSetup(new RunHistoryController(new JdbcRunHistoryRepository(db,new ObjectMapper()),access,null)).build();
        mvc.perform(get("/api/v1/history/session/{sessionId}",SESSION).principal(auth("alice","USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].runId").value("r2"))
                .andExpect(jsonPath("$.data[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.data[1].sessionId").value(SESSION)).andExpect(jsonPath("$.data[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data[1].error").value("provider failed"))
                .andExpect(jsonPath("$.data[1].events").doesNotExist()).andExpect(jsonPath("$.data[1].answer").doesNotExist());
        assertEquals(Integer.valueOf(0),db.queryForObject("SELECT COUNT(*) FROM ai_chat_memory",Integer.class));
        assertEquals(Integer.valueOf(0),db.queryForObject("SELECT COUNT(*) FROM auth_conversation_owner",Integer.class));
    }

    @Test public void loginIsRequiredAndAnAdminCannotReadAnotherOwnersSession() throws Exception {
        run("r1","alice",SESSION,"FAILED");
        mvc.perform(get("/api/v1/history/session/{sessionId}",SESSION)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/history/session/{sessionId}",SESSION)
                        .principal(new UsernamePasswordAuthenticationToken("alice","unused")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/history/session/{sessionId}",SESSION).principal(auth("bob","USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/history/session/{sessionId}",SESSION).principal(auth("admin","ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/history/session/{sessionId}",SESSION).principal(auth("alice","USER"))
                        .param("userId","bob").header("X-User-Id","bob"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].userId").value("alice"));
    }

    @Test public void explicitForeignAndAmbiguousDurableOwnershipAreForbidden() throws Exception {
        run("r1","alice",SESSION,"FAILED");
        db.update("INSERT INTO auth_conversation_owner VALUES(?,?)",SESSION,"bob");
        sql.clearCache();
        mvc.perform(get("/api/v1/history/session/{sessionId}",SESSION).principal(auth("alice","USER")))
                .andExpect(status().isForbidden());
        db.update("DELETE FROM auth_conversation_owner");
        run("r2","bob",SESSION,"CANCELLED");
        mvc.perform(get("/api/v1/history/session/{sessionId}",SESSION).principal(auth("alice","USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/history/session/{sessionId}",SESSION).principal(auth("bob","USER")))
                .andExpect(status().isForbidden());
    }

    @Test public void routeUsesExactSessionDefaultPaginationAndAuthenticatedOwner() throws Exception {
        IRunHistoryRepository repository=mock(IRunHistoryRepository.class);
        ConversationAccess guard=mock(ConversationAccess.class);
        when(repository.listOwnedSession("alice",SESSION,0,50)).thenReturn(List.of());
        mvc=MockMvcBuilders.standaloneSetup(new RunHistoryController(repository,guard,null)).build();
        mvc.perform(get("/api/v1/history/session/{sessionId}",SESSION).principal(auth("alice","USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        var ordered=inOrder(guard,repository);
        ordered.verify(guard).session(SESSION,"alice",false);
        ordered.verify(repository).listOwnedSession("alice",SESSION,0,50);
        mvc.perform(get("/api/v1/history/session/{sessionId}",SESSION).param("offset","2").param("limit","10")
                        .principal(auth("alice","USER"))).andExpect(status().isOk());
        verify(repository).listOwnedSession("alice",SESSION,2,10);
    }
}
