package interview.guide.infrastructure.export;

import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.kernel.colors.DeviceRgb;
import interview.guide.modules.interview.entity.InterviewAnswerEntity;
import interview.guide.modules.interview.entity.InterviewSessionEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 面试报告 PDF 导出器。
 */
@Slf4j
@Service
public class InterviewReportPdfExporter {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PdfDocumentSupport support;
    private final ObjectMapper objectMapper;

    public InterviewReportPdfExporter(PdfDocumentSupport support, ObjectMapper objectMapper) {
        this.support = support;
        this.objectMapper = objectMapper;
    }

    public byte[] export(InterviewSessionEntity session) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document document = new Document(pdfDoc);
        PdfFont font = support.createChineseFont();
        document.setFont(font);

        Paragraph title = new Paragraph("模拟面试报告")
                .setFontSize(24)
                .setBold()
                .setTextAlignment(TextAlignment.CENTER)
                .setFontColor(PdfDocumentSupport.HEADER_COLOR);
        document.add(title);

        document.add(new Paragraph("\n"));
        document.add(support.createSectionTitle("面试信息"));
        document.add(new Paragraph("会话ID: " + session.getSessionId()));
        document.add(new Paragraph("题目数量: " + session.getTotalQuestions()));
        document.add(new Paragraph("面试状态: " + support.getStatusText(session.getStatus())));
        document.add(new Paragraph("开始时间: "
                + (session.getCreatedAt() != null ? DATE_FORMAT.format(session.getCreatedAt()) : "未知")));
        if (session.getCompletedAt() != null) {
            document.add(new Paragraph("完成时间: " + DATE_FORMAT.format(session.getCompletedAt())));
        }

        if (session.getOverallScore() != null) {
            document.add(new Paragraph("\n"));
            document.add(support.createSectionTitle("综合评分"));
            Paragraph scoreP = new Paragraph("总分: " + session.getOverallScore() + " / 100")
                    .setFontSize(18)
                    .setBold()
                    .setFontColor(support.getScoreColor(session.getOverallScore()));
            document.add(scoreP);
        }

        if (session.getOverallFeedback() != null) {
            document.add(new Paragraph("\n"));
            document.add(support.createSectionTitle("总体评价"));
            document.add(new Paragraph(support.sanitizeText(session.getOverallFeedback())));
        }

        addStringList(document, session.getStrengthsJson(), "表现优势", session.getSessionId());
        addStringList(document, session.getImprovementsJson(), "改进建议", session.getSessionId());

        List<InterviewAnswerEntity> answers = session.getAnswers();
        if (answers != null && !answers.isEmpty()) {
            document.add(new Paragraph("\n"));
            document.add(support.createSectionTitle("问答详情"));

            for (InterviewAnswerEntity answer : answers) {
                document.add(new Paragraph("\n"));
                document.add(new Paragraph("问题 " + (answer.getQuestionIndex() + 1)
                        + " [" + (answer.getCategory() != null ? answer.getCategory() : "综合") + "]")
                        .setBold()
                        .setFontSize(12));
                document.add(new Paragraph("Q: " + support.sanitizeText(answer.getQuestion())));
                document.add(new Paragraph("A: " + support.sanitizeText(
                        answer.getUserAnswer() != null ? answer.getUserAnswer() : "未回答")));
                document.add(new Paragraph("得分: " + answer.getScore() + "/100")
                        .setFontColor(support.getScoreColor(answer.getScore())));
                if (answer.getFeedback() != null) {
                    document.add(new Paragraph("评价: " + support.sanitizeText(answer.getFeedback())).setItalic());
                }
                if (answer.getReferenceAnswer() != null) {
                    document.add(new Paragraph("参考答案: " + support.sanitizeText(answer.getReferenceAnswer()))
                            .setFontColor(new DeviceRgb(39, 174, 96)));
                }
            }
        }

        document.close();
        return baos.toByteArray();
    }

    private void addStringList(Document document, String json, String title, String sessionId) {
        if (json == null) {
            return;
        }
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<>() {
            });
            if (!values.isEmpty()) {
                document.add(new Paragraph("\n"));
                document.add(support.createSectionTitle(title));
                for (String value : values) {
                    document.add(new Paragraph("• " + support.sanitizeText(value)));
                }
            }
        } catch (Exception e) {
            log.error("解析{}JSON失败: sessionId={}", title, sessionId, e);
        }
    }
}
