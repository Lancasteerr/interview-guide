package interview.guide.common.log;

/**
 * 异常日志消息简化器。
 *
 * <p>SDK 与上游服务可能把请求体、Prompt 片段回显到异常 message 中，直接记录存在隐私风险。
 * 统一只保留异常类型。原始异常 message、cause 和 suppressed exception 都可能回显用户输入，
 * 因此不得直接进入日志；需要堆栈时使用 {@link #forLogging(Throwable)} 生成脱敏副本。
 */
public final class ErrorLogSanitizer {

    private ErrorLogSanitizer() {
    }

    /**
     * 仅输出异常类名，不输出可能包含 Prompt、转写或文件正文的 message。
     */
    public static String summarize(Throwable e) {
        if (e == null) {
            return "unknown";
        }
        return e.getClass().getSimpleName();
    }

    /**
     * 将外部服务返回的自由文本归一为固定标记，避免响应正文或用户输入被回显。
     */
    public static String summarize(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return "external-error";
    }

    /**
     * 返回可安全作为 SLF4J 最后一个参数记录的异常副本。
     *
     * <p>副本保留原始调用栈以便定位代码，但丢弃原始 message、cause 与 suppressed exception。
     */
    public static Throwable forLogging(Throwable error) {
        String errorType = error == null ? "unknown" : error.getClass().getSimpleName();
        SanitizedLogException sanitized = new SanitizedLogException(errorType);
        if (error != null) {
            sanitized.setStackTrace(error.getStackTrace());
        }
        return sanitized;
    }

    private static final class SanitizedLogException extends Exception {

        private SanitizedLogException(String errorType) {
            super("sanitized-error type=" + errorType, null, false, true);
        }
    }
}
