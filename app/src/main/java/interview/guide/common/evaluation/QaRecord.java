package interview.guide.common.evaluation;

/**
 * 通用面试问答记录（文字面试和语音面试共用）
 * questionIndex 与 parentQuestionIndex 均为 0-based，展示时再加 1
 */
public record QaRecord(
    int questionIndex,
    String question,
    String category,
    String userAnswer,   // null 表示未回答
    boolean followUp,
    Integer parentQuestionIndex
) {
  public QaRecord(int questionIndex, String question, String category, String userAnswer) {
    this(questionIndex, question, category, userAnswer, false, null);
  }

  /**
   * 追问所属的主问题索引；非追问返回自身索引
   */
  public int groupRootIndex() {
    return followUp && parentQuestionIndex != null ? parentQuestionIndex : questionIndex;
  }
}
