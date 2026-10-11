package cn.bugstack.ai.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Personal API endpoints must be explicitly allowed by the operator; credentials never follow redirects. */
@Component
public class PersonalModelEndpointPolicy {
    private final Set<String> hosts;
    public PersonalModelEndpointPolicy(@Value("${agent.model-connections.allowed-hosts:api.openai.com,api.deepseek.com,api.siliconflow.cn}") String configured) {
        hosts=Arrays.stream(configured.split(",")).map(String::trim).map(String::toLowerCase).filter(s->!s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
    public Set<String> allowedHosts(){return hosts;}
    public void requireRequestTarget(String baseUrl,URI target) {
        URI base=URI.create(validate(baseUrl));
        String prefix=base.getPath().replaceAll("/+$","")+"/";
        if(!"https".equalsIgnoreCase(target.getScheme()) || !base.getHost().equalsIgnoreCase(target.getHost())
                || (target.getPort()!=-1 && target.getPort()!=443) || target.getUserInfo()!=null
                || !target.normalize().getPath().startsWith(prefix))
            throw new SecurityException("模型请求地址与当前连接配置不一致，请重新装配模型连接");
    }
    public String validate(String text) {
        URI uri;
        try { uri=URI.create(text); } catch(Exception e) {throw new IllegalArgumentException("模型地址无效");}
        if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || !hosts.contains(uri.getHost().toLowerCase())
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || (uri.getPort()!=-1 && uri.getPort()!=443)
                || uri.getPath().contains("..")) throw new IllegalArgumentException("模型地址必须使用管理员允许域名的 HTTPS 地址，且不能包含凭据或查询参数");
        return text.replaceAll("/+$","");
    }
    public void requirePublicDns(String text) {
        validate(text);
        try {
            InetAddress[] resolved=resolve(URI.create(text).getHost());
            if(resolved.length==0)throw new IllegalArgumentException();
            for(InetAddress ip:resolved)
                if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress())
                    throw new IllegalArgumentException();
                else {
                    byte[] bytes=ip.getAddress();
                    if(bytes.length==16 && (bytes[0]&0xfe)==0xfc)throw new IllegalArgumentException();
                    if(bytes.length==4 && ((bytes[0]&0xff)==0 || (bytes[0]&0xff)>=224
                            || ((bytes[0]&0xff)==100 && (bytes[1]&0xc0)==64)
                            || ((bytes[0]&0xff)==198 && ((bytes[1]&0xff)==18 || (bytes[1]&0xff)==19))))
                        throw new IllegalArgumentException();
                }
        } catch(Exception e) { throw new IllegalArgumentException("模型域名未解析到允许的公网地址"); }
    }
    protected InetAddress[] resolve(String host) throws java.net.UnknownHostException { return InetAddress.getAllByName(host); }
}
