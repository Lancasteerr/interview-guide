package interview.guide.infrastructure.export;

import interview.guide.modules.interview.dto.ResumeAnalysisResponse;
import interview.guide.modules.interview.entity.InterviewSessionEntity;
import interview.guide.modules.resume.entity.ResumeEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * PDF 导出兼容门面。
 *
 * <p>按业务文档类型委托给专用导出器，保留原有调用方使用的 API。</p>
 */
@Service
public class PdfExportService {

    private final ResumeAnalysisPdfExporter resumeExporter;
    private final InterviewReportPdfExporter interviewExporter;

    @Autowired
    public PdfExportService(ResumeAnalysisPdfExporter resumeExporter,
                            InterviewReportPdfExporter interviewExporter) {
        this.resumeExporter = resumeExporter;
        this.interviewExporter = interviewExporter;
    }

    /**
     * 保留旧测试和手动构造调用方的构造方式。
     */
    public PdfExportService(ObjectMapper objectMapper) {
        PdfDocumentSupport support = new PdfDocumentSupport();
        this.resumeExporter = new ResumeAnalysisPdfExporter(support);
        this.interviewExporter = new InterviewReportPdfExporter(support, objectMapper);
    }

    public byte[] exportResumeAnalysis(ResumeEntity resume, ResumeAnalysisResponse analysis) {
        return resumeExporter.export(resume, analysis);
    }

    public byte[] exportInterviewReport(InterviewSessionEntity session) {
        return interviewExporter.export(session);
    }
}
