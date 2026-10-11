package cn.bugstack.ai.trigger.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import static cn.bugstack.ai.trigger.workspace.WorkspaceController.*;

@RestController
@RequestMapping("/api/v1/workspace/outcomes")
public class WorkspaceOutcomeController {
    private final WorkspaceOutcomeService outcomes;
    public WorkspaceOutcomeController(WorkspaceOutcomeService outcomes){this.outcomes=outcomes;}
    @GetMapping("/artifacts") public Object list(Authentication a,@RequestParam(required=false)String runId){return ok(outcomes.list(user(a),runId));}
    @PostMapping("/artifacts") public Object create(Authentication a,@RequestBody JsonNode body){return ok(outcomes.save(user(a),null,body));}
    @GetMapping("/artifacts/{id}") public Object get(Authentication a,@PathVariable String id,@RequestParam(defaultValue="0")int revision){return ok(outcomes.artifact(user(a),id,revision));}
    @PutMapping("/artifacts/{id}") public Object save(Authentication a,@PathVariable String id,@RequestBody JsonNode body){return ok(outcomes.save(user(a),id,body));}
    @GetMapping("/artifacts/{id}/revisions") public Object revisions(Authentication a,@PathVariable String id){return ok(outcomes.revisions(user(a),id));}
    @GetMapping("/artifacts/{id}/export") public ResponseEntity<byte[]> export(Authentication a,@PathVariable String id,@RequestParam(defaultValue="0")int revision){
        var file=outcomes.export(user(a),id,revision);return ResponseEntity.ok().header("Content-Type",file.contentType())
                .header("Content-Disposition",ContentDisposition.attachment().filename(file.filename(),StandardCharsets.UTF_8).build().toString()).header("X-Content-Type-Options","nosniff")
                .header("Cache-Control","no-store").body(file.bytes());
    }
    @GetMapping("/runs/{runId}/feedback") public Object feedback(Authentication a,@PathVariable String runId){return ok(outcomes.feedback(user(a),runId));}
    @PutMapping("/runs/{runId}/feedback") public Object feedback(Authentication a,@PathVariable String runId,@RequestBody JsonNode body){return ok(outcomes.saveFeedback(user(a),runId,body));}
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> error(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("code",String.valueOf(e.getStatusCode().value()),"info",e.getReason()==null?"操作失败":e.getReason()));}
}
