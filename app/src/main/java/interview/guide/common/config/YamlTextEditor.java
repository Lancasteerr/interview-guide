package interview.guide.common.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于文本的 YAML 编辑器。
 *
 * <p>仅修改目标节点，尽量保留原文件中的注释、空行和其他格式。</p>
 */
public final class YamlTextEditor {

  private final List<String> lines;

  public YamlTextEditor(List<String> lines) {
    this.lines = new ArrayList<>(lines);
  }

  public List<String> getLines() {
    return lines;
  }

  public void setScalar(String[] path, String value) {
    int searchFrom = path.length > 1
        ? ensureParents(Arrays.copyOf(path, path.length - 1))
        : 0;
    int indent = (path.length - 1) * 2;
    String key = path[path.length - 1];
    String newLine = " ".repeat(indent) + key + ": " + value;

    int found = findKey(key, indent, searchFrom);
    if (found >= 0) {
      lines.set(found, newLine);
    } else {
      int parentIndent = indent >= 2 ? indent - 2 : -1;
      int insertPos = findSectionEnd(searchFrom, parentIndent);
      lines.add(insertPos, newLine);
    }
  }

  public void setBlock(String[] parentPath, String blockKey, LinkedHashMap<String, Object> values) {
    int parentSearchFrom = ensureParents(parentPath);
    int blockIndent = parentPath.length * 2;
    int valueIndent = blockIndent + 2;

    int blockLine = findKey(blockKey, blockIndent, parentSearchFrom);
    if (blockLine < 0) {
      int parentEnd = parentPath.length >= 1 ? blockIndent - 2 : -1;
      int insertPos = findSectionEnd(parentSearchFrom, parentEnd);
      lines.add(insertPos, " ".repeat(blockIndent) + blockKey + ":");
      blockLine = insertPos;
    }

    int blockEnd = findSectionEnd(blockLine + 1, blockIndent);

    for (Map.Entry<String, Object> entry : values.entrySet()) {
      String valueLine = " ".repeat(valueIndent) + entry.getKey() + ": " + formatValue(entry.getValue());
      int existing = findKeyInRange(entry.getKey(), valueIndent, blockLine + 1, blockEnd);
      if (existing >= 0) {
        lines.set(existing, valueLine);
      } else {
        lines.add(blockEnd, valueLine);
        blockEnd++;
      }
    }
  }

  public void removeSection(String[] parentPath, String sectionKey) {
    int parentSearchFrom = navigateTo(parentPath);
    if (parentSearchFrom < 0) return;

    int sectionIndent = parentPath.length * 2;
    int sectionLine = findKey(sectionKey, sectionIndent, parentSearchFrom);
    if (sectionLine < 0) return;

    int endLine = sectionLine + 1;
    while (endLine < lines.size()) {
      String line = lines.get(endLine);
      if (line.isBlank()) {
        endLine++;
        continue;
      }
      if (indentOf(line) <= sectionIndent) break;
      endLine++;
    }

    for (int i = endLine - 1; i >= sectionLine; i--) {
      lines.remove(i);
    }
  }

  public void removeBlockKeys(String[] parentPath, String blockKey, String... keys) {
    int parentSearchFrom = navigateTo(parentPath);
    if (parentSearchFrom < 0) return;

    int blockIndent = parentPath.length * 2;
    int valueIndent = blockIndent + 2;
    int blockLine = findKey(blockKey, blockIndent, parentSearchFrom);
    if (blockLine < 0) return;

    int blockEnd = findSectionEnd(blockLine + 1, blockIndent);
    Set<String> keysToRemove = Set.of(keys);
    for (int i = blockEnd - 1; i > blockLine; i--) {
      String line = lines.get(i);
      if (line.isBlank() || line.trim().startsWith("#") || indentOf(line) != valueIndent) {
        continue;
      }
      String trimmed = line.trim();
      int separator = trimmed.indexOf(':');
      if (separator > 0 && keysToRemove.contains(trimmed.substring(0, separator))) {
        lines.remove(i);
      }
    }
  }

  private int ensureParents(String[] path) {
    int searchFrom = 0;
    for (int i = 0; i < path.length; i++) {
      int indent = i * 2;
      int found = findKey(path[i], indent, searchFrom);
      if (found < 0) {
        int parentIndent = i > 0 ? indent - 2 : -1;
        int insertPos = findSectionEnd(searchFrom, parentIndent);
        lines.add(insertPos, " ".repeat(indent) + path[i] + ":");
        searchFrom = insertPos + 1;
      } else {
        searchFrom = found + 1;
      }
    }
    return searchFrom;
  }

  private int navigateTo(String[] path) {
    int searchFrom = 0;
    for (int i = 0; i < path.length; i++) {
      int indent = i * 2;
      int found = findKey(path[i], indent, searchFrom);
      if (found < 0) return -1;
      searchFrom = found + 1;
    }
    return searchFrom;
  }

  private int findKey(String key, int indent, int searchFrom) {
    String prefix = " ".repeat(indent) + key + ":";
    for (int i = searchFrom; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank() || line.trim().startsWith("#")) continue;
      if (line.startsWith(prefix)) return i;
      if (indentOf(line) < indent) break;
    }
    return -1;
  }

  private int findKeyInRange(String key, int indent, int start, int end) {
    String prefix = " ".repeat(indent) + key + ":";
    for (int i = start; i < end && i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank() || line.trim().startsWith("#")) continue;
      if (line.startsWith(prefix)) return i;
      if (indentOf(line) < indent) break;
    }
    return -1;
  }

  private int findSectionEnd(int searchFrom, int parentIndent) {
    for (int i = searchFrom; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank() || line.trim().startsWith("#")) continue;
      if (indentOf(line) <= parentIndent) return i;
    }
    return lines.size();
  }

  private int indentOf(String line) {
    int count = 0;
    while (count < line.length() && line.charAt(count) == ' ') count++;
    return count;
  }

  private String formatValue(Object value) {
    if (value instanceof Boolean b) return b.toString();
    if (value instanceof Number n) return n.toString();
    return value.toString();
  }
}
