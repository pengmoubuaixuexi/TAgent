package cn.bugstack.ai.trigger.http.admin;

import cn.bugstack.ai.api.IAiClientAdvisorAdminService;
import cn.bugstack.ai.api.dto.AiClientAdvisorQueryRequestDTO;
import cn.bugstack.ai.api.dto.AiClientAdvisorRequestDTO;
import cn.bugstack.ai.api.dto.AiClientAdvisorResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.infrastructure.dao.IAiClientAdvisorDao;
import cn.bugstack.ai.infrastructure.dao.po.AiClientAdvisor;
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
 * 顾问配置管理控制器
 *
 * @author bugstack虫洞栈
 * @description 顾问配置管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ai-client-advisor")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AiClientAdvisorAdminController implements IAiClientAdvisorAdminService {

    @Resource
    private IAiClientAdvisorDao aiClientAdvisorDao;

    @Override
    @PostMapping("/create")
    public Response<Boolean> createAiClientAdvisor(@RequestBody AiClientAdvisorRequestDTO request) {
        try {
            log.info("创建顾问配置请求：{}", request);
            
            // DTO转PO
            AiClientAdvisor aiClientAdvisor = convertToAiClientAdvisor(request);
            aiClientAdvisor.setCreateTime(LocalDateTime.now());
            aiClientAdvisor.setUpdateTime(LocalDateTime.now());
            
            int result = aiClientAdvisorDao.insert(aiClientAdvisor);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("创建顾问配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @PutMapping("/update-by-id")
    public Response<Boolean> updateAiClientAdvisorById(@RequestBody AiClientAdvisorRequestDTO request) {
        try {
            log.info("根据ID更新顾问配置请求：{}", request);
            
            if (request.getId() == null) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("ID不能为空")
                        .data(false)
                        .build();
            }
            
            // DTO转PO
            AiClientAdvisor aiClientAdvisor = convertToAiClientAdvisor(request);
            aiClientAdvisor.setUpdateTime(LocalDateTime.now());
            
            int result = aiClientAdvisorDao.updateById(aiClientAdvisor);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据ID更新顾问配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @PutMapping("/update-by-advisor-id")
    public Response<Boolean> updateAiClientAdvisorByAdvisorId(@RequestBody AiClientAdvisorRequestDTO request) {
        try {
            log.info("根据顾问ID更新顾问配置请求：{}", request);
            
            if (!StringUtils.hasText(request.getAdvisorId())) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("顾问ID不能为空")
                        .data(false)
                        .build();
            }
            
            // DTO转PO
            AiClientAdvisor aiClientAdvisor = convertToAiClientAdvisor(request);
            aiClientAdvisor.setUpdateTime(LocalDateTime.now());
            
            int result = aiClientAdvisorDao.updateByAdvisorId(aiClientAdvisor);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据顾问ID更新顾问配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @DeleteMapping("/delete-by-id/{id}")
    public Response<Boolean> deleteAiClientAdvisorById(@PathVariable("id") Long id) {
        try {
            log.info("根据ID删除顾问配置请求：{}", id);
            
            int result = aiClientAdvisorDao.deleteById(id);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据ID删除顾问配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @DeleteMapping("/delete-by-advisor-id/{advisorId}")
    public Response<Boolean> deleteAiClientAdvisorByAdvisorId(@PathVariable("advisorId") String advisorId) {
        try {
            log.info("根据顾问ID删除顾问配置请求：{}", advisorId);
            
            int result = aiClientAdvisorDao.deleteByAdvisorId(advisorId);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据顾问ID删除顾问配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-id/{id}")
    public Response<AiClientAdvisorResponseDTO> queryAiClientAdvisorById(@PathVariable("id") Long id) {
        try {
            log.info("根据ID查询顾问配置请求：{}", id);
            
            AiClientAdvisor aiClientAdvisor = aiClientAdvisorDao.queryById(id);
            
            if (aiClientAdvisor == null) {
                return Response.<AiClientAdvisorResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info("未找到对应的顾问配置")
                        .data(null)
                        .build();
            }
            
            AiClientAdvisorResponseDTO responseDTO = convertToAiClientAdvisorResponseDTO(aiClientAdvisor);
            
            return Response.<AiClientAdvisorResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("根据ID查询顾问配置失败", e);
            return Response.<AiClientAdvisorResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-advisor-id/{advisorId}")
    public Response<AiClientAdvisorResponseDTO> queryAiClientAdvisorByAdvisorId(@PathVariable("advisorId") String advisorId) {
        try {
            log.info("根据顾问ID查询顾问配置请求：{}", advisorId);
            
            AiClientAdvisor aiClientAdvisor = aiClientAdvisorDao.queryByAdvisorId(advisorId);
            
            if (aiClientAdvisor == null) {
                return Response.<AiClientAdvisorResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info("未找到对应的顾问配置")
                        .data(null)
                        .build();
            }
            
            AiClientAdvisorResponseDTO responseDTO = convertToAiClientAdvisorResponseDTO(aiClientAdvisor);
            
            return Response.<AiClientAdvisorResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("根据顾问ID查询顾问配置失败", e);
            return Response.<AiClientAdvisorResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-enabled")
    public Response<List<AiClientAdvisorResponseDTO>> queryEnabledAiClientAdvisors() {
        try {
            log.info("查询所有启用的顾问配置");
            
            List<AiClientAdvisor> aiClientAdvisors = aiClientAdvisorDao.queryByStatus(1);
            
            List<AiClientAdvisorResponseDTO> responseDTOs = aiClientAdvisors.stream()
                    .map(this::convertToAiClientAdvisorResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientAdvisorResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("查询所有启用的顾问配置失败", e);
            return Response.<List<AiClientAdvisorResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-status/{status}")
    public Response<List<AiClientAdvisorResponseDTO>> queryAiClientAdvisorsByStatus(@PathVariable("status") Integer status) {
        try {
            log.info("根据状态查询顾问配置请求：{}", status);
            
            List<AiClientAdvisor> aiClientAdvisors = aiClientAdvisorDao.queryByStatus(status);
            
            List<AiClientAdvisorResponseDTO> responseDTOs = aiClientAdvisors.stream()
                    .map(this::convertToAiClientAdvisorResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientAdvisorResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("根据状态查询顾问配置失败", e);
            return Response.<List<AiClientAdvisorResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-type/{advisorType}")
    public Response<List<AiClientAdvisorResponseDTO>> queryAiClientAdvisorsByType(@PathVariable("advisorType") String advisorType) {
        try {
            log.info("根据顾问类型查询顾问配置请求：{}", advisorType);
            
            List<AiClientAdvisor> aiClientAdvisors = aiClientAdvisorDao.queryByAdvisorType(advisorType);
            
            List<AiClientAdvisorResponseDTO> responseDTOs = aiClientAdvisors.stream()
                    .map(this::convertToAiClientAdvisorResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientAdvisorResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("根据顾问类型查询顾问配置失败", e);
            return Response.<List<AiClientAdvisorResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @PostMapping("/query-list")
    public Response<List<AiClientAdvisorResponseDTO>> queryAiClientAdvisorList(@RequestBody AiClientAdvisorQueryRequestDTO request) {
        try {
            log.info("根据条件查询顾问配置列表请求：{}", request);
            
            // 根据查询条件获取数据
            List<AiClientAdvisor> aiClientAdvisors;
            
            if (StringUtils.hasText(request.getAdvisorId())) {
                // 如果有顾问ID，直接查询
                AiClientAdvisor advisor = aiClientAdvisorDao.queryByAdvisorId(request.getAdvisorId());
                aiClientAdvisors = advisor != null ? List.of(advisor) : List.of();
            } else if (StringUtils.hasText(request.getAdvisorType())) {
                // 如果有顾问类型，按类型查询
                aiClientAdvisors = aiClientAdvisorDao.queryByAdvisorType(request.getAdvisorType());
            } else if (request.getStatus() != null) {
                // 如果有状态，按状态查询
                aiClientAdvisors = aiClientAdvisorDao.queryByStatus(request.getStatus());
            } else {
                // 否则查询所有
                aiClientAdvisors = aiClientAdvisorDao.queryAll();
            }
            
            // 过滤条件
            List<AiClientAdvisor> filteredAdvisors = aiClientAdvisors.stream()
                    .filter(advisor -> {
                        // 顾问名称模糊查询
                        if (StringUtils.hasText(request.getAdvisorName()) && 
                            !advisor.getAdvisorName().contains(request.getAdvisorName())) {
                            return false;
                        }
                        // 状态过滤
                        if (request.getStatus() != null && !request.getStatus().equals(advisor.getStatus())) {
                            return false;
                        }
                        return true;
                    })
                    .collect(Collectors.toList());
            
            // 分页处理（简单实现）
            if (request.getPageNum() != null && request.getPageSize() != null) {
                int pageNum = Math.max(1, request.getPageNum());
                int pageSize = Math.max(1, request.getPageSize());
                int startIndex = (pageNum - 1) * pageSize;
                int endIndex = Math.min(startIndex + pageSize, filteredAdvisors.size());
                
                if (startIndex < filteredAdvisors.size()) {
                    filteredAdvisors = filteredAdvisors.subList(startIndex, endIndex);
                } else {
                    filteredAdvisors = List.of();
                }
            }
            
            List<AiClientAdvisorResponseDTO> responseDTOs = filteredAdvisors.stream()
                    .map(this::convertToAiClientAdvisorResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientAdvisorResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("根据条件查询顾问配置列表失败", e);
            return Response.<List<AiClientAdvisorResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-all")
    public Response<List<AiClientAdvisorResponseDTO>> queryAllAiClientAdvisors() {
        try {
            log.info("查询所有顾问配置");
            
            List<AiClientAdvisor> aiClientAdvisors = aiClientAdvisorDao.queryAll();
            
            List<AiClientAdvisorResponseDTO> responseDTOs = aiClientAdvisors.stream()
                    .map(this::convertToAiClientAdvisorResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientAdvisorResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("查询所有顾问配置失败", e);
            return Response.<List<AiClientAdvisorResponseDTO>>builder()
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
    private AiClientAdvisor convertToAiClientAdvisor(AiClientAdvisorRequestDTO requestDTO) {
        AiClientAdvisor aiClientAdvisor = new AiClientAdvisor();
        BeanUtils.copyProperties(requestDTO, aiClientAdvisor);
        return aiClientAdvisor;
    }

    /**
     * PO转响应DTO对象
     * @param aiClientAdvisor PO对象
     * @return 响应DTO
     */
    private AiClientAdvisorResponseDTO convertToAiClientAdvisorResponseDTO(AiClientAdvisor aiClientAdvisor) {
        AiClientAdvisorResponseDTO responseDTO = new AiClientAdvisorResponseDTO();
        BeanUtils.copyProperties(aiClientAdvisor, responseDTO);
        return responseDTO;
    }

}