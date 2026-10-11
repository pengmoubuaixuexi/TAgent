package cn.bugstack.ai.trigger.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static cn.bugstack.ai.trigger.workspace.WorkspaceController.*;

@RestController
@RequestMapping(value="/api/v1/workspace/agents/{id}",produces="application/json")
public class WorkspaceVersionController {
    private final WorkspaceVersionService versions;
    public WorkspaceVersionController(WorkspaceVersionService versions) { this.versions=versions; }
    @GetMapping("/versions") public Object list(Authentication a,@PathVariable String id) { return ok(versions.versions(user(a),id)); }
    @GetMapping("/versions/{version}") public Object get(Authentication a,@PathVariable String id,@PathVariable int version) { return ok(versions.version(user(a),id,version)); }
    @GetMapping("/versions/compare") public Object compare(Authentication a,@PathVariable String id,@RequestParam int from,@RequestParam int to) { return ok(versions.compare(user(a),id,from,to)); }
    @GetMapping("/draft") public Object draft(Authentication a,@PathVariable String id) { return ok(versions.draft(user(a),id)); }
    @PutMapping("/draft") public Object save(Authentication a,@PathVariable String id,@RequestBody JsonNode input) { return ok(versions.saveDraft(user(a),id,input)); }
    @PostMapping("/publish") public Object publish(Authentication a,@PathVariable String id,@RequestBody JsonNode input) { return ok(versions.publish(user(a),id,input)); }
    @PostMapping("/versions/{version}/restore") public Object restore(Authentication a,@PathVariable String id,@PathVariable int version,@RequestBody JsonNode input) { return ok(versions.restore(user(a),id,version,input)); }
}
