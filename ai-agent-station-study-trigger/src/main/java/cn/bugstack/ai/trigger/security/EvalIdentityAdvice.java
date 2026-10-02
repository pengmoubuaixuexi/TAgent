package cn.bugstack.ai.trigger.security;

import cn.bugstack.ai.trigger.http.EvalOpsController;
import cn.bugstack.ai.trigger.eval.EvalRunService;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import java.lang.reflect.Type;

@ControllerAdvice(assignableTypes = EvalOpsController.class)
public class EvalIdentityAdvice extends RequestBodyAdviceAdapter {
    private final EvalAccess access;
    public EvalIdentityAdvice(EvalAccess access) { this.access = access; }
    @Override public boolean supports(MethodParameter p, Type t, Class<? extends HttpMessageConverter<?>> c) { return true; }
    @Override public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter p, Type t,
            Class<? extends HttpMessageConverter<?>> c) {
        if (body instanceof EvalOpsController.DatasetRequest r) r.setOwnerUserId(EvalAccess.userId());
        if (body instanceof EvalOpsController.ImportRequest r) r.setOwnerUserId(EvalAccess.userId());
        if (body instanceof EvalRunService.RunCommand r) {
            access.version(r.getVersionId());
            r.setUserId(EvalAccess.userId());
            r.setTenantId("default");
        }
        return body;
    }
}
