package cn.bugstack.ai.trigger.workspace;

import cn.bugstack.ai.domain.agent.adapter.repository.IRunHistoryRepository;
import cn.bugstack.ai.trigger.eval.EvalCaseConfig;
import cn.bugstack.ai.trigger.eval.EvalDatasetService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** Personal outputs and opt-in evaluation drafts. Run provenance never comes from request fields. */
@Service
public class WorkspaceOutcomeService {
    private final JdbcTemplate db;
    private final IRunHistoryRepository history;
    private final EvalDatasetService datasets;
    private final ObjectMapper json;
    private static final int MAX_CONTENT_CHARS=200_000;
    private static final Set<String> CODE_EXTENSIONS=Set.of("py","java","js","mjs","cjs","ts","jsx","tsx","html","htm","css","scss","sass","less",
            "json","jsonl","yaml","yml","xml","sql","sh","bash","zsh","ps1","bat","cmd","c","h","cc","cpp","cxx","hpp","cs","go","rs",
            "rb","php","kt","kts","swift","scala","r","jl","lua","pl","dart","vue","svelte","md","txt","toml","ini","conf","cfg","properties",
            "graphql","gql","proto","ipynb","tex","dockerfile","gradle","groovy","ex","exs","erl","hrl","hs","fs","fsx","clj","cljs","cljc","edn",
            "sln","csproj","vb","vbs","asm","s","zig","tf","hcl","env","gitignore","gitattributes","editorconfig");
    public WorkspaceOutcomeService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate db,IRunHistoryRepository history,
                                   EvalDatasetService datasets,ObjectMapper json) {
        this.db=db;this.history=history;this.datasets=datasets;this.json=json;
    }

    public List<Map<String,Object>> list(String user,String runId) {
        if(runId!=null&&!runId.isBlank()) ownedRun(user,runId);
        return db.query("SELECT * FROM ai_run_artifact WHERE owner_user_id=?"+
                (runId==null||runId.isBlank()?"":" AND run_id=?")+" ORDER BY updated_at DESC,artifact_id LIMIT 100",
                (rs,n)->head(rs),runId==null||runId.isBlank()?new Object[]{user}:new Object[]{user,runId});
    }
    public List<Map<String,Object>> revisions(String user,String id) {
        ownedHead(user,id,false);
        return db.query("SELECT revision,title,format,content_hash,created_at FROM ai_run_artifact_revision WHERE artifact_id=? ORDER BY revision DESC",
                (rs,n)->Map.<String,Object>of("revision",rs.getInt("revision"),"title",rs.getString("title"),"format",rs.getString("format"),
                        "contentHash",rs.getString("content_hash"),"createdAt",rs.getTimestamp("created_at").toString()),id);
    }
    public Map<String,Object> artifact(String user,String id,int revision) {
        Map<String,Object> result=ownedHead(user,id,false);
        int chosen=revision==0?((Number)result.get("currentRevision")).intValue():revision;
        var rows=db.query("SELECT * FROM ai_run_artifact_revision WHERE artifact_id=? AND revision=?",(rs,n)->{
            Map<String,Object> version=new LinkedHashMap<String,Object>();version.put("revision",rs.getInt("revision"));
            version.put("title",rs.getString("title"));version.put("format",rs.getString("format"));
            version.put("content",read(rs.getString("content_json")));version.put("contentHash",rs.getString("content_hash"));
            version.put("versionCreatedAt",rs.getTimestamp("created_at").toString());return version;
        },id,chosen);
        if(rows.isEmpty())throw WorkspaceService.notFound();result.putAll(rows.get(0));return result;
    }
    @Transactional
    public Map<String,Object> save(String user,String id,JsonNode input) {
        lockUser(user);
        exactFields(input,Set.of("runId","title","format","content","revision"));
        String title=WorkspaceService.text(input,"title",1,160),format=WorkspaceService.text(input,"format",1,16);
        JsonNode content=validateContent(format,input.get("content"));String encoded=content.toString();
        long bytes=db.queryForObject("SELECT COALESCE(SUM(OCTET_LENGTH(r.content_json)),0) FROM ai_run_artifact_revision r JOIN ai_run_artifact a ON a.artifact_id=r.artifact_id WHERE a.owner_user_id=?",Long.class,user);
        if(bytes+encoded.getBytes(StandardCharsets.UTF_8).length>20L*1024*1024)throw WorkspaceService.bad("个人产物版本总大小已达到 20 MiB 上限");
        int revision;
        if(id==null) {
            if(db.queryForObject("SELECT COUNT(*) FROM ai_run_artifact WHERE owner_user_id=?",Integer.class,user)>=100)throw WorkspaceService.bad("最多保存 100 个个人产物");
            String runId=WorkspaceService.text(input,"runId",1,128);Map<String,Object> run=ownedRun(user,runId);
            id=UUID.randomUUID().toString();revision=1;
            db.update("INSERT INTO ai_run_artifact(artifact_id,owner_user_id,run_id,agent_id,agent_version,title,format,current_revision) VALUES(?,?,?,?,?,?,?,?)",
                    id,user,runId,run.get("agentId"),run.get("agentVersion"),title,format,revision);
        } else {
            Map<String,Object> current=ownedHead(user,id,true);
            int expected=WorkspaceService.requiredInt(input,"revision",1,Integer.MAX_VALUE-1);
            if(expected>=100)throw WorkspaceService.bad("每个产物最多保留 100 个版本");
            if(expected!=((Number)current.get("currentRevision")).intValue())throw WorkspaceService.conflict("产物已有新版本，请重新读取后编辑");
            if(input.has("runId")&&!Objects.equals(current.get("runId"),input.path("runId").asText()))throw WorkspaceService.bad("不能修改产物的来源运行");
            revision=expected+1;
            db.update("UPDATE ai_run_artifact SET title=?,format=?,current_revision=?,updated_at=CURRENT_TIMESTAMP(3) WHERE artifact_id=? AND owner_user_id=? AND current_revision=?",
                    title,format,revision,id,user,expected);
        }
        db.update("INSERT INTO ai_run_artifact_revision(artifact_id,revision,title,format,content_json,content_hash) VALUES(?,?,?,?,?,?)",
                id,revision,title,format,encoded,WorkspaceVersionStore.hash(encoded));
        return artifact(user,id,revision);
    }

    /** The caller supplies verified runtime identity; model arguments contain output content only. */
    @Transactional
    public Map<String,Object> createFromTool(String user,String runId,String toolCallId,JsonNode input) {
        if(toolCallId==null||toolCallId.isBlank()||toolCallId.length()>256)throw WorkspaceService.bad("缺少有效工具调用标识");
        exactFields(input,Set.of("title","format","content"));
        String title=WorkspaceService.text(input,"title",1,160),format=WorkspaceService.text(input,"format",1,16);
        JsonNode content=validateContent(format,input.get("content"));
        // Canonical field order makes object-key reordering equivalent for retry/deduplication.
        String encoded=canonicalContent(format,content).toString(),hash=WorkspaceVersionStore.hash(encoded);
        lockUser(user);
        Map<String,Object> run=ownedRun(user,runId);
        if(!"RUNNING".equals(run.get("status")))throw WorkspaceService.conflict("只有当前正在执行的任务可以自动创建产物");
        String id=WorkspaceVersionStore.hash(json.createArrayNode().add("artifact-tool-v1").add(user).add(runId).add(toolCallId).toString());
        var existing=db.query("SELECT * FROM ai_run_artifact WHERE artifact_id=? AND owner_user_id=? FOR UPDATE",(rs,n)->head(rs),id,user);
        if(!existing.isEmpty()) {
            Map<String,Object> original=artifact(user,id,1);
            if(!Objects.equals(original.get("title"),title)||!Objects.equals(original.get("format"),format)
                    ||!Objects.equals(original.get("contentHash"),hash))throw WorkspaceService.conflict("同一次工具调用已保存不同内容，请使用新的调用创建新产物");
            original.put("created",false);return original;
        }
        // A model can emit a new call ID after a stream retry. Exact outputs within this run are one artifact.
        var duplicates=db.queryForList("SELECT a.artifact_id FROM ai_run_artifact a JOIN ai_run_artifact_revision r ON r.artifact_id=a.artifact_id AND r.revision=1 WHERE a.owner_user_id=? AND a.run_id=? AND r.title=? AND r.format=? AND r.content_hash=? ORDER BY a.created_at,a.artifact_id LIMIT 1",
                String.class,user,runId,title,format,hash);
        if(!duplicates.isEmpty()) {
            Map<String,Object> duplicate=artifact(user,duplicates.get(0),1);
            // MySQL title collations may be case-insensitive; deduplication is exact, not linguistic.
            if(Objects.equals(duplicate.get("title"),title)&&Objects.equals(duplicate.get("format"),format)) {
                duplicate.put("created",false);return duplicate;
            }
        }
        if(db.queryForObject("SELECT COUNT(*) FROM ai_run_artifact WHERE owner_user_id=?",Integer.class,user)>=100)
            throw WorkspaceService.bad("最多保存 100 个个人产物");
        long bytes=db.queryForObject("SELECT COALESCE(SUM(OCTET_LENGTH(r.content_json)),0) FROM ai_run_artifact_revision r JOIN ai_run_artifact a ON a.artifact_id=r.artifact_id WHERE a.owner_user_id=?",Long.class,user);
        if(bytes+encoded.getBytes(StandardCharsets.UTF_8).length>20L*1024*1024)
            throw WorkspaceService.bad("个人产物版本总大小已达到 20 MiB 上限");
        db.update("INSERT INTO ai_run_artifact(artifact_id,owner_user_id,run_id,agent_id,agent_version,title,format,current_revision) VALUES(?,?,?,?,?,?,?,1)",
                id,user,runId,run.get("agentId"),run.get("agentVersion"),title,format);
        db.update("INSERT INTO ai_run_artifact_revision(artifact_id,revision,title,format,content_json,content_hash) VALUES(?,1,?,?,?,?)",
                id,title,format,encoded,hash);
        Map<String,Object> saved=artifact(user,id,1);saved.put("created",true);return saved;
    }

    private JsonNode canonicalContent(String format,JsonNode content) {
        var canonical=json.createObjectNode();
        if("MARKDOWN".equals(format))canonical.set("markdown",content.get("markdown"));
        else if("CODE".equals(format)) {
            canonical.set("filename",content.get("filename"));canonical.set("language",content.get("language"));canonical.set("code",content.get("code"));
        }
        else {canonical.set("columns",content.get("columns"));canonical.set("rows",content.get("rows"));}
        return canonical;
    }

    public Map<String,Object> feedback(String user,String runId) {
        ownedRun(user,runId);return feedbackRow(user,runId,false).orElseGet(LinkedHashMap::new);
    }
    @Transactional
    public Map<String,Object> saveFeedback(String user,String runId,JsonNode input) {
        lockUser(user);
        exactFields(input,Set.of("revision","rating","comment","correctedAnswer","createEvalDraft"));
        Map<String,Object> run=ownedRun(user,runId);
        int expected=WorkspaceService.requiredInt(input,"revision",0,Integer.MAX_VALUE-1);
        String rating=WorkspaceService.text(input,"rating",1,16);
        if(!Set.of("HELPFUL","NOT_HELPFUL").contains(rating))throw WorkspaceService.bad("请选择有帮助或需改进");
        String comment=WorkspaceService.optionalText(input,"comment",4000),answer=WorkspaceService.optionalText(input,"correctedAnswer",20000);
        if(input.has("createEvalDraft")&&!input.path("createEvalDraft").isBoolean())throw WorkspaceService.bad("评测草稿选项必须为布尔值");
        var old=feedbackRow(user,runId,true).orElse(null);
        if(old==null) {
            if(expected!=0)throw WorkspaceService.conflict("反馈版本已变化，请重新加载");
            try {db.update("INSERT INTO ai_run_feedback(owner_user_id,run_id,agent_id,agent_version,revision,rating,comment_text,corrected_answer) VALUES(?,?,?,?,?,?,?,?)",
                    user,runId,run.get("agentId"),run.get("agentVersion"),1,rating,comment,answer);}
            catch(DuplicateKeyException conflict){throw WorkspaceService.conflict("反馈已保存，请重新加载");}
        } else {
            if(expected!=((Number)old.get("revision")).intValue())throw WorkspaceService.conflict("反馈版本已变化，请重新加载");
            db.update("UPDATE ai_run_feedback SET revision=revision+1,rating=?,comment_text=?,corrected_answer=?,updated_at=CURRENT_TIMESTAMP(3) WHERE owner_user_id=? AND run_id=? AND revision=?",
                    rating,comment,answer,user,runId,expected);
        }
        // An existing promoted case is an independent draft: subsequent feedback edits never rewrite it.
        if(input.path("createEvalDraft").asBoolean(false)&&(old==null||old.get("evalDatasetId")==null)) {
            String question=Objects.toString(run.get("question"),"");
            if(question.isBlank()||question.length()>20000)throw WorkspaceService.bad("原始问题为空或过长，不能转为评测草稿");
            Map<String,Object> dataset=datasets.createDataset("我的反馈回归 · "+runId.substring(0,Math.min(12,runId.length())),
                    "个人运行反馈，尚未发布。来源运行："+runId+"；Agent 版本："+Objects.toString(run.get("agentVersion"),"未知")+"。反馈："+comment,"INDEPENDENT",user);
            String datasetId=String.valueOf(dataset.get("datasetId"));
            var command=new EvalDatasetService.CaseCommand();command.setStableKey("feedback-"+WorkspaceVersionStore.hash(runId));
            command.setQuestion(question);command.setReferenceAnswer(answer.isBlank()?null:answer);
            command.setCategory("personal-feedback");command.setTags(List.of(rating));command.setConfig(new EvalCaseConfig().normalized());
            command.setEnabled(false); // User must review and enable the case before publishing/running it.
            Map<String,Object> saved=datasets.saveCase(datasetId,null,command);
            db.update("UPDATE ai_run_feedback SET eval_dataset_id=?,eval_case_id=? WHERE owner_user_id=? AND run_id=?",
                    datasetId,saved.get("caseId"),user,runId);
        }
        return feedbackRow(user,runId,false).orElseThrow(WorkspaceService::notFound);
    }

    public record Export(String filename,String contentType,byte[] bytes) {}
    public Export export(String user,String id,int revision) {
        Map<String,Object> artifact=artifact(user,id,revision);JsonNode content=(JsonNode)artifact.get("content");
        if("CODE".equals(artifact.get("format"))) {
            // Code is downloadable source, never executable content served by the application.
            validateContent("CODE",content);
            return new Export(content.path("filename").asText(),"application/octet-stream",content.path("code").asText().getBytes(StandardCharsets.UTF_8));
        }
        boolean table="TABLE".equals(artifact.get("format"));String text;
        if(table) {
            StringBuilder csv=new StringBuilder("\uFEFF");csvRow(csv,content.path("columns"));
            for(JsonNode row:content.path("rows"))csvRow(csv,row);text=csv.toString();
        } else text=content.path("markdown").asText();
        return new Export("artifact-"+id+"-v"+artifact.get("revision")+(table?".csv":".md"),
                table?"text/csv;charset=UTF-8":"text/markdown;charset=UTF-8",text.getBytes(StandardCharsets.UTF_8));
    }
    static void csvRow(StringBuilder output,JsonNode values) {
        boolean first=true;for(JsonNode value:values) {
            if(!first)output.append(',');first=false;String text=value.asText();String probe=text.stripLeading();
            if(!probe.isEmpty()&&("=+-@".indexOf(probe.charAt(0))>=0)||(!text.isEmpty()&&(text.charAt(0)=='\t'||text.charAt(0)=='\r'||text.charAt(0)=='\n')))text="'"+text;
            output.append('"').append(text.replace("\"","\"\"")).append('"');
        }output.append("\r\n");
    }
    JsonNode validateContent(String format,JsonNode value) {
        if(value==null||!value.isObject())throw WorkspaceService.bad("content 必须为 JSON 对象；MARKDOWN 示例：{\"markdown\":\"# 标题\\n正文\"}，不要直接填写原始正文");
        if(value.toString().length()>MAX_CONTENT_CHARS)throw WorkspaceService.bad("content 编码后不能超过 200000 字符");
        if("MARKDOWN".equals(format)) {
            exactFields(value,Set.of("markdown"),"content.");
            if(!value.path("markdown").isTextual()||value.path("markdown").asText().isBlank())throw WorkspaceService.bad("content.markdown 必须为非空字符串；示例：{\"markdown\":\"# 标题\\n正文\"}");
        } else if("TABLE".equals(format)) {
            exactFields(value,Set.of("columns","rows"),"content.");JsonNode columns=value.path("columns"),rows=value.path("rows");
            if(!columns.isArray()||columns.isEmpty()||columns.size()>30)throw WorkspaceService.bad("content.columns 必须为 1–30 个表头组成的字符串数组");
            if(!rows.isArray()||rows.size()>1000)throw WorkspaceService.bad("content.rows 必须为最多 1000 行的二维字符串数组");
            for(int column=0;column<columns.size();column++) {JsonNode c=columns.get(column);if(!c.isTextual()||c.asText().isBlank()||c.asText().length()>160)throw WorkspaceService.bad("content.columns["+column+"] 必须为 1–160 字符的文本");}
            for(int rowIndex=0;rowIndex<rows.size();rowIndex++) {
                JsonNode row=rows.get(rowIndex);
                if(!row.isArray()||row.size()!=columns.size())throw WorkspaceService.bad("content.rows["+rowIndex+"] 必须为数组，单元格数量应为 "+columns.size());
                for(int column=0;column<row.size();column++){JsonNode cell=row.get(column);if(!cell.isTextual()||cell.asText().length()>4000)throw WorkspaceService.bad("content.rows["+rowIndex+"]["+column+"] 必须为最多 4000 字符的文本");}
            }
        } else if("CODE".equals(format)) {
            exactFields(value,Set.of("filename","language","code"),"content.");
            validateCodeFilename(value.path("filename"));
            if(!value.path("language").isTextual()||!value.path("language").asText().matches("[A-Za-z0-9_+#.-]{1,40}"))
                throw WorkspaceService.bad("content.language 必须为 1–40 字符的语言标识，例如 python、java、javascript、html 或 plaintext");
            if(!value.path("code").isTextual()||value.path("code").asText().isBlank())
                throw WorkspaceService.bad("content.code 必须为完整源码字符串，保留原始缩进和换行；示例：{\"filename\":\"hello.py\",\"language\":\"python\",\"code\":\"print('hello')\\n\"}");
        } else throw WorkspaceService.bad("format 只支持 MARKDOWN、TABLE 或 CODE");
        return value;
    }
    private static void validateCodeFilename(JsonNode node) {
        if(!node.isTextual())throw WorkspaceService.bad("content.filename 必须为文件名字符串，例如 hello.py");
        String name=node.asText();
        if(name.isBlank()||name.length()>160||!name.equals(name.strip())||name.endsWith(".")||name.contains("..")
                ||name.chars().anyMatch(c->Character.isISOControl(c)||"/\\<>:\"|?*".indexOf(c)>=0))
            throw WorkspaceService.bad("content.filename 必须为 1–160 字符的单个文件名，不能含路径、..、控制字符或 / \\ < > : \" | ? *");
        String lower=name.toLowerCase(Locale.ROOT),stem=lower.split("\\.",2)[0];
        if(stem.matches("con|prn|aux|nul|com[1-9]|lpt[1-9]"))throw WorkspaceService.bad("content.filename 不能使用系统保留文件名");
        int dot=lower.lastIndexOf('.');
        if(!Set.of("dockerfile","makefile","gemfile","rakefile","justfile").contains(lower)
                &&(dot<0||!CODE_EXTENSIONS.contains(lower.substring(dot+1))))
            throw WorkspaceService.bad("content.filename 需要常用源码或配置文件扩展名，例如 .py、.java、.js、.html、.sql、.json、.yaml，也支持 Dockerfile 和 Makefile");
    }
    private Map<String,Object> ownedRun(String user,String runId) {return history.findOwned(user,runId).orElseThrow(WorkspaceService::notFound);}
    private void lockUser(String user) {
        if(db.queryForList("SELECT user_id FROM admin_user WHERE user_id=? AND status=1 FOR UPDATE",String.class,user).size()!=1)
            throw WorkspaceService.forbidden("账户不可用");
    }
    private Map<String,Object> ownedHead(String user,String id,boolean lock) {
        var rows=db.query("SELECT * FROM ai_run_artifact WHERE artifact_id=? AND owner_user_id=?"+(lock?" FOR UPDATE":""),(rs,n)->head(rs),id,user);
        if(rows.isEmpty())throw WorkspaceService.notFound();return rows.get(0);
    }
    private Optional<Map<String,Object>> feedbackRow(String user,String runId,boolean lock) {
        return db.query("SELECT * FROM ai_run_feedback WHERE owner_user_id=? AND run_id=?"+(lock?" FOR UPDATE":""),(rs,n)->{
            Map<String,Object> row=new LinkedHashMap<>();row.put("runId",rs.getString("run_id"));row.put("agentId",rs.getString("agent_id"));
            row.put("agentVersion",rs.getObject("agent_version"));row.put("revision",rs.getInt("revision"));row.put("rating",rs.getString("rating"));
            row.put("comment",rs.getString("comment_text"));row.put("correctedAnswer",rs.getString("corrected_answer"));
            row.put("evalDatasetId",rs.getString("eval_dataset_id"));row.put("evalCaseId",rs.getString("eval_case_id"));return row;
        },user,runId).stream().findFirst();
    }
    private Map<String,Object> head(ResultSet rs)throws SQLException {
        Map<String,Object> row=new LinkedHashMap<>();row.put("artifactId",rs.getString("artifact_id"));row.put("runId",rs.getString("run_id"));
        row.put("agentId",rs.getString("agent_id"));row.put("agentVersion",rs.getObject("agent_version"));row.put("title",rs.getString("title"));
        row.put("format",rs.getString("format"));row.put("currentRevision",rs.getInt("current_revision"));row.put("updatedAt",rs.getTimestamp("updated_at").toString());return row;
    }
    private JsonNode read(String value) {try {return json.readTree(value);}catch(Exception e){throw new IllegalStateException("产物正文无法读取",e);}}
    private static void exactFields(JsonNode input,Set<String> allowed) {
        exactFields(input,allowed,"");
    }
    private static void exactFields(JsonNode input,Set<String> allowed,String prefix) {
        if(input==null||!input.isObject())throw WorkspaceService.bad("参数必须为对象");
        input.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw WorkspaceService.bad("不支持的字段："+prefix+key+"；可用字段："+String.join("、",new TreeSet<>(allowed)));});
    }
}
