package cn.bugstack.ai.trigger.workspace;

import cn.bugstack.ai.domain.agent.adapter.repository.IRunHistoryRepository;
import cn.bugstack.ai.domain.agent.service.security.PiiMasker;
import cn.bugstack.ai.infrastructure.adapter.repository.trace.RunTraceStore;
import cn.bugstack.ai.trigger.security.ConversationAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.bugstack.ai.trigger.workspace.WorkspaceController.*;

@RestController
@RequestMapping(value="/api/v1/history",produces="application/json")
public class RunHistoryController {
    private final IRunHistoryRepository history;
    private final ConversationAccess conversations;
    private final RunTraceStore traces;
    public RunHistoryController(IRunHistoryRepository history,ConversationAccess conversations,RunTraceStore traces){this.history=history;this.conversations=conversations;this.traces=traces;}
    @GetMapping public Object list(Authentication a,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="30") int limit,
            @RequestParam(required=false) String q){return ok(history.listOwned(user(a),q,offset,limit));}
    @GetMapping("/session/{sessionId}") public Object session(Authentication a,@PathVariable String sessionId,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit) {
        String owner=user(a);
        if(sessionId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"缺少会话标识");
        conversations.session(sessionId,owner,false);
        return ok(history.listOwnedSession(owner,sessionId,offset,limit));
    }
    @GetMapping("/{runId}") public Object get(Authentication a,@PathVariable String runId){return ok(history.findOwned(user(a),runId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"运行不存在或无权访问")));}

    /** Complete trace of a run: node list with previews. Owner or administrator; texts are masked for display. */
    @GetMapping("/{runId}/trace") public Object trace(Authentication a,@PathVariable String runId) {
        traceAccess(a,runId);
        List<Map<String,Object>> nodes=new ArrayList<>();
        for(Map<String,Object> node:traces.nodes(runId)) nodes.add(maskMap(node));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("runId",runId);result.put("nodes",nodes);
        return ok(result);
    }
    /** One node restored in full: a model request's complete messages, reasoning and reply; a tool call's arguments and result. */
    @GetMapping("/{runId}/trace/{seq}") public Object traceNode(Authentication a,@PathVariable String runId,@PathVariable int seq) {
        traceAccess(a,runId);
        ObjectNode node=traces.node(runId,seq);
        if(node==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"轨迹节点不存在");
        return ok(mask(node));
    }
    private void traceAccess(Authentication a,String runId) {
        String user=user(a);
        if(a.getAuthorities().stream().anyMatch(g->"ROLE_ADMIN".equals(g.getAuthority()))) return;
        if(history.findOwned(user,runId).isPresent() || user.equals(traces.owner(runId)) || traces.evaluatedBy(runId,user)) return;
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,"运行不存在或无权访问");
    }
    private static JsonNode mask(JsonNode node) {
        if(node instanceof ObjectNode object) {
            var names=new ArrayList<String>();object.fieldNames().forEachRemaining(names::add);
            for(String name:names) object.set(name,mask(object.get(name)));
            return object;
        }
        if(node instanceof ArrayNode array) {
            for(int i=0;i<array.size();i++) array.set(i,mask(array.get(i)));
            return array;
        }
        return node!=null&&node.isTextual()?TextNode.valueOf(PiiMasker.mask(node.asText())):node;
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> maskMap(Map<String,Object> map) {
        Map<String,Object> masked=new LinkedHashMap<>();
        map.forEach((key,value)->masked.put(key,value instanceof String text?PiiMasker.mask(text)
                :value instanceof Map<?,?> nested?maskMap((Map<String,Object>)nested)
                :value instanceof List<?> list?list.stream().map(v->v instanceof String s?PiiMasker.mask(s):v).toList():value));
        return masked;
    }
}
