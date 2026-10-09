package cn.bugstack.ai.test.security;

import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.service.security.WorkspaceMcpPolicy;
import org.junit.Test;
import java.net.InetAddress;
import static org.junit.Assert.*;

public class WorkspaceMcpPolicyTest {
    private WorkspaceMcpPolicy policy(byte[] address) {
        return new WorkspaceMcpPolicy("mcp.example.test") {
            @Override protected InetAddress[] resolve(String host) throws Exception {
                return new InetAddress[]{InetAddress.getByAddress(address)};
            }
        };
    }
    @Test public void userCannotUseCommandsUnknownHostsOrCredentialUrls() {
        var p=policy(new byte[]{1,1,1,1});
        assertEquals("mcp.example.test",p.validateRemote("streamable","https://mcp.example.test/mcp").getHost());
        for(String url:new String[]{"http://mcp.example.test/mcp","https://other.example.test/mcp",
                "https://mcp.example.test.evil.test/mcp","https://user:secret@mcp.example.test/mcp",
                "https://mcp.example.test/mcp?key=secret","https://mcp.example.test:8443/mcp","https://127.0.0.1/mcp"})
            assertThrows(IllegalArgumentException.class,()->p.validateRemote("streamable",url));
        assertThrows(IllegalArgumentException.class,()->p.validateRemote("stdio","https://mcp.example.test/mcp"));
    }
    @Test public void approvedHostCannotResolveToInternalOrMetadataNetwork() {
        for(byte[] ip:new byte[][]{{127,0,0,1},{10,2,0,7},{(byte)172,18,0,1},{(byte)192,(byte)168,1,1},
                {(byte)169,(byte)254,(byte)169,(byte)254},{100,100,100,(byte)200},{0,0,0,0}})
            assertThrows(IllegalArgumentException.class,()->policy(ip).validateRemote("sse","https://mcp.example.test/sse"));
    }
    @Test public void reconnectRevalidatesWorkspaceConfigurationButKeepsTrustedLegacyTransport() {
        var p=policy(new byte[]{127,0,0,1});
        var mcp=AiClientToolMcpVO.builder().transportType("streamable-http")
                .transportConfig("{\"workspaceOwned\":true,\"url\":\"https://mcp.example.test/mcp\"}").build();
        assertThrows(IllegalArgumentException.class,()->p.validate(mcp));
        p.validate(AiClientToolMcpVO.builder().transportType("stdio").transportConfig("{\"legacy\":{\"command\":\"node\"}}").build());
    }
}
