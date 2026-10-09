package cn.bugstack.ai.domain.agent.service.security;

import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** User endpoints require operator approval; this is not a general URL proxy. */
@Component
public class WorkspaceMcpPolicy {
    private final List<String> allowedHosts;
    public WorkspaceMcpPolicy(@Value("${agent.workspace.mcp-allowed-hosts:}") String hosts) {
        allowedHosts = Arrays.stream(hosts.split(",")).map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> !s.isBlank()).distinct().toList();
    }
    public List<String> allowedHosts() { return allowedHosts; }
    public URI validateRemote(String transport, String url) {
        if (!List.of("sse", "streamable", "streamable-http", "streamablehttp").contains(transport))
            throw new IllegalArgumentException("个人工具只支持 HTTPS SSE / Streamable HTTP 连接");
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getRawQuery() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !allowedHosts.contains(host.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("工具地址须为管理员允许的 HTTPS 域名，认证信息请填写在令牌栏");
            InetAddress[] addresses = resolve(host);
            if (addresses.length == 0) throw new IllegalArgumentException("工具域名无法解析");
            for (InetAddress address : addresses) if (!publicAddress(address))
                throw new IllegalArgumentException("个人工具不能连接本机、内网或云元数据地址");
            return uri;
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("工具域名无法解析，请检查地址"); }
    }
    protected InetAddress[] resolve(String host) throws Exception { return InetAddress.getAllByName(host); }
    static boolean publicAddress(InetAddress ip) {
        if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                || ip.isSiteLocalAddress() || ip.isMulticastAddress()) return false;
        byte[] b = ip.getAddress();
        if (b.length == 16) return (b[0] & 0xe0) == 0x20; // globally routed IPv6 only
        int a = b[0] & 255, c = b[1] & 255;
        return a != 0 && a != 127 && a < 224 && !(a == 100 && c >= 64 && c <= 127)
                && !(a == 169 && c == 254) && !(a == 198 && (c == 18 || c == 19));
    }
    public void validate(AiClientToolMcpVO mcp) {
        try {
            var config = new ObjectMapper().readTree(mcp.getTransportConfig());
            if (!config.path("workspaceOwned").asBoolean(false)) return; // immutable administrator configuration
            String url = "sse".equals(mcp.getTransportType())
                    ? config.path("baseUri").asText("") + config.path("sseEndpoint").asText("/sse")
                    : config.path("url").asText("");
            validateRemote(mcp.getTransportType(), url);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("MCP 配置无效"); }
    }
}
