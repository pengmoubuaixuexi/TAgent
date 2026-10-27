package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.service.security.WorkspaceMcpPolicy;
import cn.bugstack.ai.trigger.workspace.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WorkspaceKnowledgeOptionsTest {
    @Test
    @SuppressWarnings("unchecked")
    public void groupsPublishedFilesWithoutLeakingOtherOwnersOrDeduplicatingNames() {
        JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try {
            db.execute("CREATE TABLE ai_client_rag_order(rag_id VARCHAR PRIMARY KEY, user_id VARCHAR, knowledge_tag VARCHAR, rag_name VARCHAR, status INT)");
            db.update("INSERT INTO ai_client_rag_order VALUES "
                    + "('a1','alice','电商购物','商品.pdf',1),"
                    + "('a2','alice','电商购物','商品.pdf',1),"
                    + "('a3','alice','电商购物','退货.pdf',1),"
                    + "('a4','alice','电商购物','未入库.pdf',0),"
                    + "('a5','alice','旅行','路线.pdf',1),"
                    + "('b1','bob','电商购物','私有资料.pdf',1)");
            WorkspaceMcpPolicy policy = mock(WorkspaceMcpPolicy.class);
            when(policy.allowedHosts()).thenReturn(List.of());
            WorkspaceService service = new WorkspaceService(db, new ObjectMapper(), null, null, null, policy, null, null) {
                @Override public List<Map<String,Object>> models(String user) { return List.of(); }
            };
            List<Map<String,Object>> knowledge = (List<Map<String,Object>>) service.options("alice").get("knowledge");
            assertEquals(2, knowledge.size());
            Map<String,Object> shopping = knowledge.stream().filter(k -> "电商购物".equals(k.get("knowledgeTag"))).findFirst().orElseThrow();
            assertEquals("电商购物", shopping.get("name"));
            assertEquals(3, shopping.get("documentCount"));
            assertEquals(List.of("商品.pdf", "商品.pdf", "退货.pdf"), shopping.get("documents"));
            List<Map<String,Object>> bob = (List<Map<String,Object>>) service.options("bob").get("knowledge");
            assertEquals(1, bob.size());
            assertEquals(List.of("私有资料.pdf"), bob.get(0).get("documents"));
            assertEquals(List.of(), service.options("no-files").get("knowledge"));
        } finally {
            db.execute("SHUTDOWN");
        }
    }
}
