package cn.bugstack.ai.trigger.http.admin;

import cn.bugstack.ai.api.IAiClientToolMcpAdminService;
import cn.bugstack.ai.api.dto.AiClientToolMcpQueryRequestDTO;
import cn.bugstack.ai.api.dto.AiClientToolMcpRequestDTO;
import cn.bugstack.ai.api.dto.AiClientToolMcpResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.ai.infrastructure.dao.IAiClientToolMcpDao;
import cn.bugstack.ai.infrastructure.dao.po.AiClientToolMcp;
import cn.bugstack.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MCP客户端配置管理控制器
 *
 * @author bugstack虫洞栈
 * @description MCP客户端配置管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ai-client-tool-mcp")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AiClientToolMcpAdminController implements IAiClientToolMcpAdminService {

    @Resource
    private IAiClientToolMcpDao aiClientToolMcpDao;

    @Resource
    private McpToolCatalogService mcpToolCatalogService;

    @Override
    @PostMapping("/create")
    public Response<Boolean> createAiClientToolMcp(@RequestBody AiClientToolMcpRequestDTO request) {
        try {
            log.info("创建MCP客户端配置请求：{}", request);
            
            // DTO转PO
            AiClientToolMcp aiClientToolMcp = convertToAiClientToolMcp(request);
            aiClientToolMcp.setCreateTime(LocalDateTime.now());
            aiClientToolMcp.setUpdateTime(LocalDateTime.now());
            
            int result = aiClientToolMcpDao.insert(aiClientToolMcp);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("创建MCP客户端配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @PutMapping("/update-by-id")
    public Response<Boolean> updateAiClientToolMcpById(@RequestBody AiClientToolMcpRequestDTO request) {
        try {
            log.info("根据ID更新MCP客户端配置请求：{}", request);
            
            if (request.getId() == null) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("ID不能为空")
                        .data(false)
                        .build();
            }
            
            // DTO转PO
            AiClientToolMcp aiClientToolMcp = convertToAiClientToolMcp(request);
            aiClientToolMcp.setUpdateTime(LocalDateTime.now());
            
            int result = aiClientToolMcpDao.updateById(aiClientToolMcp);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据ID更新MCP客户端配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @PutMapping("/update-by-mcp-id")
    public Response<Boolean> updateAiClientToolMcpByMcpId(@RequestBody AiClientToolMcpRequestDTO request) {
        try {
            log.info("根据MCP ID更新MCP客户端配置请求：{}", request);
            
            if (!StringUtils.hasText(request.getMcpId())) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("MCP ID不能为空")
                        .data(false)
                        .build();
            }
            
            // DTO转PO
            AiClientToolMcp aiClientToolMcp = convertToAiClientToolMcp(request);
            aiClientToolMcp.setUpdateTime(LocalDateTime.now());
            
            int result = aiClientToolMcpDao.updateByMcpId(aiClientToolMcp);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据MCP ID更新MCP客户端配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @DeleteMapping("/delete-by-id/{id}")
    public Response<Boolean> deleteAiClientToolMcpById(@PathVariable("id") Long id) {
        try {
            log.info("根据ID删除MCP客户端配置：{}", id);
            
            int result = aiClientToolMcpDao.deleteById(id);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据ID删除MCP客户端配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @DeleteMapping("/delete-by-mcp-id/{mcpId}")
    public Response<Boolean> deleteAiClientToolMcpByMcpId(@PathVariable("mcpId") String mcpId) {
        try {
            log.info("根据MCP ID删除MCP客户端配置：{}", mcpId);
            
            int result = aiClientToolMcpDao.deleteByMcpId(mcpId);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据MCP ID删除MCP客户端配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-id/{id}")
    public Response<AiClientToolMcpResponseDTO> queryAiClientToolMcpById(@PathVariable("id") Long id) {
        try {
            log.info("根据ID查询MCP客户端配置：{}", id);
            
            AiClientToolMcp aiClientToolMcp = aiClientToolMcpDao.queryById(id);
            
            if (aiClientToolMcp == null) {
                return Response.<AiClientToolMcpResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(null)
                        .build();
            }
            
            AiClientToolMcpResponseDTO responseDTO = convertToAiClientToolMcpResponseDTO(aiClientToolMcp);
            
            return Response.<AiClientToolMcpResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("根据ID查询MCP客户端配置失败", e);
            return Response.<AiClientToolMcpResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-mcp-id/{mcpId}")
    public Response<AiClientToolMcpResponseDTO> queryAiClientToolMcpByMcpId(@PathVariable("mcpId") String mcpId) {
        try {
            log.info("根据MCP ID查询MCP客户端配置：{}", mcpId);
            
            AiClientToolMcp aiClientToolMcp = aiClientToolMcpDao.queryByMcpId(mcpId);
            
            if (aiClientToolMcp == null) {
                return Response.<AiClientToolMcpResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(null)
                        .build();
            }
            
            AiClientToolMcpResponseDTO responseDTO = convertToAiClientToolMcpResponseDTO(aiClientToolMcp);
            
            return Response.<AiClientToolMcpResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("根据MCP ID查询MCP客户端配置失败", e);
            return Response.<AiClientToolMcpResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-all")
    public Response<List<AiClientToolMcpResponseDTO>> queryAllAiClientToolMcps() {
        try {
            log.info("查询所有MCP客户端配置");
            
            List<AiClientToolMcp> aiClientToolMcps = aiClientToolMcpDao.queryAll();
            
            List<AiClientToolMcpResponseDTO> responseDTOs = aiClientToolMcps.stream()
                    .map(this::convertToAiClientToolMcpResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("查询所有MCP客户端配置失败", e);
            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-status/{status}")
    public Response<List<AiClientToolMcpResponseDTO>> queryAiClientToolMcpsByStatus(@PathVariable("status") Integer status) {
        try {
            log.info("根据状态查询MCP客户端配置：{}", status);
            
            List<AiClientToolMcp> aiClientToolMcps = aiClientToolMcpDao.queryByStatus(status);
            
            List<AiClientToolMcpResponseDTO> responseDTOs = aiClientToolMcps.stream()
                    .map(this::convertToAiClientToolMcpResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("根据状态查询MCP客户端配置失败", e);
            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-transport-type/{transportType}")
    public Response<List<AiClientToolMcpResponseDTO>> queryAiClientToolMcpsByTransportType(@PathVariable("transportType") String transportType) {
        try {
            log.info("根据传输类型查询MCP客户端配置：{}", transportType);
            
            List<AiClientToolMcp> aiClientToolMcps = aiClientToolMcpDao.queryByTransportType(transportType);
            
            List<AiClientToolMcpResponseDTO> responseDTOs = aiClientToolMcps.stream()
                    .map(this::convertToAiClientToolMcpResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("根据传输类型查询MCP客户端配置失败", e);
            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-enabled")
    public Response<List<AiClientToolMcpResponseDTO>> queryEnabledAiClientToolMcps() {
        try {
            log.info("查询启用的MCP客户端配置");
            
            List<AiClientToolMcp> aiClientToolMcps = aiClientToolMcpDao.queryEnabledMcps();
            
            List<AiClientToolMcpResponseDTO> responseDTOs = aiClientToolMcps.stream()
                    .map(this::convertToAiClientToolMcpResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("查询启用的MCP客户端配置失败", e);
            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @PostMapping("/tool-catalog/refresh")
    public Response<java.util.Map<String, Object>> refreshAllMcpToolCatalog() {
        try {
            int toolCount = mcpToolCatalogService.refreshEnabledMcpCatalog();
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("scope", "enabled");
            data.put("toolCount", toolCount);
            return Response.<java.util.Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("刷新 MCP 工具目录失败", e);
            return Response.<java.util.Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @PostMapping("/tool-catalog/refresh/{mcpId}")
    public Response<java.util.Map<String, Object>> refreshMcpToolCatalog(@PathVariable("mcpId") String mcpId) {
        try {
            int toolCount = mcpToolCatalogService.refreshMcpCatalog(mcpId);
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("scope", "single");
            data.put("mcpId", mcpId);
            data.put("toolCount", toolCount);
            return Response.<java.util.Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("刷新 MCP 工具目录失败 mcpId={}", mcpId, e);
            return Response.<java.util.Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @PostMapping("/query-list")
    public Response<List<AiClientToolMcpResponseDTO>> queryAiClientToolMcpList(@RequestBody AiClientToolMcpQueryRequestDTO request) {
        try {
            log.info("根据查询条件查询MCP客户端配置列表：{}", request);
            
            // 根据查询条件调用不同的DAO方法
            List<AiClientToolMcp> aiClientToolMcps;
            
            if (StringUtils.hasText(request.getMcpId())) {
                // 根据MCP ID查询
                AiClientToolMcp single = aiClientToolMcpDao.queryByMcpId(request.getMcpId());
                aiClientToolMcps = single != null ? List.of(single) : List.of();
            } else if (request.getStatus() != null) {
                // 根据状态查询
                aiClientToolMcps = aiClientToolMcpDao.queryByStatus(request.getStatus());
            } else if (StringUtils.hasText(request.getTransportType())) {
                // 根据传输类型查询
                aiClientToolMcps = aiClientToolMcpDao.queryByTransportType(request.getTransportType());
            } else {
                // 查询所有
                aiClientToolMcps = aiClientToolMcpDao.queryAll();
            }
            
            // 如果有MCP名称条件，进行过滤
            if (StringUtils.hasText(request.getMcpName())) {
                aiClientToolMcps = aiClientToolMcps.stream()
                        .filter(mcp -> mcp.getMcpName() != null && 
                                      mcp.getMcpName().contains(request.getMcpName()))
                        .collect(Collectors.toList());
            }
            
            List<AiClientToolMcpResponseDTO> responseDTOs = aiClientToolMcps.stream()
                    .map(this::convertToAiClientToolMcpResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("根据查询条件查询MCP客户端配置列表失败", e);
            return Response.<List<AiClientToolMcpResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    /**
     * DTO转PO对象
     * @param requestDTO 请求DTO
     * @return PO对象
     */
    private AiClientToolMcp convertToAiClientToolMcp(AiClientToolMcpRequestDTO requestDTO) {
        AiClientToolMcp aiClientToolMcp = new AiClientToolMcp();
        BeanUtils.copyProperties(requestDTO, aiClientToolMcp);
        return aiClientToolMcp;
    }

    /**
     * PO转响应DTO对象
     * @param aiClientToolMcp PO对象
     * @return 响应DTO
     */
    private AiClientToolMcpResponseDTO convertToAiClientToolMcpResponseDTO(AiClientToolMcp aiClientToolMcp) {
        AiClientToolMcpResponseDTO responseDTO = new AiClientToolMcpResponseDTO();
        BeanUtils.copyProperties(aiClientToolMcp, responseDTO);
        return responseDTO;
    }

}
