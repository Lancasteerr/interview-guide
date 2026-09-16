import BatchUploadPage from '../components/batch-upload/BatchUploadPage';
import { knowledgeBaseUploadAdapter } from '../api/batchUpload';
import { KNOWLEDGE_BASE_FILE_POLICY } from '../utils/batchUpload';

export default function KnowledgeBaseUploadPage({ onBack }: { onBack: () => void }) {
  return <BatchUploadPage
    entityLabel="知识库"
    policy={KNOWLEDGE_BASE_FILE_POLICY}
    adapter={knowledgeBaseUploadAdapter}
    customNamePlaceholder="知识库名称（可选，默认使用文件名）"
    backLabel="返回知识库"
    onBack={onBack}
  />;
}
