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
    private final org.springframework.beans.factory.ObjectProvider<cn.bugstack.ai.trigger.sandbox.SandboxService> sandbox;
    public WorkspaceOutcomeController(WorkspaceOutcomeService outcomes,
                                      org.springframework.beans.factory.ObjectProvider<cn.bugstack.ai.trigger.sandbox.SandboxService> sandbox){
        this.outcomes=outcomes;this.sandbox=sandbox;
    }
    @GetMapping("/artifacts") public Object list(Authentication a,@RequestParam(required=false)String runId,
            @RequestParam(required=false)String q,@RequestParam(defaultValue="0")int offset){return ok(outcomes.list(user(a),runId,q,offset));}
    @PostMapping("/artifacts") public Object create(Authentication a,@RequestBody JsonNode body){return ok(outcomes.save(user(a),null,body));}
    @GetMapping("/artifacts/{id}") public Object get(Authentication a,@PathVariable String id,@RequestParam(defaultValue="0")int revision){return ok(outcomes.artifact(user(a),id,revision));}
    @PutMapping("/artifacts/{id}") public Object save(Authentication a,@PathVariable String id,@RequestBody JsonNode body){return ok(outcomes.save(user(a),id,body));}
    @GetMapping("/artifacts/{id}/revisions") public Object revisions(Authentication a,@PathVariable String id){return ok(outcomes.revisions(user(a),id));}
    @GetMapping("/artifacts/{id}/export") public ResponseEntity<byte[]> export(Authentication a,@PathVariable String id,@RequestParam(defaultValue="0")int revision,
                                                                 @RequestParam(required=false) String as){
        if(as!=null&&!as.isBlank())return convert(user(a),id,revision,as);
        var file=outcomes.export(user(a),id,revision);return download(file.filename(),file.contentType(),file.bytes());
    }
    /** Markdown → Word/PDF inside the sandbox. */
    private ResponseEntity<byte[]> convert(String user,String id,int revision,String as) {
        if(!"docx".equals(as)&&!"pdf".equals(as))throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"只支持导出为 docx 或 pdf");
        Map<String,Object> artifact=outcomes.artifact(user,id,revision);
        if(!"MARKDOWN".equals(artifact.get("format")))throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"只有文档产物可以导出为 Word 或 PDF");
        var service=sandbox.getIfAvailable();
        if(service==null||!service.available())throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"代码沙箱未启用，暂时不能导出");
        try {
            var converted=service.convertMarkdown(user,((com.fasterxml.jackson.databind.JsonNode)artifact.get("content")).path("markdown").asText(),
                    String.valueOf(artifact.get("title")),as);
            return download(converted.filename(),"pdf".equals(as)?"application/pdf":"application/vnd.openxmlformats-officedocument.wordprocessingml.document",converted.bytes());
        } catch(java.io.IOException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,e.getMessage()==null?"导出失败":e.getMessage());
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"导出被中断");
        }
    }
    @GetMapping("/artifacts/{id}/preview") public Object preview(Authentication a,@PathVariable String id,@RequestParam(defaultValue="0")int revision){
        return ok(outcomes.preview(user(a),id,revision));
    }
    /** Raster images only; served with no-sniff and a sandboxing CSP so nothing in the file can run. */
    @GetMapping("/artifacts/{id}/inline") public ResponseEntity<byte[]> inline(Authentication a,@PathVariable String id,@RequestParam(defaultValue="0")int revision){
        var image=outcomes.inlineImage(user(a),id,revision);
        return ResponseEntity.ok().header("Content-Type",image.mimeType()).header("X-Content-Type-Options","nosniff")
                .header("Content-Security-Policy","default-src 'none'; sandbox").header("Cache-Control","private, no-store")
                .header("Content-Disposition",ContentDisposition.inline().filename(image.filename(),StandardCharsets.UTF_8).build().toString())
                .body(image.bytes());
    }
    /** The Run button of a Python code artifact. Outputs stay downloadable for a short while; nothing is saved. */
    @PostMapping("/artifacts/{id}/run") public Object run(Authentication a,@PathVariable String id,@RequestParam(defaultValue="0")int revision){
        String user=user(a);
        Map<String,Object> artifact=outcomes.artifact(user,id,revision);
        var content=(com.fasterxml.jackson.databind.JsonNode)artifact.get("content");
        String language=content.path("language").asText("").toLowerCase(java.util.Locale.ROOT),filename=content.path("filename").asText("");
        if(!"CODE".equals(artifact.get("format"))||!(language.startsWith("python")||filename.endsWith(".py")))
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"只有 Python 代码产物可以运行");
        var service=sandbox.getIfAvailable();
        if(service==null||!service.available())throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"代码沙箱未启用");
        try {return ok(service.runCode(user,content.path("code").asText()));}
        catch(java.io.IOException e){throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,e.getMessage()==null?"运行失败":e.getMessage());}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"运行被中断");}
    }
    @GetMapping("/sandbox-runs/{executionId}/outputs/{index}") public ResponseEntity<byte[]> runOutput(Authentication a,@PathVariable String executionId,
                                                                                                     @PathVariable int index,@RequestParam(defaultValue="output") String name){
        var service=sandbox.getIfAvailable();
        if(service==null)throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"运行结果不存在");
        try {return download(WorkspaceOutcomeService.safeFilename(name),"application/octet-stream",service.pageRunOutput(user(a),executionId,index));}
        catch(java.io.IOException e){throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"运行结果不存在或已过期");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"下载被中断");}
    }
    private static ResponseEntity<byte[]> download(String filename,String contentType,byte[] bytes) {
        return ResponseEntity.ok().header("Content-Type",contentType)
                .header("Content-Disposition",ContentDisposition.attachment().filename(filename,StandardCharsets.UTF_8).build().toString()).header("X-Content-Type-Options","nosniff")
                .header("Cache-Control","no-store").body(bytes);
    }
    @GetMapping("/runs/{runId}/feedback") public Object feedback(Authentication a,@PathVariable String runId){return ok(outcomes.feedback(user(a),runId));}
    @PutMapping("/runs/{runId}/feedback") public Object feedback(Authentication a,@PathVariable String runId,@RequestBody JsonNode body){return ok(outcomes.saveFeedback(user(a),runId,body));}
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> error(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("code",String.valueOf(e.getStatusCode().value()),"info",e.getReason()==null?"操作失败":e.getReason()));}
}
