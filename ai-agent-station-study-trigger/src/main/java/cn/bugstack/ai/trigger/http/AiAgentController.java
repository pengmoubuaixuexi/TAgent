package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.IAiAgentService;
import cn.bugstack.ai.api.dto.AiAgentResponseDTO;
import cn.bugstack.ai.api.dto.AgentFeedbackRequestDTO;
import cn.bugstack.ai.api.dto.ArmoryAgentRequestDTO;
import cn.bugstack.ai.api.dto.ArmoryApiRequestDTO;
import cn.bugstack.ai.api.dto.AutoAgentRequestDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.IAgentDispatchService;
import cn.bugstack.ai.domain.agent.service.IArmoryService;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.ai.domain.agent.service.security.PiiMasker;
import cn.bugstack.ai.types.common.Constants;
import cn.bugstack.ai.types.enums.ResponseCode;
import com.alibaba.fastjson.JSON;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * AutoAgent 自动智能对话体
 *
 * @author TAgent
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class AiAgentController implements IAiAgentService {

    @Resource
    private IAgentDispatchService agentDispatchService;

    @Resource
    private IArmoryService armoryService;

    @RequestMapping(value = "auto_agent", method = RequestMethod.POST)
    public ResponseBodyEmitter autoAgent(@RequestBody AutoAgentRequestDTO request, HttpServletResponse response) {
        // 业务维度追踪字段：agentId 定位"哪个智能体"，sessionId 串起同一用户多轮对话。
        // MdcTraceFilter 已写 requestId（UUID），Micrometer Tracing 已写 traceId/spanId（W3C），此处只补业务字段。
        if (request != null) {
            if (request.getAiAgentId() != null) MDC.put("agentId", String.valueOf(request.getAiAgentId()));
            if (request.getSessionId() != null) MDC.put("sessionId", request.getSessionId());
        }
        log.info("AutoAgent流式执行请求开始，请求信息：{}", JSON.toJSONString(request));

        try {
            // 设置SSE响应头
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            // 2026-05-07：禁用 nginx / 代理的输出缓冲（即使没用 nginx 也不影响），
            // 强制 chunked transfer encoding 让 token 立即送达浏览器
            response.setHeader("X-Accel-Buffering", "no");
            response.setBufferSize(0);  // 关 servlet 容器的输出缓冲

            // 1. 创建流式输出对象
            ResponseBodyEmitter emitter = new ResponseBodyEmitter(Long.MAX_VALUE);

            // 2026-05-07 #2 TTFT 优化：emitter 创建后立即吐一个 ack 事件，
            // 让浏览器 Network 面板第一个 chunk 在 < 50ms 出现。后续 IntentRouter / advisor
            // 任何阻塞都不会影响"已连接"的视觉反馈，前端立刻把"思考中..."替换为已连接动画
            try {
                String ackSid = request != null && request.getSessionId() != null ? request.getSessionId() : "";
                emitter.send("event: ack\ndata: {\"sessionId\":\"" + ackSid + "\",\"timestamp\":"
                        + System.currentTimeMillis() + "}\n\n");
            } catch (Exception ackEx) {
                log.debug("ack 发送失败（不影响主流程）: {}", ackEx.getMessage());
            }

            // P1.2.3 多租户隔离：userId/tenantId 来源优先级：
            //   DTO 字段 > MDC（MdcTraceFilter 已从 X-User-Id / X-Tenant-Id header 解析） > fallback
            String userId = coalesce(
                    request.getUserId(),
                    MDC.get("userId"),
                    request.getSessionId());
            String tenantId = coalesce(
                    request.getTenantId(),
                    MDC.get("tenantId"),
                    "default");

            // 写回 MDC，确保 ThreadPoolConfig.wrap() 捕获到并接力到 worker 线程
            MDC.put("userId", userId);
            MDC.put("tenantId", tenantId);

            // 2. 构建执行命令实体
            ExecuteCommandEntity executeCommandEntity = ExecuteCommandEntity.builder()
                    .aiAgentId(request.getAiAgentId())
                    .message(request.getMessage())
                    .sessionId(request.getSessionId())
                    .userId(userId)
                    .tenantId(tenantId)
                    .maxStep(request.getMaxStep())
                    .build();

            // 3. 调度处理
            agentDispatchService.dispatch(executeCommandEntity, emitter);

            return emitter;

        } catch (Exception e) {
            log.error("AutoAgent请求处理异常：{}", e.getMessage(), e);
            ResponseBodyEmitter errorEmitter = new ResponseBodyEmitter();
            try {
                errorEmitter.send("请求处理异常：" + e.getMessage());
                errorEmitter.complete();
            } catch (Exception ex) {
                log.error("发送错误信息失败：{}", ex.getMessage(), ex);
            }
            return errorEmitter;
        }
    }

    @RequestMapping(value = "armory_agent", method = RequestMethod.POST)
    @Override
    public Response<Boolean> armoryAgent(@RequestBody ArmoryAgentRequestDTO request) {
        log.info("装配智能体请求开始，请求信息：{}", JSON.toJSONString(request));

        try {
            // 参数校验
            if (request == null || request.getAgentId() == null || request.getAgentId().trim().isEmpty()) {
                log.warn("装配智能体请求参数无效：agentId为空");
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("agentId不能为空")
                        .data(false)
                        .build();
            }
            
            // 调用装配服务
            armoryService.acceptArmoryAgent(request.getAgentId());
            
            log.info("装配智能体成功，agentId：{}", request.getAgentId());
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("装配成功")
                    .data(true)
                    .build();
                    
        } catch (Exception e) {
            log.error("装配智能体失败，agentId：{}，错误信息：{}", 
                    request != null ? request.getAgentId() : "null", e.getMessage(), e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("装配失败：" + e.getMessage())
                    .data(false)
                    .build();
        }
    }

    @RequestMapping(value = "query_available_agents", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentResponseDTO>> queryAvailableAgents() {
        log.info("查询可用智能体列表请求开始");

        try {
            // 调用装配服务查询可用智能体
            List<AiAgentVO> aiAgentVOList = armoryService.queryAvailableAgents();
            
            // 转换为响应DTO
            List<AiAgentResponseDTO> responseList = new ArrayList<>();
            for (AiAgentVO aiAgentVO : aiAgentVOList) {
                AiAgentResponseDTO responseDTO = AiAgentResponseDTO.builder()
                        .agentId(aiAgentVO.getAgentId())
                        .agentName(aiAgentVO.getAgentName())
                        .description(aiAgentVO.getDescription())
                        .channel(aiAgentVO.getChannel())
                        .strategy(aiAgentVO.getStrategy())
                        .status(aiAgentVO.getStatus())
                        .build();
                responseList.add(responseDTO);
            }
            
            log.info("查询可用智能体列表成功，共{}个智能体", responseList.size());
            return Response.<List<AiAgentResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("查询成功")
                    .data(responseList)
                    .build();
                    
        } catch (Exception e) {
            log.error("查询可用智能体列表失败，错误信息：{}", e.getMessage(), e);
            return Response.<List<AiAgentResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("查询失败：" + e.getMessage())
                    .data(new ArrayList<>())
                    .build();
        }
    }

    @RequestMapping(value = "armory_api", method = RequestMethod.POST)
    @Override
    public Response<Boolean> armoryApi(@RequestBody ArmoryApiRequestDTO request) {
        log.info("装配API请求开始，请求信息：{}", JSON.toJSONString(request));

        try {
            // 参数校验
            if (request == null || request.getApiId() == null || request.getApiId().trim().isEmpty()) {
                log.warn("装配API请求参数无效：apiId为空");
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("apiId不能为空")
                        .data(false)
                        .build();
            }
            
            // 调用装配服务
            armoryService.acceptArmoryAgentClientModelApi(request.getApiId());
            
            log.info("装配API成功，apiId：{}", request.getApiId());
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("装配成功")
                    .data(true)
                    .build();
                    
        } catch (Exception e) {
            log.error("装配API失败，apiId：{}，错误信息：{}", 
                    request != null ? request.getApiId() : "null", e.getMessage(), e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("装配失败：" + e.getMessage())
                    .data(false)
                    .build();
        }
    }

    /**
     * P2.6 15.2 用户反馈回路：前端 thumbs up/down 通过此端点入库。
     */
    @RequestMapping(value = "feedback", method = RequestMethod.POST)
    public Response<Boolean> submitFeedback(@RequestBody AgentFeedbackRequestDTO request) {
        log.info("用户反馈: traceId={} rating={} comment={}", request.getTraceId(), request.getRating(), request.getComment());
        // 写入 Logstash JSON，ELK 按 traceId 关联到原始请求
        MDC.put("feedbackRating", request.getRating() != null ? request.getRating() : "n/a");
        MDC.put("feedbackTraceId", request.getTraceId() != null ? request.getTraceId() : "n/a");
        try {
            log.info("Feedback received: rating={} traceId={} sessionId={} comment={}",
                    request.getRating(), request.getTraceId(), request.getSessionId(),
                    request.getComment() != null ? request.getComment().substring(0, Math.min(200, request.getComment().length())) : "");
        } finally {
            MDC.remove("feedbackRating");
            MDC.remove("feedbackTraceId");
        }
        return Response.<Boolean>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("反馈已记录")
                .data(true)
                .build();
    }

    /**
     * P2.2 11.5 Human-in-the-Loop：前端审批敏感工具调用。
     */
    @Resource
    private cn.bugstack.ai.domain.agent.service.security.HumanApprovalGate humanApprovalGate;

    @RequestMapping(value = "approval", method = RequestMethod.POST)
    public Response<Boolean> submitApproval(@RequestBody Map<String, Object> request) {
        String approvalId = (String) request.get("approvalId");
        Boolean approved = (Boolean) request.get("approved");
        if (approvalId == null || approved == null) {
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("缺少 approvalId 或 approved")
                    .data(false)
                    .build();
        }
        humanApprovalGate.resolveApproval(approvalId, approved);
        return Response.<Boolean>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(approved ? "已批准" : "已拒绝")
                .data(true)
                .build();
    }

    /** 取第一个非空非空白的字符串值 */
    private static String coalesce(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** 查用户的会话历史列表 */
    @RequestMapping(value = "conversations", method = RequestMethod.GET)
    public Response<List<Map<String, Object>>> getUserConversations(@RequestParam("userId") String userId) {
        try {
            cn.bugstack.ai.infrastructure.dao.IAiChatMemoryDao dao =
                    applicationContext.getBean(cn.bugstack.ai.infrastructure.dao.IAiChatMemoryDao.class);
            List<Map<String, Object>> conversations = dao.findConversationsByUserId(userId, 50);
            // 展示点脱敏：DB 里 firstMessage 是原文 PII，回前端前掩码
            for (Map<String, Object> c : conversations) {
                Object first = c.get("firstMessage");
                if (first instanceof String s) {
                    c.put("firstMessage", PiiMasker.mask(s));
                }
            }
            return Response.<List<Map<String, Object>>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(conversations)
                    .build();
        } catch (Exception e) {
            log.error("查询会话历史失败", e);
            return Response.<List<Map<String, Object>>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .data(List.of())
                    .build();
        }
    }

    /**
     * 查某个会话的消息。支持两种模式：
     * <ul>
     *   <li><b>不传 limit</b>：返回会话全部消息（向后兼容老调用）</li>
     *   <li><b>传 limit</b>：游标分页。首次不传 beforeId 取最近 N 条；后续传 beforeId（本批最早消息 id）取更早 N 条</li>
     * </ul>
     * 返回结构在分页模式下含 oldestId（本批最早 id，作为下次 beforeId）和 hasMore（是否还有更早消息）。
     */
    @RequestMapping(value = "conversation_messages", method = RequestMethod.GET)
    public Response<Map<String, Object>> getConversationMessages(
            @RequestParam("conversationId") String conversationId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "beforeId", required = false) Long beforeId) {
        try {
            cn.bugstack.ai.infrastructure.dao.IAiChatMemoryDao dao =
                    applicationContext.getBean(cn.bugstack.ai.infrastructure.dao.IAiChatMemoryDao.class);

            List<cn.bugstack.ai.infrastructure.dao.po.AiChatMemory> rows;
            boolean paged = limit != null && limit > 0;
            if (paged) {
                // 取一条多于 limit 的数据用来判断 hasMore，最后只返回 limit 条
                int fetch = limit + 1;
                rows = dao.findByConversationIdPaged(conversationId, beforeId, fetch);
                // SQL 是 DESC，反转回 ASC（前端按时间正序展示）
                java.util.Collections.reverse(rows);
            } else {
                rows = dao.findByConversationIdFull(conversationId);
            }

            boolean hasMore = false;
            if (paged && rows.size() > limit) {
                // 多取的 1 条出现在 ASC 序列的最前（最早），剔除并标记 hasMore
                rows = rows.subList(1, rows.size());
                hasMore = true;
            }

            List<Map<String, Object>> messages = new java.util.ArrayList<>(rows.size());
            Long oldestId = null;
            for (var r : rows) {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("id", r.getId());
                m.put("messageType", r.getMessageType());
                // 展示点脱敏：DB 里存的是 USER + ASSISTANT 原文
                m.put("content", PiiMasker.mask(r.getContent()));
                m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
                messages.add(m);
                if (oldestId == null) oldestId = r.getId();
            }

            Map<String, Object> data = new java.util.HashMap<>();
            data.put("messages", messages);
            if (paged) {
                data.put("oldestId", oldestId);
                data.put("hasMore", hasMore);
            }
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
        } catch (Exception e) {
            log.error("查询会话消息失败", e);
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode()).info(e.getMessage()).data(java.util.Map.of()).build();
        }
    }

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository agentRepository;

    /**
     * 查询 Agent 完整配置详情（供前端 agent-config 页面使用）
     * 返回：agent 信息 + 所有 client 的 model/prompt/advisor/MCP 配置
     */
    @RequestMapping(value = "query_agent_config/{agentId}", method = RequestMethod.GET)
    public Response<Map<String, Object>> queryAgentConfig(@PathVariable String agentId) {
        try {
            // 1. Agent 基本信息
            AiAgentVO agent = agentRepository.queryAiAgentByAgentId(agentId);
            if (agent == null) {
                return Response.<Map<String, Object>>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("Agent 不存在: " + agentId)
                        .data(null)
                        .build();
            }

            // 2. Agent → Client 流程配置
            List<cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO> flowConfigs =
                    agentRepository.queryAiAgentClientsByAgentId(agentId);
            List<String> clientIds = flowConfigs.stream()
                    .map(cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO::getClientId)
                    .toList();

            // 3. Client 详情
            List<cn.bugstack.ai.domain.agent.model.valobj.AiClientVO> clients =
                    clientIds.isEmpty() ? List.of() : agentRepository.AiClientVOByClientIds(clientIds);

            // 4. Model 详情
            List<cn.bugstack.ai.domain.agent.model.valobj.AiClientModelVO> models =
                    clientIds.isEmpty() ? List.of() : agentRepository.AiClientModelVOByClientIds(clientIds);

            // 5. Prompt 详情
            List<cn.bugstack.ai.domain.agent.model.valobj.AiClientSystemPromptVO> prompts =
                    clientIds.isEmpty() ? List.of() : agentRepository.AiClientSystemPromptVOByClientIds(clientIds);

            // 6. Advisor 详情
            List<cn.bugstack.ai.domain.agent.model.valobj.AiClientAdvisorVO> advisors =
                    clientIds.isEmpty() ? List.of() : agentRepository.AiClientAdvisorVOByClientIds(clientIds);

            // 7. MCP 工具详情
            List<cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO> mcps =
                    clientIds.isEmpty() ? List.of() : agentRepository.AiClientToolMcpVOByClientIds(clientIds);

            // 组装返回
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("agent", agent);
            result.put("flowConfigs", flowConfigs);
            result.put("clients", clients);
            result.put("models", models);
            result.put("prompts", prompts);
            result.put("advisors", advisors);
            result.put("mcps", mcps);

            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("查询成功")
                    .data(result)
                    .build();
        } catch (Exception e) {
            log.error("查询Agent配置详情失败: agentId={}", agentId, e);
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .data(null)
                    .build();
        }
    }

}