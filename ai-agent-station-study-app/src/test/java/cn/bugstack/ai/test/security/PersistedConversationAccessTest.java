package cn.bugstack.ai.test.security;

import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import cn.bugstack.ai.infrastructure.dao.IConversationOwnerDao;
import cn.bugstack.ai.trigger.security.ConversationAccess;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

/** Exercise the real mapper against durable Eval session IDs, without Redis or a deployed DB. */
public class PersistedConversationAccessTest {
    private static final String SESSION = "eval-b8b7c137-e2e100-main";
    private JdbcTemplate db;
    private SqlSession sql;
    private IConversationOwnerDao owners;
    private ConversationAccess access;

    @Before @SuppressWarnings("unchecked") public void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                // MySQL CAST AS BINARY keeps the full value; H2 otherwise defaults to BINARY(1).
                + ";MODE=MySQL;VARIABLE_BINARY=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE auth_conversation_owner(conversation_id VARCHAR PRIMARY KEY,user_id VARCHAR)");
        db.execute("CREATE TABLE ai_chat_memory(conversation_id VARCHAR,user_id VARCHAR_IGNORECASE)");
        db.execute("CREATE TABLE ai_run_history(session_id VARCHAR,user_id VARCHAR_IGNORECASE)");
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
        config.addMapper(IConversationOwnerDao.class);
        sql = new SqlSessionFactoryBuilder().build(config).openSession(true);
        owners = sql.getMapper(IConversationOwnerDao.class);
        access = new ConversationAccess(owners, mock(ObjectProvider.class));
    }

    @After public void close() { sql.close(); }

    @Test public void evalRunCanOpenBeforeTheFirstCompletedTurnWithoutClaimingAnOwner() {
        db.update("INSERT INTO ai_run_history VALUES(?,?)", SESSION, "10001");
        access.session(SESSION, "10001", false);
        access.session("default:10001:" + SESSION, "10001", false);
        access.session("10001:" + SESSION, "10001", false);
        assertNull(owners.owner(SESSION));
        assertThrows(ResponseStatusException.class, () -> access.session(SESSION, "other", false));
        assertThrows(ResponseStatusException.class, () -> access.session("default:10001:" + SESSION, "other", false));
    }

    @Test public void completedEvalConversationCanOpenAfterRedisAndRunHistoryExpire() {
        db.update("INSERT INTO ai_chat_memory VALUES(?,?)", "default:10001:" + SESSION, "10001");
        access.session("default:10001:" + SESSION, "10001", false);
        access.session(SESSION, "10001", false);
        assertThrows(ResponseStatusException.class, () -> access.session(SESSION, "other", false));
    }

    @Test public void matchingOwnersAcrossBothDurableSourcesRemainAFullString() {
        db.update("INSERT INTO ai_chat_memory VALUES(?,?),(?,?),(?,?)",
                SESSION, "10001", "default:10001:" + SESSION, "10001", "10001:" + SESSION, "10001");
        db.update("INSERT INTO ai_run_history VALUES(?,?),(?,?)", SESSION, "10001", SESSION, "10001");
        assertEquals("10001", owners.persistedOwner(SESSION, "default:10001:" + SESSION, "10001:" + SESSION));
        access.session(SESSION, "10001", false);
        assertNull(owners.owner(SESSION));
    }

    @Test public void caseInsensitiveColumnsCannotHideConflictingOwners() {
        assertConflictingOwnersCannotBeClaimed("CaseOwner", "caseOwner");
    }

    @Test public void trailingSpacesCannotHideConflictingOwners() {
        assertConflictingOwnersCannotBeClaimed("10001", "10001 ");
    }

    @Test public void emptyOwnerAlongsideAValidOwnerCannotBeClaimed() {
        assertConflictingOwnersCannotBeClaimed("10001", "");
    }

    @Test public void anonymousOnlyRecordsCannotBeClaimed() {
        for (String anonymous : new String[] {null, ""}) {
            db.update("DELETE FROM ai_run_history");
            db.update("INSERT INTO ai_run_history VALUES(?,?)", SESSION, anonymous);
            sql.clearCache();
            assertEquals("__legacy_unowned__", owners.persistedOwner(SESSION,
                    "default:10001:" + SESSION, "10001:" + SESSION));
            assertThrows(ResponseStatusException.class, () -> access.session(SESSION, "10001", true));
            assertNull(owners.owner(SESSION));
        }
    }

    private void assertConflictingOwnersCannotBeClaimed(String memoryOwner, String historyOwner) {
        db.update("INSERT INTO ai_chat_memory VALUES(?,?)", SESSION, memoryOwner);
        db.update("INSERT INTO ai_run_history VALUES(?,?)", SESSION, historyOwner);
        assertEquals("__legacy_unowned__", owners.persistedOwner(SESSION,
                "default:" + memoryOwner + ":" + SESSION, memoryOwner + ":" + SESSION));
        assertThrows(ResponseStatusException.class, () -> access.session(SESSION, memoryOwner, true));
        assertNull(owners.owner(SESSION));
    }

    @Test public void missingAmbiguousOrAnonymousDurableOwnersCannotBeClaimed() {
        assertThrows(ResponseStatusException.class, () -> access.session(SESSION, "10001", false));
        db.update("INSERT INTO ai_run_history VALUES(?,?),(?,?)", SESSION, "10001", SESSION, "other");
        sql.clearCache();
        assertThrows(ResponseStatusException.class, () -> access.session(SESSION, "10001", true));
        assertNull(owners.owner(SESSION));
        db.update("DELETE FROM ai_run_history");
        db.update("INSERT INTO ai_chat_memory VALUES(?,?),(?,?)", "default:10001:" + SESSION, "10001", SESSION, null);
        sql.clearCache();
        assertThrows(ResponseStatusException.class, () -> access.session(SESSION, "10001", true));
        assertNull(owners.owner(SESSION));
    }

    @Test public void explicitOwnerAlwaysWinsOverAnUnrelatedHistoryRecord() {
        db.update("INSERT INTO auth_conversation_owner VALUES(?,?)", SESSION, "other");
        db.update("INSERT INTO ai_run_history VALUES(?,?)", SESSION, "10001");
        assertThrows(ResponseStatusException.class, () -> access.session(SESSION, "10001", false));
        assertThrows(ResponseStatusException.class, () -> access.session("default:10001:" + SESSION, "10001", true));
        access.session(SESSION, "other", false);
    }
}
