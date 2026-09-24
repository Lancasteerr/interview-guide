package interview.guide.infrastructure.export;

import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import interview.guide.modules.interview.dto.ResumeAnalysisResponse;
import interview.guide.modules.resume.entity.ResumeEntity;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;

/**
 * 简历分析报告 PDF 导出器。
 */
@Service
public class ResumeAnalysisPdfExporter {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PdfDocumentSupport support;

    public ResumeAnalysisPdfExporter(PdfDocumentSupport support) {
        this.support = support;
    }

    public byte[] export(ResumeEntity resume, ResumeAnalysisResponse analysis) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document document = new Document(pdfDoc);
        PdfFont font = support.createChineseFont();
        document.setFont(font);

        Paragraph title = new Paragraph("简历分析报告")
                .setFontSize(24)
                .setBold()
                .setTextAlignment(TextAlignment.CENTER)
                .setFontColor(PdfDocumentSupport.HEADER_COLOR);
        document.add(title);

        document.add(new Paragraph("\n"));
        document.add(support.createSectionTitle("基本信息"));
        document.add(new Paragraph("文件名: " + resume.getOriginalFilename()));
        document.add(new Paragraph("上传时间: "
                + (resume.getUploadedAt() != null ? DATE_FORMAT.format(resume.getUploadedAt()) : "未知")));

        document.add(new Paragraph("\n"));
        document.add(support.createSectionTitle("综合评分"));
        Paragraph scoreP = new Paragraph("总分: " + analysis.overallScore() + " / 100")
                .setFontSize(18)
                .setBold()
                .setFontColor(support.getScoreColor(analysis.overallScore()));
        document.add(scoreP);

        if (analysis.scoreDetail() != null) {
            document.add(new Paragraph("\n"));
            document.add(support.createSectionTitle("各维度评分"));

            Table scoreTable = new Table(UnitValue.createPercentArray(new float[]{2, 1}))
                    .useAllAvailableWidth();
            support.addScoreRow(scoreTable, "项目经验", analysis.scoreDetail().projectScore(), 40);
            support.addScoreRow(scoreTable, "技能匹配度", analysis.scoreDetail().skillMatchScore(), 20);
            support.addScoreRow(scoreTable, "内容完整性", analysis.scoreDetail().contentScore(), 15);
            support.addScoreRow(scoreTable, "结构清晰度", analysis.scoreDetail().structureScore(), 15);
            support.addScoreRow(scoreTable, "表达专业性", analysis.scoreDetail().expressionScore(), 10);
            document.add(scoreTable);
        }

        if (analysis.summary() != null) {
            document.add(new Paragraph("\n"));
            document.add(support.createSectionTitle("简历摘要"));
            document.add(new Paragraph(support.sanitizeText(analysis.summary())));
        }

        if (analysis.strengths() != null && !analysis.strengths().isEmpty()) {
            document.add(new Paragraph("\n"));
            document.add(support.createSectionTitle("优势亮点"));
            for (String strength : analysis.strengths()) {
                document.add(new Paragraph("• " + support.sanitizeText(strength)));
            }
        }

        if (analysis.suggestions() != null && !analysis.suggestions().isEmpty()) {
            document.add(new Paragraph("\n"));
            document.add(support.createSectionTitle("改进建议"));
            for (ResumeAnalysisResponse.Suggestion suggestion : analysis.suggestions()) {
                document.add(new Paragraph("【" + suggestion.priority() + "】"
                        + support.sanitizeText(suggestion.category())).setBold());
                document.add(new Paragraph("问题: " + support.sanitizeText(suggestion.issue())));
                document.add(new Paragraph("建议: " + support.sanitizeText(suggestion.recommendation())));
                document.add(new Paragraph("\n"));
            }
        }

        document.close();
        return baos.toByteArray();
    }
}
