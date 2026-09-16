import BatchUploadPage from '../components/batch-upload/BatchUploadPage';
import { resumeUploadAdapter } from '../api/batchUpload';
import { RESUME_FILE_POLICY } from '../utils/batchUpload';

export default function UploadPage({ onBack }: { onBack: () => void }) {
  return <BatchUploadPage
    entityLabel="简历"
    policy={RESUME_FILE_POLICY}
    adapter={resumeUploadAdapter}
    backLabel="返回简历库"
    onBack={onBack}
  />;
}
