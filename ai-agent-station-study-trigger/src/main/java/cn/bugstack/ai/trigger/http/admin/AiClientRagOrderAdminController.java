package cn.bugstack.ai.trigger.http.admin;

import cn.bugstack.ai.api.IAiClientRagOrderAdminService;
import cn.bugstack.ai.api.dto.AiClientRagOrderQueryRequestDTO;
import cn.bugstack.ai.api.dto.AiClientRagOrderRequestDTO;
import cn.bugstack.ai.api.dto.AiClientRagOrderResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.service.IRagService;
import cn.bugstack.ai.infrastructure.dao.IAiClientRagOrderDao;
import cn.bugstack.ai.infrastructure.dao.po.AiClientRagOrder;
import cn.bugstack.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库配置管理控制器
 *
 * @author bugstack虫洞栈
 * @description 知识库配置管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ai-client-rag-order")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AiClientRagOrderAdminController implements IAiClientRagOrderAdminService {

    @Resource
    private IAiClientRagOrderDao aiClientRagOrderDao;

    @Resource
    private IRagService ragService;

    @Override
    @PostMapping("/create")
    public Response<Boolean> createAiClientRagOrder(@RequestBody AiClientRagOrderRequestDTO request) {
        try {
            log.info("创建知识库配置请求：{}", request);
            
            // DTO转PO
            AiClientRagOrder aiClientRagOrder = convertToAiClientRagOrder(request);
            aiClientRagOrder.setCreateTime(LocalDateTime.now());
            aiClientRagOrder.setUpdateTime(LocalDateTime.now());
            
            int result = aiClientRagOrderDao.insert(aiClientRagOrder);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("创建知识库配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @PutMapping("/update-by-id")
    public Response<Boolean> updateAiClientRagOrderById(@RequestBody AiClientRagOrderRequestDTO request) {
        try {
            log.info("根据ID更新知识库配置请求：{}", request);
            
            if (request.getId() == null) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("ID不能为空")
                        .data(false)
                        .build();
            }
            
            // DTO转PO
            AiClientRagOrder aiClientRagOrder = convertToAiClientRagOrder(request);
            aiClientRagOrder.setUpdateTime(LocalDateTime.now());
            
            int result = aiClientRagOrderDao.updateById(aiClientRagOrder);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据ID更新知识库配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @PutMapping("/update-by-rag-id")
    public Response<Boolean> updateAiClientRagOrderByRagId(@RequestBody AiClientRagOrderRequestDTO request) {
        try {
            log.info("根据知识库ID更新知识库配置请求：{}", request);
            
            if (!StringUtils.hasText(request.getRagId())) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("知识库ID不能为空")
                        .data(false)
                        .build();
            }
            
            // DTO转PO
            AiClientRagOrder aiClientRagOrder = convertToAiClientRagOrder(request);
            aiClientRagOrder.setUpdateTime(LocalDateTime.now());
            
            int result = aiClientRagOrderDao.updateByRagId(aiClientRagOrder);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据知识库ID更新知识库配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @DeleteMapping("/delete-by-id/{id}")
    public Response<Boolean> deleteAiClientRagOrderById(@PathVariable("id") Long id) {
        try {
            log.info("根据ID删除知识库配置：{}", id);
            
            int result = aiClientRagOrderDao.deleteById(id);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据ID删除知识库配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @DeleteMapping("/delete-by-rag-id/{ragId}")
    public Response<Boolean> deleteAiClientRagOrderByRagId(@PathVariable("ragId") String ragId) {
        try {
            log.info("根据知识库ID删除知识库配置：{}", ragId);
            
            int result = aiClientRagOrderDao.deleteByRagId(ragId);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据知识库ID删除知识库配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-id/{id}")
    public Response<AiClientRagOrderResponseDTO> queryAiClientRagOrderById(@PathVariable("id") Long id) {
        try {
            log.info("根据ID查询知识库配置：{}", id);
            
            AiClientRagOrder aiClientRagOrder = aiClientRagOrderDao.queryById(id);
            if (aiClientRagOrder == null) {
                return Response.<AiClientRagOrderResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(null)
                        .build();
            }
            
            AiClientRagOrderResponseDTO responseDTO = convertToAiClientRagOrderResponseDTO(aiClientRagOrder);
            
            return Response.<AiClientRagOrderResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("根据ID查询知识库配置失败", e);
            return Response.<AiClientRagOrderResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-rag-id/{ragId}")
    public Response<AiClientRagOrderResponseDTO> queryAiClientRagOrderByRagId(@PathVariable("ragId") String ragId) {
        try {
            log.info("根据知识库ID查询知识库配置：{}", ragId);
            
            AiClientRagOrder aiClientRagOrder = aiClientRagOrderDao.queryByRagId(ragId);
            if (aiClientRagOrder == null) {
                return Response.<AiClientRagOrderResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(null)
                        .build();
            }
            
            AiClientRagOrderResponseDTO responseDTO = convertToAiClientRagOrderResponseDTO(aiClientRagOrder);
            
            return Response.<AiClientRagOrderResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("根据知识库ID查询知识库配置失败", e);
            return Response.<AiClientRagOrderResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-enabled")
    public Response<List<AiClientRagOrderResponseDTO>> queryEnabledAiClientRagOrders() {
        try {
            log.info("查询启用的知识库配置");
            
            List<AiClientRagOrder> aiClientRagOrders = aiClientRagOrderDao.queryEnabledRagOrders();
            List<AiClientRagOrderResponseDTO> responseDTOs = aiClientRagOrders.stream()
                    .map(this::convertToAiClientRagOrderResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientRagOrderResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("查询启用的知识库配置失败", e);
            return Response.<List<AiClientRagOrderResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-knowledge-tag/{knowledgeTag}")
    public Response<List<AiClientRagOrderResponseDTO>> queryAiClientRagOrdersByKnowledgeTag(@PathVariable("knowledgeTag") String knowledgeTag) {
        try {
            log.info("根据知识标签查询知识库配置：{}", knowledgeTag);
            
            List<AiClientRagOrder> aiClientRagOrders = aiClientRagOrderDao.queryByKnowledgeTag(knowledgeTag);
            List<AiClientRagOrderResponseDTO> responseDTOs = aiClientRagOrders.stream()
                    .map(this::convertToAiClientRagOrderResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientRagOrderResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("根据知识标签查询知识库配置失败", e);
            return Response.<List<AiClientRagOrderResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-status/{status}")
    public Response<List<AiClientRagOrderResponseDTO>> queryAiClientRagOrdersByStatus(@PathVariable("status") Integer status) {
        try {
            log.info("根据状态查询知识库配置：{}", status);
            
            // 这里需要根据实际的DAO方法实现，如果没有可以通过queryAll然后过滤
            List<AiClientRagOrder> aiClientRagOrders = aiClientRagOrderDao.queryAll();
            List<AiClientRagOrderResponseDTO> responseDTOs = aiClientRagOrders.stream()
                    .filter(order -> order.getStatus().equals(status))
                    .map(this::convertToAiClientRagOrderResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientRagOrderResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("根据状态查询知识库配置失败", e);
            return Response.<List<AiClientRagOrderResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @PostMapping("/query-list")
    public Response<List<AiClientRagOrderResponseDTO>> queryAiClientRagOrderList(@RequestBody AiClientRagOrderQueryRequestDTO request) {
        try {
            log.info("分页查询知识库配置列表：{}", request);
            
            // 这里简化实现，实际项目中可能需要实现分页查询
            List<AiClientRagOrder> aiClientRagOrders = aiClientRagOrderDao.queryAll();
            
            // 根据查询条件过滤
            List<AiClientRagOrder> filteredOrders = aiClientRagOrders.stream()
                    .filter(order -> {
                        boolean match = true;
                        if (StringUtils.hasText(request.getRagId())) {
                            match = match && order.getRagId().contains(request.getRagId());
                        }
                        if (StringUtils.hasText(request.getRagName())) {
                            match = match && order.getRagName().contains(request.getRagName());
                        }
                        if (StringUtils.hasText(request.getKnowledgeTag())) {
                            match = match && order.getKnowledgeTag().contains(request.getKnowledgeTag());
                        }
                        if (request.getStatus() != null) {
                            match = match && order.getStatus().equals(request.getStatus());
                        }
                        return match;
                    })
                    .collect(Collectors.toList());
            
            // 简单分页处理
            if (request.getPageNum() != null && request.getPageSize() != null) {
                int start = (request.getPageNum() - 1) * request.getPageSize();
                int end = Math.min(start + request.getPageSize(), filteredOrders.size());
                if (start < filteredOrders.size()) {
                    filteredOrders = filteredOrders.subList(start, end);
                } else {
                    filteredOrders.clear();
                }
            }
            
            List<AiClientRagOrderResponseDTO> responseDTOs = filteredOrders.stream()
                    .map(this::convertToAiClientRagOrderResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientRagOrderResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("分页查询知识库配置列表失败", e);
            return Response.<List<AiClientRagOrderResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-all")
    public Response<List<AiClientRagOrderResponseDTO>> queryAllAiClientRagOrders() {
        try {
            log.info("查询所有知识库配置");
            
            List<AiClientRagOrder> aiClientRagOrders = aiClientRagOrderDao.queryAll();
            List<AiClientRagOrderResponseDTO> responseDTOs = aiClientRagOrders.stream()
                    .map(this::convertToAiClientRagOrderResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientRagOrderResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("查询所有知识库配置失败", e);
            return Response.<List<AiClientRagOrderResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    /**
     * DTO转PO
     */
    private AiClientRagOrder convertToAiClientRagOrder(AiClientRagOrderRequestDTO requestDTO) {
        AiClientRagOrder aiClientRagOrder = new AiClientRagOrder();
        BeanUtils.copyProperties(requestDTO, aiClientRagOrder);
        return aiClientRagOrder;
    }

    @Override
    @RequestMapping(value = "file/upload", method = RequestMethod.POST, headers = "content-type=multipart/form-data")
    public Response<Boolean> uploadRagFile(@RequestParam(value = "name", required = false) String name, @RequestParam("tag") String tag, @RequestParam("files") List<MultipartFile> files) {
        try {
            String userId = org.slf4j.MDC.get("userId");
            log.info("上传知识库，请求 name={} userId={}", name, userId);
            ragService.storeRagFile(name, tag, files, userId);
            Response<Boolean> response = Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
            log.info("上传知识库，结果 {} {}", name, response);
            return response;
        } catch (Exception e) {
            log.error("上传知识库，异常 {}", name, e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    /**
     * PO转DTO
     */
    private AiClientRagOrderResponseDTO convertToAiClientRagOrderResponseDTO(AiClientRagOrder aiClientRagOrder) {
        AiClientRagOrderResponseDTO responseDTO = new AiClientRagOrderResponseDTO();
        BeanUtils.copyProperties(aiClientRagOrder, responseDTO);
        return responseDTO;
    }

}