package interview.guide.modules.knowledgebase.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库向量化 Chunk 策略配置。
 *
 * <p>只暴露 Spring AI 2.0 TokenTextSplitter 原生支持的参数，不虚构 overlap 能力；
 * 是否开发自定义重叠分块器由边界题测评结果决定（P1-03 记录结论）。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai.rag.vectorization")
public class KnowledgeBaseVectorProperties {

    /**
     * 分块器类型，当前仅支持 token。
     */
    @NotBlank(message = "splitter 不能为空")
    @Pattern(regexp = "token", message = "splitter 当前仅支持 token")
    private String splitter = "token";

    /**
     * 每块目标 token 数。
     */
    @Min(value = 1, message = "chunkSize 必须大于 0")
    private int chunkSize = 800;

    /**
     * 单块最小字符数，低于该长度的片段并入相邻块。
     */
    @Min(value = 1, message = "minChunkSizeChars 必须大于 0")
    private int minChunkSizeChars = 350;

    /**
     * 低于该字符长度的块不参与 Embedding。
     */
    @Min(value = 0, message = "minChunkLengthToEmbed 不能为负数")
    private int minChunkLengthToEmbed = 5;

    /**
     * 单文档最大块数，超出截断。
     */
    @Min(value = 1, message = "maxNumChunks 必须大于 0")
    private int maxNumChunks = 10000;

    /**
     * 是否在块边界保留分隔符。
     */
    private boolean keepSeparator = true;

    /**
     * 句末标点集合，YAML 中用 "\\n" 表示换行符。
     */
    @NotEmpty(message = "punctuationMarks 不能为空")
    private List<String> punctuationMarks = List.of(".", "?", "!", "\\n", "。", "？", "！", "；");

    /**
     * 策略版本标识，写入向量 metadata 与知识库配置快照，排查混用问题。
     */
    @NotBlank(message = "strategyVersion 不能为空")
    private String strategyVersion = "token-v1";

    /**
     * 按当前配置构造生产使用的分块器，测试与运行时必须复用该入口。
     */
    public TextSplitter createTextSplitter() {
        return TokenTextSplitter.builder()
            .withChunkSize(chunkSize)
            .withMinChunkSizeChars(minChunkSizeChars)
            .withMinChunkLengthToEmbed(minChunkLengthToEmbed)
            .withMaxNumChunks(maxNumChunks)
            .withKeepSeparator(keepSeparator)
            .withPunctuationMarks(toPunctuationCharacters())
            .build();
    }

    /**
     * 标点字符串转字符集合；"\\n" 转义为换行符，其余取首字符。
     */
    public List<Character> toPunctuationCharacters() {
        List<Character> characters = new ArrayList<>();
        for (String mark : punctuationMarks) {
            if (mark == null || mark.isEmpty()) {
                continue;
            }
            if ("\\n".equals(mark)) {
                characters.add('\n');
            } else {
                characters.add(mark.charAt(0));
            }
        }
        return characters;
    }
}
