package cn.bugstack.ai.trigger.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Explicit user-triggered /models verification; never generates model output or follows redirects. */
@Component
public class ModelConnectionProbe {
    public void verify(String baseUrl,String key,String model) {
        try {
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
            String path=cn.bugstack.ai.domain.agent.service.support.OpenAiCompatibleApiSupport.chatCompletionsPath(baseUrl,null)
                    .replace("/chat/completions","/models");
            var request=HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$","")+path))
                    .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+key).GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) throw new IllegalArgumentException("连接验证失败（HTTP "+response.statusCode()+"）");
            var body=new ObjectMapper().readTree(response.body());
            boolean found=false;
            for(var item:body.path("data")) if(model.equals(item.path("id").asText())) found=true;
            if(!found) throw new IllegalArgumentException("连接可达，但模型列表未返回该模型名称，请核对提供商的模型名称");
        } catch(IllegalArgumentException e){throw e;}
        catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new IllegalArgumentException("连接验证失败，请核对地址、密钥及模型名称");}
    }
}
