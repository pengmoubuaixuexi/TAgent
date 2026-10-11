package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.trigger.workspace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class WorkspaceVersionTest {
    private final ObjectMapper json=new ObjectMapper();
    private JdbcTemplate db;
    private WorkspaceService workspace;
    private WorkspaceVersionService service;
    private WorkspaceVersionStore store;
    @Before public void setup() {
        db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE admin_user(user_id VARCHAR PRIMARY KEY,status INT)");
        db.execute("CREATE TABLE ai_agent(agent_id VARCHAR PRIMARY KEY,owner_user_id VARCHAR,archived INT,workspace_version INT,workspace_config CLOB,source_agent_id VARCHAR)");
        db.execute("CREATE TABLE ai_agent_version(agent_id VARCHAR,version INT,owner_user_id VARCHAR,config_json CLOB,config_hash VARCHAR,note VARCHAR,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(agent_id,version))");
        db.execute("CREATE TABLE ai_agent_draft(agent_id VARCHAR PRIMARY KEY,owner_user_id VARCHAR,base_version INT,revision INT,config_json CLOB,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        db.update("INSERT INTO admin_user VALUES('alice',1),('bob',1)");
        db.update("INSERT INTO ai_agent VALUES('agent','alice',0,1,?,NULL)",config("initial").toString());
        workspace=mock(WorkspaceService.class);
        when(workspace.validateAgent(eq("alice"),any())).thenAnswer(call->{ObjectNode c=((ObjectNode)call.getArgument(1)).deepCopy();c.remove(List.of("version","draftRevision"));return c;});
        store=new WorkspaceVersionStore(db); store.record("alice","agent",1,config("initial").toString(),"initial");
        when(workspace.saveAgent(eq("alice"),eq("agent"),any())).thenAnswer(call->{
            ObjectNode c=((ObjectNode)call.getArgument(2)).deepCopy();int version=c.remove("version").asInt()+1;
            db.update("UPDATE ai_agent SET workspace_version=?,workspace_config=? WHERE agent_id='agent'",version,c.toString());
            store.record("alice","agent",version,c.toString(),"activated");
            return Map.of("version",version,"agentId","agent");
        });
        service=new WorkspaceVersionService(db,json,workspace);
    }
    private ObjectNode config(String name) { return json.createObjectNode().put("name",name).put("strategy","fixed").put("version",1); }
    @Test public void draftIsIndependentAndPublishCreatesImmutableVersion() {
        var draft=service.saveDraft("alice","agent",config("draft"));
        assertEquals(1,draft.get("revision"));
        assertEquals(1,(int)db.queryForObject("SELECT workspace_version FROM ai_agent",Integer.class));
        verify(workspace,never()).saveAgent(anyString(),anyString(),any());
        var published=service.publish("alice","agent",json.createObjectNode().put("baseVersion",1).put("draftRevision",1));
        assertEquals(2,published.get("version"));
        assertEquals("initial",((com.fasterxml.jackson.databind.JsonNode)service.version("alice","agent",1).get("config")).path("name").asText());
        assertEquals(0,service.draft("alice","agent").get("revision"));
        assertEquals(2,((List<?>)service.versions("alice","agent")).size());
    }
    @Test public void ownersAndOptimisticDraftRevisionCannotBeBypassed() {
        assertThrows(ResponseStatusException.class,()->service.versions("bob","agent"));
        assertThrows(ResponseStatusException.class,()->service.saveDraft("bob","agent",config("attack")));
        service.saveDraft("alice","agent",config("one"));
        assertThrows(ResponseStatusException.class,()->service.saveDraft("alice","agent",config("stale")));
        assertThrows(ResponseStatusException.class,()->service.publish("alice","agent",json.createObjectNode().put("baseVersion",1).put("draftRevision",99)));
        var revised=service.saveDraft("alice","agent",config("two").put("draftRevision",1));
        assertEquals(2,revised.get("revision"));
    }
    @Test public void restoreOnlyCreatesDraftAndResourcesAreRevalidatedOnActivation() {
        service.saveDraft("alice","agent",config("changed"));
        service.publish("alice","agent",json.createObjectNode().put("baseVersion",1).put("draftRevision",1));
        var restored=service.restore("alice","agent",1,json.createObjectNode().put("baseVersion",2));
        assertEquals("initial",((com.fasterxml.jackson.databind.JsonNode)restored.get("config")).path("name").asText());
        assertEquals(2,(int)db.queryForObject("SELECT workspace_version FROM ai_agent",Integer.class));
        assertFalse(((List<?>)service.compare("alice","agent",1,2)).isEmpty());
        when(workspace.saveAgent(eq("alice"),eq("agent"),any())).thenThrow(new IllegalArgumentException("model revoked"));
        assertThrows(IllegalArgumentException.class,()->service.publish("alice","agent",json.createObjectNode().put("baseVersion",2).put("draftRevision",1)));
        assertEquals(1,service.draft("alice","agent").get("revision"));
    }
}
