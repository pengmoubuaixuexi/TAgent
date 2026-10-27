package cn.bugstack.ai.trigger.workspace;

import cn.bugstack.ai.domain.agent.service.model.ModelProviderAccess;
import cn.bugstack.ai.infrastructure.security.ModelSecretCipher;
import cn.bugstack.ai.infrastructure.security.PersonalModelEndpointPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PersonalModelConnectionService {
    private final JdbcTemplate db;
    private final ModelSecretCipher cipher;
    private final PersonalModelEndpointPolicy endpoints;
    private final ModelProviderAccess credentials;
    private final ModelConnectionProbe probe;
    /** Null only in narrow test contexts: then no catalog entry is written and no health is shown. */
    private final ModelFailoverSettings failover;
    @org.springframework.beans.factory.annotation.Autowired
    public PersonalModelConnectionService(@org.springframework.beans.factory.annotation.Qualifier("mysqlJdbcTemplate") JdbcTemplate db,
            ModelSecretCipher cipher,PersonalModelEndpointPolicy endpoints,ModelProviderAccess credentials,ModelConnectionProbe probe,
            org.springframework.beans.factory.ObjectProvider<ModelFailoverSettings> failover) {
        this(db,cipher,endpoints,credentials,probe,failover.getIfAvailable());
    }
    public PersonalModelConnectionService(JdbcTemplate db,ModelSecretCipher cipher,PersonalModelEndpointPolicy endpoints,
            ModelProviderAccess credentials,ModelConnectionProbe probe) {
        this(db,cipher,endpoints,credentials,probe,(ModelFailoverSettings)null);
    }
    public PersonalModelConnectionService(JdbcTemplate db,ModelSecretCipher cipher,PersonalModelEndpointPolicy endpoints,
            ModelProviderAccess credentials,ModelConnectionProbe probe,ModelFailoverSettings failover) {
        this.db=db;this.cipher=cipher;this.endpoints=endpoints;this.credentials=credentials;this.probe=probe;this.failover=failover;
    }
    public Map<String,Object> list(String user) {
        var rows=db.queryForList("""
            SELECT a.api_id AS apiId,a.base_url AS baseUrl,a.status,s.display_name AS name,
                   s.credential_version AS credentialVersion,s.verified_at AS verifiedAt,
                   m.model_id AS modelId,m.model_name AS modelName,m.tier,
                   c.tier AS failoverTier,c.sort_order AS sortOrder
            FROM ai_model_secret s JOIN ai_client_api a ON a.api_id=s.api_id AND a.owner_user_id=s.owner_user_id
            JOIN ai_client_model m ON m.api_id=a.api_id AND m.owner_user_id=s.owner_user_id AND m.source_model_id IS NULL
            LEFT JOIN ai_model_catalog c ON c.owner_scope=s.owner_user_id AND c.api_id=a.api_id AND c.model_name=m.model_name
            WHERE s.owner_user_id=? ORDER BY s.create_time DESC
            """,user);
        for(var row:rows){
            row.put("hasSecret",true);row.put("maskedKey","••••••••");row.put("providerSource","USER_BYOK");
            if(row.get("failoverTier")!=null)row.put("tier",row.remove("failoverTier"));else row.remove("failoverTier");
            if(row.get("sortOrder")==null)row.put("sortOrder",100);
            // The owner sees the redacted provider text of their own model.
            row.put("health",failover==null?Map.of("state","UNKNOWN"):failover.health((String)row.get("apiId"),(String)row.get("modelName"),true));
        }
        var policies=db.queryForList("SELECT platform_auxiliaries_enabled FROM ai_user_model_policy WHERE user_id=?",user);
        boolean allow=policies.isEmpty() || ((Number)policies.get(0).get("platform_auxiliaries_enabled")).intValue()==1;
        return Map.of("connections",rows,"secretsAvailable",cipher.configured(),"allowedHosts",endpoints.allowedHosts(),
                "allowPlatformAuxiliaries",allow,"auxiliaryProviderSource","PLATFORM",
                "auxiliaryPurposes",List.of("路由、规划及摘要等辅助对话","知识索引与检索向量","重排"));
    }
    @Transactional public Map<String,Object> create(String user,JsonNode input) {
        String name=WorkspaceService.text(input,"name",1,100);
        String model=WorkspaceService.text(input,"modelName",1,128);
        String base=endpoints.validate(WorkspaceService.text(input,"baseUrl",8,500));
        String key=secret(input);
        if(!input.path("allowPlatformAuxiliaries").isBoolean())throw WorkspaceService.bad("请明确是否允许平台辅助模型服务");
        String api="pa"+UUID.randomUUID().toString().replace("-","");
        String modelId="pm"+UUID.randomUUID().toString().replace("-","");
        String ref="ms"+UUID.randomUUID().toString().replace("-","");
        String encrypted=cipher.encrypt(user,ref,1,key);
        db.update("""
            INSERT INTO ai_client_api(api_id,owner_user_id,base_url,api_key,secret_ref,completions_path,embeddings_path,status,create_time,update_time)
            VALUES(?,?,?,'managed',?,'','',1,NOW(),NOW())
            """,api,user,base,ref);
        db.update("""
            INSERT INTO ai_model_secret(secret_ref,api_id,owner_user_id,display_name,ciphertext,credential_version,status)
            VALUES(?,?,?,?,?,1,1)
            """,ref,api,user,name,encrypted);
        db.update("""
            INSERT INTO ai_client_model(model_id,api_id,owner_user_id,model_name,model_type,model_usage,tier,capabilities_json,status,platform_enabled,source_model_id,create_time,update_time)
            VALUES(?,?,?,?,'openai','chat','medium','{}',1,0,NULL,NOW(),NOW())
            """,modelId,api,user,model);
        // Every personal model is a failover candidate for the owner's other personal models (never for platform ones).
        if(failover!=null)failover.register(user,api,model);
        setAuxiliaryPolicy(user,input.path("allowPlatformAuxiliaries").asBoolean());
        return Map.of("apiId",api,"modelId",modelId,"credentialVersion",1,"hasSecret",true);
    }
    /** Tier and order among the owner's own models, used only when one of them is unavailable. */
    @Transactional public void setFailover(String user,String api,JsonNode input) {
        var row=owned(user,api,true);
        String tier=WorkspaceService.text(input,"tier",4,8);
        if(!cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogEntry.TIERS.contains(tier))throw WorkspaceService.bad("档位只能是 small、medium 或 large");
        int order=input.path("sortOrder").isInt()?input.path("sortOrder").asInt():100;
        if(order<0||order>10000)throw WorkspaceService.bad("顺序需在 0 到 10000 之间");
        String model=(String)row.get("model_name");
        if(failover==null)throw new IllegalStateException("模型降级设置暂不可用");
        db.update("UPDATE ai_client_model SET tier=?,update_time=NOW() WHERE api_id=? AND owner_user_id=? AND source_model_id IS NULL",tier,api,user);
        failover.save(user,api,model,tier,order);
    }
    /** One minimal request (about ten tokens on the owner's key); also clears or confirms the health state. */
    public Map<String,Object> checkHealth(String user,String api) {
        var row=owned(user,api,false);
        if(failover==null)throw new IllegalStateException("模型健康检测暂不可用");
        return failover.check(api,(String)row.get("model_name"),true);
    }
    @Transactional public void rotate(String user,String api,JsonNode input) {
        var row=owned(user,api,true);
        int version=((Number)row.get("credential_version")).intValue()+1;
        String encrypted=cipher.encrypt(user,(String)row.get("secret_ref"),version,secret(input));
        db.update("UPDATE ai_model_secret SET ciphertext=?,credential_version=?,status=1,verified_at=NULL,update_time=NOW() WHERE api_id=? AND owner_user_id=?",encrypted,version,api,user);
        db.update("UPDATE ai_client_api SET status=1,update_time=NOW() WHERE api_id=? AND owner_user_id=?",api,user);
    }
    @Transactional public void revoke(String user,String api) {
        owned(user,api,true);
        db.update("UPDATE ai_model_secret SET status=0,credential_version=credential_version+1,verified_at=NULL,update_time=NOW() WHERE api_id=? AND owner_user_id=?",api,user);
        db.update("UPDATE ai_client_api SET status=0,update_time=NOW() WHERE api_id=? AND owner_user_id=?",api,user);
    }
    public void verify(String user,String api) {
        var row=owned(user,api,false);
        String base=(String)row.get("base_url");
        endpoints.requirePublicDns(base);
        String key=credentials.apiKeyForRequest(api,user);
        probe.verify(base,key,(String)row.get("model_name"));
        int changed=db.update("UPDATE ai_model_secret SET verified_at=NOW() WHERE api_id=? AND owner_user_id=? AND credential_version=? AND status=1",api,user,row.get("credential_version"));
        if(changed!=1)throw WorkspaceService.bad("验证期间密钥已变化，请重新验证");
    }
    @Transactional public void setAuxiliaryPolicy(String user,boolean allow) {
        db.update("""
            INSERT INTO ai_user_model_policy(user_id,platform_auxiliaries_enabled) VALUES(?,?)
            ON DUPLICATE KEY UPDATE platform_auxiliaries_enabled=VALUES(platform_auxiliaries_enabled),update_time=NOW()
            """,user,allow?1:0);
    }
    private Map<String,Object> owned(String user,String api,boolean lock) {
        var rows=db.queryForList("""
            SELECT s.secret_ref,s.credential_version,a.base_url,m.model_name
            FROM ai_model_secret s JOIN ai_client_api a ON a.api_id=s.api_id AND a.owner_user_id=s.owner_user_id
            JOIN ai_client_model m ON m.api_id=s.api_id AND m.owner_user_id=s.owner_user_id AND m.source_model_id IS NULL
            WHERE s.api_id=? AND s.owner_user_id=?
            """+(lock?" FOR UPDATE":""),api,user);
        if(rows.size()!=1)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"连接不存在或无权访问");
        return rows.get(0);
    }
    private String secret(JsonNode input) {
        String value=WorkspaceService.text(input,"apiKey",1,4096);
        if(value.indexOf('\r')>=0||value.indexOf('\n')>=0)throw WorkspaceService.bad("密钥不能包含换行");
        return value;
    }
}
