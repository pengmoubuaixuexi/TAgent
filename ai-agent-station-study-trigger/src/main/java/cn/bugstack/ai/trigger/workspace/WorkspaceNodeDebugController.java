package cn.bugstack.ai.trigger.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static cn.bugstack.ai.trigger.workspace.WorkspaceController.*;

@RestController
@RequestMapping(value="/api/v1/workspace/agents/{id}/nodes/{nodeId}", produces="application/json")
public class WorkspaceNodeDebugController {
    private final WorkspaceNodeDebugService debugger;
    public WorkspaceNodeDebugController(WorkspaceNodeDebugService debugger) { this.debugger=debugger; }
    @PostMapping("/debug") public Object debug(Authentication authentication, @PathVariable String id,
                                               @PathVariable String nodeId, @RequestBody JsonNode input) {
        return ok(debugger.debug(user(authentication),id,nodeId,input));
    }
}
