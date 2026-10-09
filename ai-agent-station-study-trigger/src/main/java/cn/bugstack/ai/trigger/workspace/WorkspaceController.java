package cn.bugstack.ai.trigger.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping(value="/api/v1/workspace",produces="application/json")
public class WorkspaceController {
    private final WorkspaceService workspace;
    private final WorkspaceDraftService drafts;
    public WorkspaceController(WorkspaceService workspace,WorkspaceDraftService drafts){this.workspace=workspace;this.drafts=drafts;}
    @GetMapping("/options") public Object options(Authentication a){return ok(workspace.options(user(a)));}
    @GetMapping("/agents") public Object agents(Authentication a){return ok(workspace.agents(user(a)));}
    @GetMapping("/agents/{id}") public Object agent(Authentication a,@PathVariable String id){return ok(workspace.agent(user(a),id));}
    @PostMapping("/agents") public Object create(Authentication a,@RequestBody JsonNode body){return ok(workspace.saveAgent(user(a),null,body));}
    @PutMapping("/agents/{id}") public Object update(Authentication a,@PathVariable String id,@RequestBody JsonNode body){return ok(workspace.saveAgent(user(a),id,body));}
    @DeleteMapping("/agents/{id}") public Object delete(Authentication a,@PathVariable String id){workspace.archiveAgent(user(a),id);return ok(Map.of());}
    @GetMapping("/public-agents") public Object publicAgents(Authentication a){user(a);return ok(workspace.publicAgents());}
    @PostMapping("/public-agents/{id}/use") public Object usePublic(Authentication a,@PathVariable String id){return ok(workspace.usePublic(user(a),id));}
    @PostMapping("/draft") public Object draft(Authentication a,@RequestBody JsonNode body){return ok(drafts.draft(user(a),body));}
    @GetMapping("/mcps") public Object mcps(Authentication a){return ok(workspace.mcps(user(a)));}
    @GetMapping("/mcps/{id}/tools") public Object tools(Authentication a,@PathVariable String id){return ok(workspace.mcpTools(user(a),id,false));}
    @PostMapping("/mcps/{id}/discover") public Object discover(Authentication a,@PathVariable String id){return ok(workspace.mcpTools(user(a),id,true));}
    @PostMapping("/mcps") public Object addMcp(Authentication a,@RequestBody JsonNode body){return ok(workspace.saveMcp(user(a),null,body));}
    @PutMapping("/mcps/{id}") public Object editMcp(Authentication a,@PathVariable String id,@RequestBody JsonNode body){return ok(workspace.saveMcp(user(a),id,body));}
    @DeleteMapping("/mcps/{id}") public Object removeMcp(Authentication a,@PathVariable String id){workspace.deleteMcp(user(a),id);return ok(Map.of());}
    static String user(Authentication a){
        if(a==null||!a.isAuthenticated()||"anonymousUser".equals(a.getPrincipal())) throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"请先登录");
        return a.getName();
    }
    static Object ok(Object data){return Map.of("code","0000","info","成功","data",data);}
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> error(ResponseStatusException e){
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("code",String.valueOf(e.getStatusCode().value()),"info",e.getReason()==null?"操作失败":e.getReason()));
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e){
        return ResponseEntity.badRequest().body(Map.of("code","400","info",e.getMessage()==null?"参数无效":e.getMessage()));
    }
}
