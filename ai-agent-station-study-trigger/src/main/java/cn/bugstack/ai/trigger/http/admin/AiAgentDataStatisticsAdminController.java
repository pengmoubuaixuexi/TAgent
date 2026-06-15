package cn.bugstack.ai.trigger.http.admin;

import cn.bugstack.ai.api.IAiAgentDataStatisticsAdminService;
import cn.bugstack.ai.api.dto.DataStatisticsResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.infrastructure.dao.*;
import cn.bugstack.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * 数据统计
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/10/4 10:33
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/data/statistics")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AiAgentDataStatisticsAdminController implements IAiAgentDataStatisticsAdminService {

    @Resource
    private IAiAgentDao aiAgentDao;
    @Resource
    private IAiAgentDrawConfigDao aiAgentDrawConfigDao;
    @Resource
    private IAiAgentFlowConfigDao aiAgentFlowConfigDao;
    @Resource
    private IAiAgentTaskScheduleDao aiAgentTaskScheduleDao;
    @Resource
    private IAiClientAdvisorDao aiClientAdvisorDao;
    @Resource
    private IAiClientApiDao aiClientApiDao;
    @Resource
    private IAiClientConfigDao aiClientConfigDao;
    @Resource
    private IAiClientDao aiClientDao;
    @Resource
    private IAiClientModelDao aiClientModelDao;
    @Resource
    private IAiClientRagOrderDao aiClientRagOrderDao;
    @Resource
    private IAiClientSystemPromptDao aiClientSystemPromptDao;
    @Resource
    private IAiClientToolMcpDao aiClientToolMcpDao;

    @Override
    @GetMapping("/get-data-statistics")
    public Response<DataStatisticsResponseDTO> getDataStatistics() {
        try {
            log.info("开始获取系统数据统计");
            
            // 统计各类数据数量
            long agentCount = (long) aiAgentDao.queryAll().size();
            long clientCount = (long) aiClientDao.queryAll().size();
            long mcpToolCount = (long) aiClientToolMcpDao.queryAll().size();
            long systemPromptCount = (long) aiClientSystemPromptDao.queryAll().size();
            long ragOrderCount = (long) aiClientRagOrderDao.queryAll().size();
            long advisorCount = (long) aiClientAdvisorDao.queryAll().size();
            long modelCount = (long) aiClientModelDao.queryAll().size();
            
            // 构建响应数据
            DataStatisticsResponseDTO responseDTO = DataStatisticsResponseDTO.builder()
                    .activeAgentCount(agentCount)
                    .clientCount(clientCount)
                    .mcpToolCount(mcpToolCount)
                    .systemPromptCount(systemPromptCount)
                    .ragOrderCount(ragOrderCount)
                    .advisorCount(advisorCount)
                    .modelCount(modelCount)
                    .todayRequestCount(0L) // 暂时设为0，后续可以添加请求统计功能
                    .successRate(95.5) // 暂时设为固定值，后续可以添加成功率统计功能
                    .runningTaskCount(0L) // 暂时设为0，后续可以添加任务统计功能
                    .build();
            
            log.info("系统数据统计获取成功：智能体数量={}, 客户端数量={}, MCP工具数量={}, 系统提示数量={}, 知识库数量={}, 顾问数量={}, 模型数量={}", 
                    agentCount, clientCount, mcpToolCount, systemPromptCount, ragOrderCount, advisorCount, modelCount);
            
            return Response.<DataStatisticsResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
                    
        } catch (Exception e) {
            log.error("获取系统数据统计失败", e);
            return Response.<DataStatisticsResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

}
