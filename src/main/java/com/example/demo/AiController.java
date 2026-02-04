package com.example.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

@RestController
@CrossOrigin(origins = "*")
public class AiController {

    // ============== 配置区（必须修改！）==============
    // 将原来写死的：
// private static final String API_KEY = "sk-your-key";
// 改为：
    @Value("${deepseek.api.key}")
    private String apiKey;
    // 2. 智谱AI的API端点
    private static  String API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    // 3. 使用的模型，glm-4-flash 速度快且免费额度足够
    private static final String AI_MODEL = "glm-4-flash";
    // =============================================

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 用于在内存中保存简单的对话历史（按用户隔离的简易版本）
    private final Map<String, List<Map<String, String>>> conversationHistory = new HashMap<>();

    /**
     * 健康检查接口
     */
    @GetMapping("/health")
    public String health() {
        return "AI聊天机器人服务运行正常！当前时间：" + new Date();
    }

    /**
     * 聊天接口（核心功能）
     * POST /chat
     * 请求体格式（JSON）: {"userId": "user123", "message": "你好"}
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {

        // 创建响应体
        Map<String, Object> response = new HashMap<>();

        try {
            // 1. 获取请求参数
            String userId = request.get("userId");
            String userMessage = request.get("message");

            if (userId == null || userId.trim().isEmpty()) {
                userId = "anonymous"; // 默认用户
            }
            if (userMessage == null || userMessage.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "消息内容不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            // 2. 获取或初始化该用户的对话历史
            List<Map<String, String>> history = conversationHistory.getOrDefault(userId, new ArrayList<>());

            // 3. 构建发送给AI的消息列表（包含历史记录）
            List<Map<String, String>> messages = new ArrayList<>();

            // 添加上下文历史（最近3轮对话，防止token超限）
            int historySize = history.size();
            int startIdx = Math.max(0, historySize - 6); // 保留最近3组问答（每组2条消息）
            for (int i = startIdx; i < historySize; i++) {
                messages.add(history.get(i));
            }

            // 添加当前用户消息
            Map<String, String> currentMessage = new HashMap<>();
            currentMessage.put("role", "user");
            currentMessage.put("content", userMessage);
            messages.add(currentMessage);

            // 4. 构建请求体JSON
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", AI_MODEL);
            requestBody.put("messages", messages);
            requestBody.put("stream", false);
            // 可选：调整参数
            // requestBody.put("temperature", 0.7); // 创造性 (0~1)
            // requestBody.put("max_tokens", 1024); // 最大生成长度

            String requestBodyJson = objectMapper.writeValueAsString(requestBody);

            // 5. 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey); // 智谱使用Bearer Token认证

            // 6. 发送HTTP请求到AI平台
            HttpEntity<String> entity = new HttpEntity<>(requestBodyJson, headers);
            ResponseEntity<String> apiResponse = restTemplate.exchange(
                    API_URL, HttpMethod.POST, entity, String.class);

            // 7. 解析AI返回的JSON响应
            JsonNode rootNode = objectMapper.readTree(apiResponse.getBody());

            // 检查是否有错误
            if (rootNode.has("error")) {
                String errorMsg = rootNode.path("error").path("message").asText("未知API错误");
                throw new RuntimeException("AI平台返回错误: " + errorMsg);
            }

            // 提取AI回复内容
            String aiReply = rootNode.path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText("AI未能生成回复");

            // 8. 保存到对话历史
            history.add(currentMessage); // 保存用户消息
            Map<String, String> aiResponse = new HashMap<>();
            aiResponse.put("role", "assistant");
            aiResponse.put("content", aiReply);
            history.add(aiResponse); // 保存AI回复
            conversationHistory.put(userId, history);

            // 9. 返回成功响应
            response.put("success", true);
            response.put("reply", aiReply);
            response.put("userId", userId);
            response.put("historyCount", history.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            // 10. 统一异常处理
            e.printStackTrace(); // 在控制台打印错误堆栈，便于调试

            response.put("success", false);
            response.put("message", "请求处理失败: " + e.getMessage());
            response.put("timestamp", new Date().toString());

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 清空指定用户的对话历史
     * GET /clearHistory?userId=user123
     */
    @GetMapping("/clearHistory")
    public ResponseEntity<Map<String, Object>> clearHistory(@RequestParam String userId) {
        Map<String, Object> response = new HashMap<>();

        if (conversationHistory.containsKey(userId)) {
            conversationHistory.remove(userId);
            response.put("success", true);
            response.put("message", "用户 " + userId + " 的对话历史已清空");
        } else {
            response.put("success", false);
            response.put("message", "用户 " + userId + " 不存在对话历史");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 查看当前所有用户的对话状态（调试用）
     */
    @GetMapping("/debug/conversations")
    public Map<String, Object> debugConversations() {
        Map<String, Object> debugInfo = new HashMap<>();
        debugInfo.put("totalUsers", conversationHistory.size());

        Map<String, Integer> userHistoryCounts = new HashMap<>();
        for (Map.Entry<String, List<Map<String, String>>> entry : conversationHistory.entrySet()) {
            userHistoryCounts.put(entry.getKey(), entry.getValue().size());
        }
        debugInfo.put("historyByUser", userHistoryCounts);
        debugInfo.put("serverTime", new Date());

        return debugInfo;
    }
}