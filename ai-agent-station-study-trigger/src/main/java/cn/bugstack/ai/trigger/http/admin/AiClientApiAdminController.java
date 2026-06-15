package cn.bugstack.ai.trigger.http.admin;

import cn.bugstack.ai.api.IAiClientApiAdminService;
import cn.bugstack.ai.api.dto.AiClientApiQueryRequestDTO;
import cn.bugstack.ai.api.dto.AiClientApiRequestDTO;
import cn.bugstack.ai.api.dto.AiClientApiResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.infrastructure.dao.IAiClientApiDao;
import cn.bugstack.ai.infrastructure.dao.po.AiClientApi;
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
 * AI客户端API配置管理控制器
 *
 * @author bugstack虫洞栈
 * @description AI客户端API配置管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ai-client-api")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AiClientApiAdminController implements IAiClientApiAdminService {

    @Resource
    private IAiClientApiDao aiClientApiDao;

    @Override
    @PostMapping("/create")
    public Response<Boolean> createAiClientApi(@RequestBody AiClientApiRequestDTO request) {
        try {
            log.info("创建AI客户端API配置请求：{}", request);
            
            // DTO转PO
            AiClientApi aiClientApi = convertToAiClientApi(request);
            aiClientApi.setCreateTime(LocalDateTime.now());
            aiClientApi.setUpdateTime(LocalDateTime.now());
            
            int result = aiClientApiDao.insert(aiClientApi);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("创建AI客户端API配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @PutMapping("/update-by-id")
    public Response<Boolean> updateAiClientApiById(@RequestBody AiClientApiRequestDTO request) {
        try {
            log.info("根据ID更新AI客户端API配置请求：{}", request);
            
            if (request.getId() == null) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("ID不能为空")
                        .data(false)
                        .build();
            }
            
            // DTO转PO
            AiClientApi aiClientApi = convertToAiClientApi(request);
            aiClientApi.setUpdateTime(LocalDateTime.now());
            
            int result = aiClientApiDao.updateById(aiClientApi);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据ID更新AI客户端API配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @PutMapping("/update-by-api-id")
    public Response<Boolean> updateAiClientApiByApiId(@RequestBody AiClientApiRequestDTO request) {
        try {
            log.info("根据API ID更新AI客户端API配置请求：{}", request);
            
            if (!StringUtils.hasText(request.getApiId())) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("API ID不能为空")
                        .data(false)
                        .build();
            }
            
            // DTO转PO
            AiClientApi aiClientApi = convertToAiClientApi(request);
            aiClientApi.setUpdateTime(LocalDateTime.now());
            
            int result = aiClientApiDao.updateByApiId(aiClientApi);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据API ID更新AI客户端API配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @DeleteMapping("/delete-by-id/{id}")
    public Response<Boolean> deleteAiClientApiById(@PathVariable("id") Long id) {
        try {
            log.info("根据ID删除AI客户端API配置请求：{}", id);
            
            int result = aiClientApiDao.deleteById(id);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据ID删除AI客户端API配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @DeleteMapping("/delete-by-api-id/{apiId}")
    public Response<Boolean> deleteAiClientApiByApiId(@PathVariable("apiId") String apiId) {
        try {
            log.info("根据API ID删除AI客户端API配置请求：{}", apiId);
            
            int result = aiClientApiDao.deleteByApiId(apiId);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据API ID删除AI客户端API配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-id/{id}")
    public Response<AiClientApiResponseDTO> queryAiClientApiById(@PathVariable("id") Long id) {
        try {
            log.info("根据ID查询AI客户端API配置请求：{}", id);
            
            AiClientApi aiClientApi = aiClientApiDao.queryById(id);
            
            if (aiClientApi == null) {
                return Response.<AiClientApiResponseDTO>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("未找到对应的AI客户端API配置")
                        .data(null)
                        .build();
            }
            
            // PO转DTO
            AiClientApiResponseDTO responseDTO = convertToAiClientApiResponseDTO(aiClientApi);
            
            return Response.<AiClientApiResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("根据ID查询AI客户端API配置失败", e);
            return Response.<AiClientApiResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-api-id/{apiId}")
    public Response<AiClientApiResponseDTO> queryAiClientApiByApiId(@PathVariable("apiId") String apiId) {
        try {
            log.info("根据API ID查询AI客户端API配置请求：{}", apiId);
            
            AiClientApi aiClientApi = aiClientApiDao.queryByApiId(apiId);
            
            if (aiClientApi == null) {
                return Response.<AiClientApiResponseDTO>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("未找到对应的AI客户端API配置")
                        .data(null)
                        .build();
            }
            
            // PO转DTO
            AiClientApiResponseDTO responseDTO = convertToAiClientApiResponseDTO(aiClientApi);
            
            return Response.<AiClientApiResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("根据API ID查询AI客户端API配置失败", e);
            return Response.<AiClientApiResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-enabled")
    public Response<List<AiClientApiResponseDTO>> queryEnabledAiClientApis() {
        try {
            log.info("查询所有启用的AI客户端API配置");
            
            List<AiClientApi> aiClientApiList = aiClientApiDao.queryEnabledApis();
            
            // PO转DTO
            List<AiClientApiResponseDTO> responseDTOList = aiClientApiList.stream()
                    .map(this::convertToAiClientApiResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientApiResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOList)
                    .build();
        } catch (Exception e) {
            log.error("查询所有启用的AI客户端API配置失败", e);
            return Response.<List<AiClientApiResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @PostMapping("/query-list")
    public Response<List<AiClientApiResponseDTO>> queryAiClientApiList(@RequestBody AiClientApiQueryRequestDTO request) {
        try {
            log.info("分页查询AI客户端API配置列表请求：{}", request);
            
            // 这里需要根据实际的DAO实现来调整，如果DAO没有分页查询方法，需要先添加
            // 暂时使用查询所有然后过滤的方式
            List<AiClientApi> allApiList = aiClientApiDao.queryAll();
            
            // 根据查询条件过滤
            List<AiClientApi> filteredList = allApiList.stream()
                    .filter(api -> {
                        boolean match = true;
                        if (StringUtils.hasText(request.getApiId())) {
                            match = match && api.getApiId().contains(request.getApiId());
                        }
                        if (StringUtils.hasText(request.getBaseUrl())) {
                            match = match && api.getBaseUrl().contains(request.getBaseUrl());
                        }
                        if (request.getStatus() != null) {
                            match = match && api.getStatus().equals(request.getStatus());
                        }
                        return match;
                    })
                    .collect(Collectors.toList());
            
            // 简单分页处理
            int pageNum = request.getPageNum() != null ? request.getPageNum() : 1;
            int pageSize = request.getPageSize() != null ? request.getPageSize() : 10;
            int startIndex = (pageNum - 1) * pageSize;
            int endIndex = Math.min(startIndex + pageSize, filteredList.size());
            
            List<AiClientApi> pagedList = startIndex < filteredList.size() ? 
                    filteredList.subList(startIndex, endIndex) : List.of();
            
            // PO转DTO
            List<AiClientApiResponseDTO> responseDTOList = pagedList.stream()
                    .map(this::convertToAiClientApiResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientApiResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOList)
                    .build();
        } catch (Exception e) {
            log.error("分页查询AI客户端API配置列表失败", e);
            return Response.<List<AiClientApiResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-all")
    public Response<List<AiClientApiResponseDTO>> queryAllAiClientApis() {
        try {
            log.info("查询所有AI客户端API配置");
            
            List<AiClientApi> aiClientApiList = aiClientApiDao.queryAll();
            
            // PO转DTO
            List<AiClientApiResponseDTO> responseDTOList = aiClientApiList.stream()
                    .map(this::convertToAiClientApiResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientApiResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOList)
                    .build();
        } catch (Exception e) {
            log.error("查询所有AI客户端API配置失败", e);
            return Response.<List<AiClientApiResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    /**
     * DTO转PO对象
     */
    private AiClientApi convertToAiClientApi(AiClientApiRequestDTO requestDTO) {
        AiClientApi aiClientApi = new AiClientApi();
        BeanUtils.copyProperties(requestDTO, aiClientApi);
        return aiClientApi;
    }

    /**
     * PO转DTO对象
     */
    private AiClientApiResponseDTO convertToAiClientApiResponseDTO(AiClientApi aiClientApi) {
        AiClientApiResponseDTO responseDTO = new AiClientApiResponseDTO();
        BeanUtils.copyProperties(aiClientApi, responseDTO);
        return responseDTO;
    }

}