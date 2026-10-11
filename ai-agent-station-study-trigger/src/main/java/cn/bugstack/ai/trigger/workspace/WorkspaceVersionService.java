package cn.bugstack.ai.trigger.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class WorkspaceVersionService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final WorkspaceService workspace;
    public WorkspaceVersionService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate db,ObjectMapper json,WorkspaceService workspace) {
        this.db=db; this.json=json; this.workspace=workspace;
    }
    private Map<String,Object> owned(String user,String id,boolean lock) {
        var rows=db.queryForList("SELECT workspace_version,workspace_config,source_agent_id FROM ai_agent WHERE agent_id=? AND owner_user_id=? AND archived=0"+(lock?" FOR UPDATE":""),id,user);
        if(rows.isEmpty()) throw WorkspaceService.notFound();
        var row=rows.get(0);
        if(row.get("workspace_config")==null || row.get("source_agent_id")!=null) throw WorkspaceService.forbidden("请先从模板创建可编辑的个人 Agent");
        return row;
    }
    private void lockUser(String user) {
        if(db.queryForList("SELECT user_id FROM admin_user WHERE user_id=? AND status=1 FOR UPDATE",String.class,user).isEmpty()) throw WorkspaceService.forbidden("账户不可用");
    }
    public Object versions(String user,String id) {
        owned(user,id,false);
        return db.queryForList("SELECT version,created_at AS createdAt,note,config_hash AS configHash FROM ai_agent_version WHERE agent_id=? AND owner_user_id=? ORDER BY version DESC",id,user);
    }
    public Map<String,Object> version(String user,String id,int version) {
        owned(user,id,false);
        var rows=db.queryForList("SELECT config_json,config_hash FROM ai_agent_version WHERE agent_id=? AND owner_user_id=? AND version=?",id,user,version);
        if(rows.isEmpty()) throw WorkspaceService.notFound();
        return Map.of("version",version,"config",parse(rows.get(0).get("config_json")),"configHash",rows.get(0).get("config_hash"));
    }
    public Map<String,Object> draft(String user,String id) {
        var active=owned(user,id,false);
        var rows=db.queryForList("SELECT * FROM ai_agent_draft WHERE agent_id=? AND owner_user_id=?",id,user);
        if(rows.isEmpty()) return Map.of("config",parse(active.get("workspace_config")),"baseVersion",active.get("workspace_version"),"revision",0);
        var row=rows.get(0);
        return Map.of("config",parse(row.get("config_json")),"baseVersion",row.get("base_version"),"revision",row.get("revision"));
    }
    @Transactional public Map<String,Object> saveDraft(String user,String id,JsonNode input) {
        lockUser(user);
        var active=owned(user,id,true);
        int base=WorkspaceService.requiredInt(input,"version",1,Integer.MAX_VALUE);
        if(base!=((Number)active.get("workspace_version")).intValue()) throw WorkspaceService.conflict("已启用的版本发生变化，请重新加载后保存草稿");
        ObjectNode clean=workspace.validateAgent(user,input);
        int expected=input.has("draftRevision")?WorkspaceService.requiredInt(input,"draftRevision",0,Integer.MAX_VALUE):0;
        var revisions=db.queryForList("SELECT revision FROM ai_agent_draft WHERE agent_id=?",Integer.class,id);
        if(revisions.isEmpty()) {
            if(expected!=0) throw WorkspaceService.conflict("草稿已变化，请重新加载");
            db.update("INSERT INTO ai_agent_draft(agent_id,owner_user_id,base_version,revision,config_json) VALUES(?,?,?,1,?)",id,user,base,clean.toString());
        } else if(db.update("UPDATE ai_agent_draft SET config_json=?,base_version=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE agent_id=? AND owner_user_id=? AND revision=?",
                clean.toString(),base,id,user,expected)!=1) throw WorkspaceService.conflict("草稿已被另一个页面修改，请重新加载");
        return draft(user,id);
    }
    @Transactional public Map<String,Object> publish(String user,String id,JsonNode input) {
        lockUser(user); var active=owned(user,id,true);
        int base=WorkspaceService.requiredInt(input,"baseVersion",1,Integer.MAX_VALUE);
        int revision=WorkspaceService.requiredInt(input,"draftRevision",1,Integer.MAX_VALUE);
        var rows=db.queryForList("SELECT config_json FROM ai_agent_draft WHERE agent_id=? AND owner_user_id=? AND revision=? AND base_version=?",id,user,revision,base);
        if(rows.isEmpty() || base!=((Number)active.get("workspace_version")).intValue()) throw WorkspaceService.conflict("草稿或已启用版本发生变化，请重新加载");
        ObjectNode config=(ObjectNode)parse(rows.get(0).get("config_json"));
        config.put("version",base);
        // Revalidate current permissions and credentials at activation, never trust an old draft.
        Map<String,Object> saved=workspace.saveAgent(user,id,config);
        db.update("DELETE FROM ai_agent_draft WHERE agent_id=? AND owner_user_id=? AND revision=?",id,user,revision);
        return saved;
    }
    @Transactional public Map<String,Object> restore(String user,String id,int version,JsonNode input) {
        lockUser(user); owned(user,id,true);
        ObjectNode config=((ObjectNode)version(user,id,version).get("config")).deepCopy();
        config.put("version",WorkspaceService.requiredInt(input,"baseVersion",1,Integer.MAX_VALUE));
        config.put("draftRevision",input.path("draftRevision").asInt(0));
        return saveDraft(user,id,config); // Restoration is a draft; activation is explicit.
    }
    public Object compare(String user,String id,int from,int to) {
        JsonNode before=(JsonNode)version(user,id,from).get("config"),after=(JsonNode)version(user,id,to).get("config");
        List<Map<String,Object>> changes=new ArrayList<>(); diff("",before,after,changes); return changes;
    }
    private void diff(String path,JsonNode a,JsonNode b,List<Map<String,Object>> changes) {
        if(Objects.equals(a,b)) return;
        if(a!=null && b!=null && a.isObject() && b.isObject()) {
            Set<String> keys=new TreeSet<>();a.fieldNames().forEachRemaining(keys::add);b.fieldNames().forEachRemaining(keys::add);
            for(String key:keys) diff(path+"/"+key.replace("~","~0").replace("/","~1"),a.get(key),b.get(key),changes);
        } else changes.add(Map.of("path",path,"before",a==null?json.nullNode():a,"after",b==null?json.nullNode():b));
    }
    private JsonNode parse(Object text) {
        try { return json.readTree(text.toString()); } catch(Exception e) { throw WorkspaceService.bad("版本配置损坏，请联系管理员"); }
    }
}
