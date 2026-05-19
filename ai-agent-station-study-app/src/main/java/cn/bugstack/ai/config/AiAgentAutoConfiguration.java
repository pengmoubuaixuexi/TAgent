package cn.bugstack.ai.config;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.IArmoryService;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.ai.infrastructure.dao.IAiAgentDao;
import cn.bugstack.ai.infrastructure.dao.IAiAgentFlowConfigDao;
import cn.bugstack.ai.types.common.Constants;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI Agent 自动装配配置类
 * 在Spring Boot应用启动完成后，根据配置自动装配AI客户端
 *
 * @author TAgent
 * 2025/1/15 10:00
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AiAgentAutoConfigProperties.class)
@ConditionalOnProperty(prefix = "spring.ai.agent.auto-config", name = "enabled", havingValue = "true")
public class AiAgentAutoConfiguration implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private DefaultArmoryStrategyFactory defaultArmoryStrategyFactory;

    @Resource
    private IArmoryService armoryService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            log.info("AI Agent 自动装配开始，配置: {}", aiAgentAutoConfigProperties);
            
            // 检查配置是否有效
            if (!aiAgentAutoConfigProperties.isEnabled()) {
                log.info("AI Agent 自动装配未启用");
                return;
            }

            if (aiAgentAutoConfigProperties.isPreloadEnabled()) {
                List<AiAgentVO> aiAgentVOS = armoryService.acceptArmoryAllAvailableAgents();
                log.info("AI Agent 预装配完成 {}", JSON.toJSONString(aiAgentVOS));
            } else {
                log.info("AI Agent 预装配已关闭，采用懒加载模式（首次请求时按需装配）");
            }
        } catch (Exception e) {
            log.error("AI Agent 自动装配失败", e);
        }
    }

}