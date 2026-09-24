package interview.guide.modules.interviewschedule.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.modules.interviewschedule.dto.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.dto.ParseResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一的面试邀约解析服务 - 整合规则解析和AI解析
 * Simplified interview schedule parsing service combining rule-based and AI parsing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewParseService {

    private final LlmProviderRegistry llmProviderRegistry;
    private final ObjectMapper objectMapper;
    private final PromptSanitizer promptSanitizer;
    private final InterviewRuleParser ruleParser = new InterviewRuleParser();

    private static final String PARSE_PROMPT = """
        你是一个专业的面试邀约信息提取助手。请仔细分析以下文本，提取面试相关信息。

        **提取规则**：
        1. companyName（公司名称）：提取面试公司的全称或简称，**必需字段**
        2. position（岗位名称）：提取面试岗位的名称，**必需字段**
        3. interviewTime（面试时间）：提取面试开始时间并转换为 ISO 8601 格式，**必需字段**
           - 格式：YYYY-MM-DDTHH:MM:SS（例如：2026-04-10T14:00:00）
           - 若只有相对时间（如"明天下午2点"），根据当前日期 %s 推算
        4. interviewType（面试形式）：ONSITE（现场）/ VIDEO（视频）/ PHONE（电话）
        5. meetingLink（会议链接）：提取完整的会议链接或会议号+密码
        6. roundNumber（第几轮面试）：提取数字（1-10），如"二面"提取为2
        7. notes（其他备注）：包含面试官姓名（如果不重要可忽略）、时长（**默认30分钟**）等。

        **重要提示**：
        - 面试官是谁不重要，只需在 notes 中提及。
        - 优先保证 companyName、position、interviewTime 的准确性。
        - 如果文本中没说时长，默认设置为 30 分钟。

        **待解析文本**：
        %s

        **返回格式**：
        纯 JSON 格式，不要包含```json标记，示例：
        {"companyName":"阿里巴巴","position":"Java工程师","interviewTime":"2026-04-10T14:00:00","interviewType":"VIDEO","meetingLink":"https://meeting.feishu.cn/xxx","roundNumber":2,"interviewer":"张三","notes":"技术面"}
        """;

    /**
     * Parse interview schedule text
     *
     * @param rawText Raw text to parse
     * @param source Source platform (feishu/tencent/zoom or null for auto-detect)
     * @return Parse response with extracted interview details
     */
    public ParseResponse parse(String rawText, String source) {
        log.info("开始解析文本，来源: {}, 文本长度: {}", source, rawText != null ? rawText.length() : 0);

        if (rawText == null || rawText.trim().isEmpty()) {
            log.warn("Input text is null or empty");
            return new ParseResponse(false, null, 0.0, "none", "输入文本为空");
        }

        // Step 1: Try rule-based parsing
        CreateInterviewRequest result = tryRuleParsing(rawText, source);
        if (isValidResult(result)) {
            log.info("规则解析成功");
            return new ParseResponse(true, result, 0.95, "rule", "规则解析成功");
        }

        // Step 2: Rule parsing failed, try AI parsing
        log.info("规则解析失败，尝试 AI 解析");
        result = parseWithAI(rawText, source); // 使用source作为provider参数
        if (isValidResult(result)) {
            log.info("AI 解析成功");
            return new ParseResponse(true, result, 0.8, "ai", "AI 解析成功");
        }

        // Step 3: Both failed
        log.warn("所有解析方式均失败");
        return new ParseResponse(false, null, 0.0, "none", "解析失败");
    }

    private CreateInterviewRequest tryRuleParsing(String rawText, String source) {
        return ruleParser.parse(rawText, source);
    }

    // ========== AI Parsing ==========

    private CreateInterviewRequest parseWithAI(String rawText, String provider) {
        try {
            String currentDate = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            String safeRawText = promptSanitizer.sanitize(rawText);
            String prompt = String.format(PARSE_PROMPT, currentDate,
                PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" +
                promptSanitizer.wrapWithDelimiters("parse-input", safeRawText));

            ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);

            String content = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (content == null || content.trim().isEmpty()) {
                log.error("AI 解析返回内容为空");
                return null;
            }

            // Extract JSON from Markdown code blocks
            String jsonContent = content.trim();
            if (jsonContent.contains("```")) {
                Pattern pattern = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```");
                Matcher matcher = pattern.matcher(jsonContent);
                if (matcher.find()) {
                    jsonContent = matcher.group(1).trim();
                }
            }

            log.debug("提取到的 JSON 内容: {}", jsonContent);
            Map<String, Object> result = objectMapper.readValue(jsonContent, new TypeReference<Map<String, Object>>() {});

            if (result == null || result.isEmpty()) {
                log.error("JSON 解析返回空结果");
                return null;
            }

            CreateInterviewRequest request = new CreateInterviewRequest();

            // Extract and validate fields
            if (result.get("companyName") != null) {
                request.setCompanyName(result.get("companyName").toString().trim());
            }

            if (result.get("position") != null) {
                request.setPosition(result.get("position").toString().trim());
            }

            if (result.get("interviewTime") != null) {
                try {
                    String timeStr = result.get("interviewTime").toString().trim();
                    if (timeStr.length() == 16) { // YYYY-MM-DDTHH:MM
                        request.setInterviewTime(LocalDateTime.parse(timeStr + ":00"));
                    } else {
                        request.setInterviewTime(LocalDateTime.parse(timeStr));
                    }
                } catch (Exception e) {
                    log.error("AI 返回的时间格式不正确: {}", result.get("interviewTime"));
                }
            }

            if (result.get("interviewType") != null) {
                request.setInterviewType(result.get("interviewType").toString().trim());
            }

            if (result.get("meetingLink") != null) {
                request.setMeetingLink(result.get("meetingLink").toString().trim());
            }

            if (result.get("roundNumber") != null) {
                try {
                    String roundStr = result.get("roundNumber").toString().trim();
                    request.setRoundNumber(Integer.parseInt(roundStr));
                } catch (Exception e) {
                    request.setRoundNumber(1);
                }
            }

            if (result.get("interviewer") != null) {
                request.setInterviewer(result.get("interviewer").toString().trim());
            }

            if (result.get("notes") != null) {
                request.setNotes(result.get("notes").toString().trim());
            }

            log.info("AI 解析成功: {}", request.getCompanyName());
            return request;

        } catch (Exception e) {
            log.error("AI 解析异常: {}", e.getMessage(), e);
            return null;
        }
    }

    private boolean isValidResult(CreateInterviewRequest result) {
        return result != null
                && result.getCompanyName() != null
                && result.getPosition() != null
                && result.getInterviewTime() != null;
    }
}
