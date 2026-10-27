package cn.bugstack.ai.trigger.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workspace/model-connections")
public class PersonalModelConnectionController {
    private final PersonalModelConnectionService service;
    public PersonalModelConnectionController(PersonalModelConnectionService service){this.service=service;}
    @GetMapping public Object list(Authentication a){return WorkspaceController.ok(service.list(WorkspaceController.user(a)));}
    @PostMapping public Object create(Authentication a,@RequestBody JsonNode body){return WorkspaceController.ok(service.create(WorkspaceController.user(a),body));}
    @PostMapping("/{id}/rotate") public Object rotate(Authentication a,@PathVariable String id,@RequestBody JsonNode body){service.rotate(WorkspaceController.user(a),id,body);return WorkspaceController.ok(Map.of());}
    @PostMapping("/{id}/verify") public Object verify(Authentication a,@PathVariable String id){service.verify(WorkspaceController.user(a),id);return WorkspaceController.ok(Map.of("verified",true));}
    @PutMapping("/{id}/failover") public Object failover(Authentication a,@PathVariable String id,@RequestBody JsonNode body){service.setFailover(WorkspaceController.user(a),id,body);return WorkspaceController.ok(Map.of());}
    @PostMapping("/{id}/health-check") public Object healthCheck(Authentication a,@PathVariable String id){return WorkspaceController.ok(service.checkHealth(WorkspaceController.user(a),id));}
    @DeleteMapping("/{id}") public Object revoke(Authentication a,@PathVariable String id){service.revoke(WorkspaceController.user(a),id);return WorkspaceController.ok(Map.of());}
    @PutMapping("/auxiliary-policy") public Object policy(Authentication a,@RequestBody JsonNode body){
        if(!body.path("allowPlatformAuxiliaries").isBoolean())throw WorkspaceService.bad("请明确平台辅助服务选项");
        service.setAuxiliaryPolicy(WorkspaceController.user(a),body.path("allowPlatformAuxiliaries").asBoolean());return WorkspaceController.ok(Map.of());
    }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("code",String.valueOf(e.getStatusCode().value()),"info",e.getReason()==null?"操作失败":e.getReason()));}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,SecurityException.class}) public ResponseEntity<?> invalid(RuntimeException e){return ResponseEntity.badRequest().body(Map.of("code","400","info",e.getMessage()==null?"操作失败":e.getMessage()));}
}
