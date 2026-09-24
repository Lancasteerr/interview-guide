package interview.guide.modules.interviewschedule.service;

import interview.guide.modules.interviewschedule.dto.CreateInterviewRequest;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面试平台规则解析器，负责 Feishu、Tencent Meeting 和 Zoom 的格式化文本。
 */
@Slf4j
final class InterviewRuleParser {

  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final Map<String, Integer> CHINESE_NUMBERS = Map.of(
      "一", 1, "二", 2, "三", 3, "四", 4, "五", 5,
      "六", 6, "七", 7, "八", 8, "九", 9, "十", 10
  );
  private static final Pattern TIME_PATTERN_FEISHU = Pattern.compile("(?:时间|时段)[：:]\\s*(\\d{4}[-/]\\d{2}[-/]\\d{2}\\s+\\d{2}:\\d{2})");
  private static final Pattern LINK_PATTERN_FEISHU = Pattern.compile("https://meeting\\.feishu\\.cn/[^\\s\\n]+");
  private static final Pattern COMPANY_PATTERN_FEISHU = Pattern.compile("(?:公司|单位|组织)[：:]\\s*([^\\s\\n]{1,50})");
  private static final Pattern POSITION_PATTERN_FEISHU = Pattern.compile("(?:岗位|职位|职务)[：:]\\s*([^\\s\\n]{1,50})");
  private static final Pattern ROUND_PATTERN_FEISHU = Pattern.compile("第\\s*[一二三四五六七八九十\\d]+\\s*[轮场]");
  private static final Pattern TIME_PATTERN_TENCENT = Pattern.compile("(\\d{4}[-/]\\d{2}[-/]\\d{2})\\s+(\\d{2}:\\d{2})");
  private static final Pattern MEETING_ID_PATTERN_TENCENT = Pattern.compile("(?:会议号|ID)[：:]?\\s*(\\d{9,})");
  private static final Pattern PASSWORD_PATTERN_TENCENT = Pattern.compile("密码[：:]?\\s*(\\d{4,})");
  private static final Pattern COMPANY_PATTERN_TENCENT = Pattern.compile("(?:公司|单位)[：:]\\s*([^\\s\\n]{1,50})");
  private static final Pattern POSITION_PATTERN_TENCENT = Pattern.compile("(?:岗位|职位)[：:]\\s*([^\\s\\n]{1,50})");
  private static final Pattern LINK_PATTERN_ZOOM = Pattern.compile("https://zoom\\.us/j/[^\\s\\n]+");
  private static final Pattern DATE_PATTERN_ZOOM = Pattern.compile("(\\d{4}[-/]\\d{2}[-/]\\d{2})");
  private static final Pattern HOUR_PATTERN_ZOOM = Pattern.compile("(\\d{1,2}:\\d{2})");
  private static final Pattern ROUND_NUMBER_PATTERN = Pattern.compile("[一二三四五六七八九十]|\\d");

  CreateInterviewRequest parse(String rawText, String source) {
    if ("feishu".equalsIgnoreCase(source)) return parseFeishu(rawText);
    if ("tencent".equalsIgnoreCase(source)) return parseTencent(rawText);
    if ("zoom".equalsIgnoreCase(source)) return parseZoom(rawText);

    if (rawText.contains("飞书") || rawText.contains("Feishu") || rawText.contains("meeting.feishu.cn")) {
      CreateInterviewRequest result = parseFeishu(rawText);
      if (isValid(result)) return result;
    }
    if (rawText.contains("腾讯会议") || rawText.contains("Tencent Meeting") || rawText.contains("会议号")) {
      CreateInterviewRequest result = parseTencent(rawText);
      if (isValid(result)) return result;
    }
    if (rawText.contains("Zoom") || rawText.contains("zoom.us")) {
      CreateInterviewRequest result = parseZoom(rawText);
      if (isValid(result)) return result;
    }

    CreateInterviewRequest result = parseFeishu(rawText);
    if (isValid(result)) return result;
    result = parseTencent(rawText);
    if (isValid(result)) return result;
    return parseZoom(rawText);
  }

  private CreateInterviewRequest parseFeishu(String rawText) {
    log.debug("尝试解析飞书格式");
    CreateInterviewRequest request = new CreateInterviewRequest();
    try {
      Matcher time = TIME_PATTERN_FEISHU.matcher(rawText);
      if (time.find()) request.setInterviewTime(parseDateTime(time.group(1)));
      Matcher link = LINK_PATTERN_FEISHU.matcher(rawText);
      if (link.find()) request.setMeetingLink(link.group());
      Matcher company = COMPANY_PATTERN_FEISHU.matcher(rawText);
      if (company.find()) request.setCompanyName(company.group(1).trim());
      Matcher position = POSITION_PATTERN_FEISHU.matcher(rawText);
      if (position.find()) request.setPosition(position.group(1).trim());
      Matcher round = ROUND_PATTERN_FEISHU.matcher(rawText);
      if (round.find()) request.setRoundNumber(parseRoundNumber(round.group()));
      request.setInterviewType("VIDEO");
    } catch (Exception e) {
      log.error("飞书格式解析异常", e);
    }
    return request;
  }

  private CreateInterviewRequest parseTencent(String rawText) {
    log.debug("尝试解析腾讯会议格式");
    CreateInterviewRequest request = new CreateInterviewRequest();
    try {
      Matcher time = TIME_PATTERN_TENCENT.matcher(rawText);
      if (time.find()) request.setInterviewTime(parseDateTime(time.group(1) + " " + time.group(2)));
      Matcher meetingId = MEETING_ID_PATTERN_TENCENT.matcher(rawText);
      Matcher password = PASSWORD_PATTERN_TENCENT.matcher(rawText);
      StringBuilder link = new StringBuilder();
      if (meetingId.find()) link.append("会议号: ").append(meetingId.group());
      if (password.find()) link.append(" 密码: ").append(password.group());
      if (!link.isEmpty()) request.setMeetingLink(link.toString());
      Matcher company = COMPANY_PATTERN_TENCENT.matcher(rawText);
      if (company.find()) request.setCompanyName(company.group(1).trim());
      Matcher position = POSITION_PATTERN_TENCENT.matcher(rawText);
      if (position.find()) request.setPosition(position.group(1).trim());
      request.setInterviewType("VIDEO");
    } catch (Exception e) {
      log.error("腾讯会议格式解析异常", e);
    }
    return request;
  }

  private CreateInterviewRequest parseZoom(String rawText) {
    log.debug("尝试解析 Zoom 格式");
    CreateInterviewRequest request = new CreateInterviewRequest();
    try {
      Matcher link = LINK_PATTERN_ZOOM.matcher(rawText);
      if (link.find()) request.setMeetingLink(link.group());
      Matcher date = DATE_PATTERN_ZOOM.matcher(rawText);
      Matcher hour = HOUR_PATTERN_ZOOM.matcher(rawText);
      if (date.find() && hour.find()) request.setInterviewTime(parseDateTime(date.group(1) + " " + hour.group(1)));
      request.setInterviewType("VIDEO");
    } catch (Exception e) {
      log.error("Zoom 格式解析异常", e);
    }
    return request;
  }

  private LocalDateTime parseDateTime(String timeStr) {
    try {
      timeStr = timeStr.replace("/", "-");
      if (timeStr.length() == 16) return LocalDateTime.parse(timeStr, DATE_TIME_FORMATTER);
      if (timeStr.length() == 19) {
        return LocalDateTime.parse(timeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
      }
      return LocalDateTime.parse(timeStr);
    } catch (Exception e) {
      log.error("时间解析失败: {}", timeStr, e);
      return null;
    }
  }

  private int parseRoundNumber(String text) {
    if (text == null) return 1;
    text = text.trim();
    if (text.matches("\\d+")) return Integer.parseInt(text);
    Matcher matcher = ROUND_NUMBER_PATTERN.matcher(text);
    if (matcher.find()) {
      String number = matcher.group();
      return CHINESE_NUMBERS.getOrDefault(number, Integer.parseInt(number.replaceAll("\\D", "")));
    }
    return 1;
  }

  private boolean isValid(CreateInterviewRequest result) {
    return result != null
        && result.getCompanyName() != null
        && result.getPosition() != null
        && result.getInterviewTime() != null;
  }
}
