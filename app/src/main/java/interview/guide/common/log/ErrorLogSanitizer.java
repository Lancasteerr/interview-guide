package interview.guide.common.log;

/**
 * 异常日志消息简化器。
 *
 * <p>SDK 与上游服务可能把请求体、Prompt 片段回显到异常 message 中，直接记录存在隐私风险。
 * 统一收敛为「异常类名 + 压缩空白并截断的消息」，异常对象本身仍按 SLF4J 规范作为最后一个参数传入。
 */
public final class ErrorLogSanitizer {

    private static final int MAX_MESSAGE_CHARS = 200;

    private ErrorLogSanitizer() {
    }

    /**
     * 输出形如 {@code IllegalStateException: LLM 连接超时} 的简化描述；
     * message 为空时只保留类名，超长时截断。
     */
    public static String summarize(Throwable e) {
        if (e == null) {
            return "unknown";
        }
        String message = e.getMessage() == null ? "" : e.getMessage().replaceAll("\\s+", " ").trim();
        if (message.length() > MAX_MESSAGE_CHARS) {
            message = message.substring(0, MAX_MESSAGE_CHARS) + "…";
        }
        return message.isEmpty() ? e.getClass().getSimpleName()
            : e.getClass().getSimpleName() + ": " + message;
    }

    /**
     * 简化普通错误文本（如外部服务返回的 error message）。
     */
    public static String summarize(String message) {
        if (message == null) {
            return "";
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        if (normalized.length() > MAX_MESSAGE_CHARS) {
            return normalized.substring(0, MAX_MESSAGE_CHARS) + "…";
        }
        return normalized;
    }
}
