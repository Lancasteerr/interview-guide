package interview.guide.infrastructure.file;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件对象 Key 生成策略。
 */
@Service
public class FileStorageKeyService {

    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    public String generate(String originalFilename, String prefix) {
        LocalDateTime now = LocalDateTime.now();
        String datePath = now.format(DATE_PATH_FORMAT);
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        String safeName = sanitizeFilename(originalFilename);
        return String.format("%s/%s/%s_%s", prefix, datePath, uuid, safeName);
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "unknown";
        }
        return convertToPinyin(filename);
    }

    private String convertToPinyin(String input) {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);

        StringBuilder result = new StringBuilder();
        for (char ch : input.toCharArray()) {
            try {
                String[] pinyins = PinyinHelper.toHanyuPinyinStringArray(ch, format);
                if (pinyins != null && pinyins.length > 0) {
                    result.append(capitalize(pinyins[0]));
                } else {
                    result.append(sanitizeChar(ch));
                }
            } catch (BadHanyuPinyinOutputFormatCombination e) {
                result.append(sanitizeChar(ch));
            }
        }
        return result.toString();
    }

    private char sanitizeChar(char ch) {
        if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) {
            return ch;
        }
        if (ch == '.' || ch == '_' || ch == '-') {
            return ch;
        }
        return '_';
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
