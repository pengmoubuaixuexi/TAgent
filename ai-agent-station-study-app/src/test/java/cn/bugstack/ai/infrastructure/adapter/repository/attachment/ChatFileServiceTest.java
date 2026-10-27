package cn.bugstack.ai.infrastructure.adapter.repository.attachment;

import cn.bugstack.ai.domain.agent.model.entity.ChatFileInput;
import cn.bugstack.ai.domain.agent.model.entity.ChatFileRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/** H2 in MySQL mode with the V072 attachment tables; local storage in a temporary folder. */
public class ChatFileServiceTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate db;
    private ChatFileService files;
    private Path storeRoot;
    private Path cacheRoot;

    @Before
    public void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE ai_chat_file(attachment_id VARCHAR(64) PRIMARY KEY,owner_user_id VARCHAR(64) NOT NULL,session_id VARCHAR(255),"
                + "conversation_id VARCHAR(320),run_id VARCHAR(128),kind VARCHAR(16) NOT NULL,status VARCHAR(16) NOT NULL,file_name VARCHAR(255) NOT NULL,"
                + "mime_type VARCHAR(128) NOT NULL,file_kind VARCHAR(16) NOT NULL,file_size BIGINT DEFAULT 0 NOT NULL,sha256 CHAR(64),"
                + "storage_provider VARCHAR(16),bucket_name VARCHAR(128),object_key VARCHAR(512),artifact_id VARCHAR(64),artifact_revision INT,"
                + "parse_status VARCHAR(16) NOT NULL,parse_error VARCHAR(500),unit VARCHAR(8),unit_count INT,text_chars BIGINT,"
                + "truncated TINYINT DEFAULT 0 NOT NULL,structure_json CLOB,preview_text CLOB,inline_text CLOB,"
                + "created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,bound_at TIMESTAMP(3),"
                + "last_used_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,purged_at TIMESTAMP(3))");
        db.execute("CREATE TABLE ai_chat_file_run(run_id VARCHAR(128) NOT NULL,attachment_id VARCHAR(64) NOT NULL,position INT NOT NULL,"
                + "created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3),PRIMARY KEY(run_id,attachment_id))");
        db.execute("CREATE TABLE ai_run_artifact(artifact_id VARCHAR(64) PRIMARY KEY,owner_user_id VARCHAR(64),run_id VARCHAR(128),"
                + "title VARCHAR(160),format VARCHAR(16),current_revision INT,agent_id VARCHAR(64))");
        db.execute("CREATE TABLE ai_agent(agent_id VARCHAR(64) PRIMARY KEY,agent_name VARCHAR(255))");
        db.execute("CREATE TABLE ai_run_artifact_revision(artifact_id VARCHAR(64),revision INT,title VARCHAR(160),format VARCHAR(16),"
                + "content_json CLOB,PRIMARY KEY(artifact_id,revision))");
        storeRoot = temp.newFolder("store").toPath();
        cacheRoot = temp.newFolder("cache").toPath();
        files = new ChatFileService(db, new DataSourceTransactionManager(source), new LocalChatFileStorage(storeRoot),
                new ChatFileParser(), cacheRoot.toString(), "chat-files", 1024 * 1024, 2, 8);
    }

    @After
    public void shutdown() {
        files.shutdown();
    }

    private String upload(String user, String name, String content) {
        return (String) files.upload(user, new MockMultipartFile("file", name, "application/octet-stream",
                content.getBytes(StandardCharsets.UTF_8))).get("attachmentId");
    }

    private Map<String, Object> awaitParsed(String user, String id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        Map<String, Object> status = files.status(user, id);
        while ("PENDING".equals(status.get("parseStatus")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            status = files.status(user, id);
        }
        return status;
    }

    private static ChatFileInput file(String id) {
        return ChatFileInput.builder().attachmentId(id).build();
    }

    private JsonNode read(String user, String session, String args) throws Exception {
        return json.readTree(files.read(Map.of("userId", user, "sessionId", session, "agent.run_id", "run"), args));
    }

    private long storedObjects() throws Exception {
        try (Stream<Path> walk = Files.walk(storeRoot)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    @Test
    public void uploadIsParsedInBackgroundAndFollowsTheConversationItIsSentIn() throws Exception {
        String id = upload("alice", "notes.md", "# 周报\n第二行\n");
        Map<String, Object> status = awaitParsed("alice", id);
        assertEquals("READY", status.get("parseStatus"));
        assertEquals("PENDING", status.get("status"));
        assertEquals("共 2 行", status.get("summary"));

        List<ChatFileRef> refs = files.bindToRun("alice", "default:alice:s1", "s1", "run-1", List.of(file(id)));
        assertEquals(1, refs.size());
        assertEquals("ACTIVE", refs.get(0).getStatus());
        assertTrue(refs.get(0).getInlineText().contains("# 周报"));
        assertEquals(id, files.forRun("alice", "run-1").get(0).getAttachmentId());
        assertTrue(files.sessionHasFiles("alice", "s1"));
        assertFalse(files.sessionHasFiles("alice", "s2"));

        // Retrying the same message in the same conversation reuses it; another conversation cannot.
        assertEquals(1, files.bindToRun("alice", "default:alice:s1", "s1", "run-2", List.of(file(id))).size());
        assertRejected("属于其他对话", () -> files.bindToRun("alice", "default:alice:s2", "s2", "run-3", List.of(file(id))));
        assertRejected("不存在或已过期", () -> files.bindToRun("bob", "default:bob:s1", "s1", "run-4", List.of(file(id))));
        assertEquals(List.of(), files.copyRun("bob", "run-1", "run-5"));
        assertEquals(1, files.copyRun("alice", "run-1", "run-6").size());
    }

    @Test
    public void readAttachmentReturnsRowsWithColumnsAndSearchesOnlyInsideTheConversation() throws Exception {
        StringBuilder csv = new StringBuilder("编号,城市,金额\n");
        for (int i = 1; i <= 300; i++) csv.append("row-").append(i).append(',').append(i % 2 == 0 ? "上海" : "北京").append(',').append(i * 10).append('\n');
        String id = upload("alice", "sales.csv", csv.toString());
        assertEquals("READY", awaitParsed("alice", id).get("parseStatus"));
        files.bindToRun("alice", "default:alice:s1", "s1", "run-1", List.of(file(id)));

        JsonNode page = read("alice", "s1", "{\"attachmentId\":\"" + id + "\",\"start\":101,\"count\":3}");
        assertTrue(page.toString(), page.path("ok").asBoolean());
        assertEquals("编号", page.path("columns").get(0).asText());
        assertEquals(301, page.path("totalRows").asInt());
        assertEquals(101, page.path("rows").get(0).path("row").asInt());
        assertEquals("row-100", page.path("rows").get(0).path("cells").get(0).asText());
        assertEquals(104, page.path("next").asInt());
        assertTrue(page.path("notice").asText().contains("只作为数据使用"));

        JsonNode found = read("alice", "s1", "{\"attachmentId\":\"" + id + "\",\"query\":\"row-250,\"}");
        assertEquals(0, found.path("matches").size());
        found = read("alice", "s1", "{\"attachmentId\":\"" + id + "\",\"query\":\"ROW-250\"}");
        assertEquals("matches are reported with spreadsheet row numbers", 251, found.path("matches").get(0).path("row").asInt());

        assertEquals("ATTACHMENT_NOT_FOUND", read("bob", "s1", "{\"attachmentId\":\"" + id + "\"}").path("error").asText());
        assertEquals("ATTACHMENT_NOT_FOUND", read("alice", "s2", "{\"attachmentId\":\"" + id + "\"}").path("error").asText());
        JsonNode badSheet = read("alice", "s1", "{\"attachmentId\":\"" + id + "\",\"sheet\":\"不存在\"}");
        assertEquals("INVALID_ARGUMENTS", badSheet.path("error").asText());
    }

    @Test
    public void artifactsCanOnlyBeReferencedByTheirOwnerAndKeepTheirRevision() throws Exception {
        db.update("INSERT INTO ai_run_artifact VALUES('art-1','alice','run-0','周报','MARKDOWN',1,'agent-w')");
        db.update("INSERT INTO ai_agent VALUES('agent-w','周报助手')");
        db.update("INSERT INTO ai_run_artifact_revision VALUES('art-1',1,'周报','MARKDOWN',?)", "{\"markdown\":\"# 周报\\n## 第二节\\n简略\"}");
        ChatFileInput ref = ChatFileInput.builder().artifactId("art-1").build();
        assertRejected("产物不存在或无权引用", () -> files.bindToRun("bob", "default:bob:s1", "s1", "run-b", List.of(ref)));

        ChatFileRef bound = files.bindToRun("alice", "default:alice:s1", "s1", "run-1", List.of(ref)).get(0);
        assertEquals("ARTIFACT", bound.getKind());
        assertEquals(Integer.valueOf(1), bound.getArtifactRevision());
        assertEquals("MARKDOWN", bound.getArtifactFormat());
        assertEquals("the router learns which agent made it", "agent-w", bound.getSourceAgentId());
        assertEquals("周报助手", bound.getSourceAgentName());
        assertTrue(bound.getInlineText().contains("## 第二节"));

        db.update("INSERT INTO ai_run_artifact_revision VALUES('art-1',2,'周报','MARKDOWN',?)", "{\"markdown\":\"# 周报\\n## 第二节\\n详细\"}");
        db.update("UPDATE ai_run_artifact SET current_revision=2 WHERE artifact_id='art-1'");
        JsonNode referenced = read("alice", "s1", "{\"attachmentId\":\"" + bound.getAttachmentId() + "\"}");
        assertEquals(1, referenced.path("revision").asInt());
        assertTrue(referenced.path("content").asText().contains("简略"));
        JsonNode latest = read("alice", "s1", "{\"attachmentId\":\"" + bound.getAttachmentId() + "\",\"latest\":true}");
        assertEquals(2, latest.path("revision").asInt());
        assertTrue(latest.path("content").asText().contains("详细"));
    }

    @Test
    public void limitsAndTypesAreEnforced() {
        List<ChatFileInput> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) eleven.add(file("f_" + i));
        assertRejected("最多添加 10 个", () -> files.bindToRun("alice", "c", "s1", "run-1", eleven));
        assertRejected("图片", () -> upload("alice", "photo.png", "png"));
        assertRejected("暂不支持", () -> upload("alice", "setup.exe", "MZ"));
        assertRejected("不能超过 1 MB", () -> upload("alice", "big.txt", "x".repeat(1024 * 1024 + 1)));
        assertRejected("为空", () -> files.upload("alice", new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0])));
    }

    @Test
    public void sessionIndexIncludesAllSentFilesButNoBodiesOrOtherOwnersOrSessions() throws Exception {
        String old = upload("alice", "old.md", "old private body");
        String otherSession = upload("alice", "other.md", "other session");
        String otherUser = upload("bob", "bob.md", "bob body");
        String unsent = upload("alice", "unsent.md", "not sent yet");
        for (String id : List.of(old, otherSession, unsent)) awaitParsed("alice", id);
        awaitParsed("bob", otherUser);
        files.bindToRun("alice", "default:alice:s1", "s1", "first-run", List.of(file(old)));
        files.bindToRun("alice", "default:alice:s2", "s2", "another-run", List.of(file(otherSession)));
        files.bindToRun("bob", "default:bob:s1", "s1", "bob-run", List.of(file(otherUser)));
        assertEquals(List.of(), files.forRun("alice", "thirtieth-run"));
        List<ChatFileRef> index = files.sessionIndex("alice", "s1");
        assertEquals(List.of(old), index.stream().map(ChatFileRef::getAttachmentId).toList());
        assertEquals("old.md", index.get(0).getFileName());
        assertNull(index.get(0).getInlineText());
        assertNull(index.get(0).getPreviewText());
        assertEquals(List.of(), files.sessionIndex("alice", ""));
        // Cleaned-up files remain explainable, rather than silently disappearing or being advertised as readable.
        db.update("UPDATE ai_chat_file SET status='PURGED' WHERE attachment_id=?", old);
        assertEquals("PURGED", files.sessionIndex("alice", "s1").get(0).getStatus());
        files.deleteByConversation("default:alice:s1", "alice");
        assertTrue(files.sessionIndex("alice", "s1").isEmpty());
    }

    @Test
    public void sameContentIsStoredOnceAndTheCacheCanBeRebuilt() throws Exception {
        String first = upload("alice", "a.txt", "alpha\nbeta\n");
        assertEquals("READY", awaitParsed("alice", first).get("parseStatus"));
        String second = upload("alice", "copy.txt", "alpha\nbeta\n");
        assertEquals("the parse result is reused right away", "READY", files.status("alice", second).get("parseStatus"));
        assertEquals(1, storedObjects());

        files.bindToRun("alice", "default:alice:s1", "s1", "run-1", List.of(file(first)));
        try (Stream<Path> walk = Files.walk(cacheRoot)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) if (!path.equals(cacheRoot)) Files.delete(path);
        }
        JsonNode page = read("alice", "s1", "{\"attachmentId\":\"" + first + "\",\"start\":2,\"count\":1}");
        assertTrue(page.toString(), page.path("content").asText().contains("2| beta"));
    }

    @Test
    public void platformCleanupPurgesLeastRecentlyUsedAndKeepsMetadata() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = upload("alice", "f" + i + ".txt", ("file " + i + "\n").repeat(20));
            awaitParsed("alice", id);
            ids.add(id);
        }
        files.bindToRun("alice", "default:alice:s1", "s1", "run-1", ids.stream().map(ChatFileServiceTest::file).toList());
        db.update("UPDATE ai_chat_file SET last_used_at=TIMESTAMP '2020-01-01 00:00:00' WHERE attachment_id=?", ids.get(0));
        long total = files.storedBytes();
        assertEquals(1, files.purgeLeastRecentlyUsed(total - 1));
        assertEquals("PURGED", files.describe("alice", List.of(ids.get(0))).get(0).getStatus());
        assertEquals("ACTIVE", files.describe("alice", List.of(ids.get(1))).get(0).getStatus());
        assertEquals("ATTACHMENT_PURGED", read("alice", "s1", "{\"attachmentId\":\"" + ids.get(0) + "\"}").path("error").asText());
        assertEquals(2, storedObjects());
        assertRejected("已被清理", () -> files.bindToRun("alice", "default:alice:s1", "s1", "run-2", List.of(file(ids.get(0)))));
    }

    @Test
    public void deletingTheConversationOrTheAccountRemovesOriginalsAndCaches() throws Exception {
        String id = upload("alice", "a.txt", "alpha\n");
        awaitParsed("alice", id);
        files.bindToRun("alice", "default:alice:s1", "s1", "run-1", List.of(file(id)));
        String other = upload("alice", "b.txt", "beta\n");
        awaitParsed("alice", other);
        assertEquals(1, files.deleteByConversation("default:alice:s1", "alice"));
        assertEquals(1, storedObjects());
        assertEquals(0, (int) db.queryForObject("SELECT COUNT(*) FROM ai_chat_file_run", Integer.class));
        assertEquals(1, files.deleteByUser("alice"));
        assertEquals(0, storedObjects());
        assertFalse(Files.exists(cacheRoot.resolve(ChatFileService.ownerKey("alice"))));
    }

    @Test
    public void unsentUploadsExpire() throws Exception {
        String id = upload("alice", "a.txt", "alpha\n");
        awaitParsed("alice", id);
        assertEquals(0, files.deleteExpiredPending(Duration.ofHours(24)));
        db.update("UPDATE ai_chat_file SET created_at=TIMESTAMP '2020-01-01 00:00:00' WHERE attachment_id=?", id);
        assertEquals(1, files.deleteExpiredPending(Duration.ofHours(24)));
        assertEquals(0, storedObjects());
    }

    private static void assertRejected(String message, Runnable action) {
        try {
            action.run();
            fail("expected rejection containing " + message);
        } catch (ChatFileService.Rejected rejected) {
            assertTrue(rejected.getMessage(), rejected.getMessage().contains(message));
        }
    }
}
