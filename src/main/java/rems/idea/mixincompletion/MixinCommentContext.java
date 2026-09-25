package rems.idea.mixincompletion;

import com.intellij.openapi.editor.Document;

import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;

final class MixinCommentContext {
    private static final Pattern ATTRIBUTE_VALUE = Pattern.compile(
            "\\b(?:method|target)\\s*=\\s*\"[^\"]*$");
    private static final Pattern ANNOTATION = Pattern.compile("@[A-Za-z0-9_$]*$");
    private static final Pattern AT_VALUE = Pattern.compile(
            "@At\\s*\\(\\s*(?:value\\s*=\\s*)?\"[^\"]*$");
    private static final Pattern JAVA_KEYWORD = Pattern.compile(
            "^\\s*(?://\\$\\$\\s*)+(?:[A-Za-z_$][A-Za-z0-9_$]*\\s+)*[A-Za-z_$]*$");
    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*$");
    private static final Pattern JAVA_MEMBER = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*\\.[A-Za-z_$]*$");
    private static final Pattern ANNOTATION_ATTRIBUTE = Pattern.compile(
            "@([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\((?:[^@)]*,\\s*)?([A-Za-z_$]*)$");
    private static final Pattern PREPROCESSOR_DIRECTIVE = Pattern.compile(
            "^\\s*(?://\\$\\$\\s*)*//#[A-Za-z]*$");
    private static final Pattern PREPROCESSOR_OPERATOR = Pattern.compile(
            "^\\s*(?://\\$\\$\\s*)*//#(?:if|elseif|elif|replace)\\s+MC\\s*(?:[!<>=]*|(?:not\\s+)?i?n?)$");
    private static final Pattern PREPROCESSOR_VERSION = Pattern.compile(
            "^\\s*(?://\\$\\$\\s*)*//#(?:if|elseif|elif|replace)\\s+.*(?:>=|<=|==|!=|>|<|\\.\\.|\\bin\\s+|\\[|,)\\s*[0-9._]*$");
    private static final Pattern PREPROCESSOR_EXPRESSION = Pattern.compile(
            "^\\s*(?://\\$\\$\\s*)*//#(?:if|elseif|elif|replace)\\s+.*$");

    private MixinCommentContext() {}

    static boolean isCompletionPosition(Document document, int offset) {
        if (offset < 0 || offset > document.getTextLength()) return false;
        int line = document.getLineNumber(offset);
        int lineStart = document.getLineStartOffset(line);
        String beforeCaret = javaCodeBeforeCaret(document, offset);
        String logicalContext = logicalContext(document, offset);
        return beforeCaret != null
                && (ATTRIBUTE_VALUE.matcher(beforeCaret).find()
                || ANNOTATION.matcher(beforeCaret).find()
                || AT_VALUE.matcher(beforeCaret).find()
                || JAVA_KEYWORD.matcher(beforeCaret).find()
                || annotationAttribute(logicalContext) != null
                || JAVA_MEMBER.matcher(beforeCaret).find()
                || JAVA_IDENTIFIER.matcher(beforeCaret).find());
    }

    static boolean isPreprocessorCompletionPosition(Document document, int offset) {
        if (offset < 0 || offset > document.getTextLength()) return false;
        int line = document.getLineNumber(offset);
        int lineStart = document.getLineStartOffset(line);
        String beforeCaret = document.getText().substring(lineStart, offset);
        return PREPROCESSOR_DIRECTIVE.matcher(beforeCaret).matches()
                || PREPROCESSOR_OPERATOR.matcher(beforeCaret).matches()
                || PREPROCESSOR_VERSION.matcher(beforeCaret).matches()
                || PREPROCESSOR_EXPRESSION.matcher(beforeCaret).matches()
                || (beforeCaret.stripLeading().startsWith("//?")
                && javaCodeBeforeCaret(document, offset) == null);
    }

    static String logicalContext(Document document, int offset) {
        int safeOffset = Math.max(0, Math.min(offset, document.getTextLength()));
        int blockStart = enclosingBlockStart(document, safeOffset);
        if (blockStart >= 0) {
            String text = document.getText().substring(blockStart + 4, safeOffset);
            return text.stripLeading();
        }
        int currentLine = document.getLineNumber(safeOffset);
        int currentStart = document.getLineStartOffset(currentLine);
        String physical = document.getText().substring(currentStart, safeOffset);
        if (!physical.stripLeading().startsWith("//$$")) {
            String synthetic = javaCodeBeforeCaret(document, safeOffset);
            if (synthetic != null) return synthetic.substring(4).stripLeading();
        }
        int firstLine = currentLine;
        while (firstLine > 0) {
            int start = document.getLineStartOffset(firstLine - 1);
            int end = document.getLineEndOffset(firstLine - 1);
            String previous = document.getText().substring(start, end).stripLeading();
            if (!previous.startsWith("//$$")) break;
            firstLine--;
        }
        StringBuilder result = new StringBuilder();
        for (int line = firstLine; line <= currentLine; line++) {
            int start = document.getLineStartOffset(line);
            int end = line == currentLine ? safeOffset : document.getLineEndOffset(line);
            String text = document.getText().substring(start, end).stripLeading();
            if (!text.startsWith("//$$")) continue;
            if (!result.isEmpty()) result.append('\n');
            result.append(text.substring(4).stripLeading());
        }
        return result.toString();
    }

    static String javaCodeBeforeCaret(Document document, int offset) {
        int safeOffset = Math.max(0, Math.min(offset, document.getTextLength()));
        int line = document.getLineNumber(safeOffset);
        int lineStart = document.getLineStartOffset(line);
        String before = document.getText().substring(lineStart, safeOffset);
        if (before.stripLeading().startsWith("//$$")) return before;
        int caseBody = caseBodyStart(before);
        if (caseBody >= 0) return "//$$ " + before.substring(caseBody);
        int replaceBody = replacementBodyStart(before);
        if (replaceBody >= 0) return "//$$ " + before.substring(replaceBody);
        int inlineBody = inlineCaseBodyStart(document, safeOffset);
        if (inlineBody >= 0) return "//$$ " + document.getText().substring(inlineBody, safeOffset);
        int blockStart = enclosingBlockStart(document, safeOffset);
        if (blockStart < 0) return null;
        int contentStart = Math.max(lineStart, blockStart + 4);
        return "//$$ " + document.getText().substring(contentStart, safeOffset);
    }

    static boolean isMultilineCodePosition(Document document, int offset) {
        return enclosingBlockStart(document, Math.max(0, Math.min(offset, document.getTextLength()))) >= 0;
    }

    private static int enclosingBlockStart(Document document, int offset) {
        String text = document.getText();
        int start = text.lastIndexOf("/*$$", Math.max(0, offset - 1));
        if (start < 0) return -1;
        int previousEnd = text.lastIndexOf("$$*/", Math.max(0, offset - 1));
        if (previousEnd > start) return -1;
        int nextEnd = text.indexOf("$$*/", offset);
        return nextEnd < 0 ? -1 : start;
    }

    private static int caseBodyStart(String linePrefix) {
        int marker = linePrefix.indexOf("//?");
        if (marker < 0 || !linePrefix.substring(0, marker).isBlank()) return -1;
        int cursor = marker + 3;
        while (cursor < linePrefix.length() && Character.isWhitespace(linePrefix.charAt(cursor))) cursor++;
        if (linePrefix.startsWith("else", cursor)) {
            int end = cursor + 4;
            if (end < linePrefix.length() && Character.isWhitespace(linePrefix.charAt(end))) {
                while (end < linePrefix.length() && Character.isWhitespace(linePrefix.charAt(end))) end++;
                return end;
            }
            return -1;
        }
        int separator = linePrefix.indexOf('?', cursor);
        if (separator < 0) return -1;
        int body = separator + 1;
        while (body < linePrefix.length() && Character.isWhitespace(linePrefix.charAt(body))) body++;
        return body;
    }

    private static int replacementBodyStart(String linePrefix) {
        int marker = linePrefix.lastIndexOf("//#replace");
        if (marker < 0) return -1;
        int separator = linePrefix.indexOf('?', marker + "//#replace".length());
        if (separator < 0) return -1;
        int body = separator + 1;
        while (body < linePrefix.length() && Character.isWhitespace(linePrefix.charAt(body))) body++;
        return body;
    }

    private static int inlineCaseBodyStart(Document document, int offset) {
        String text = document.getText();
        int marker = text.lastIndexOf("/*?", Math.max(0, offset - 1));
        if (marker < 0) return -1;
        int previousEnd = text.lastIndexOf("*/", Math.max(0, offset - 1));
        if (previousEnd > marker || text.indexOf("*/", offset) < 0) return -1;
        int separator = text.indexOf('?', marker + 3);
        if (separator < 0 || separator >= offset) return -1;
        int body = separator + 1;
        while (body < offset && Character.isWhitespace(text.charAt(body))) body++;
        return body;
    }

    static AnnotationAttribute annotationAttribute(String text) {
        int prefixStart = text.length();
        while (prefixStart > 0 && Character.isJavaIdentifierPart(text.charAt(prefixStart - 1))) {
            prefixStart--;
        }
        String prefix = text.substring(prefixStart);
        int delimiter = prefixStart - 1;
        while (delimiter >= 0 && Character.isWhitespace(text.charAt(delimiter))) delimiter--;
        if (delimiter < 0 || (text.charAt(delimiter) != '(' && text.charAt(delimiter) != ',')) {
            return null;
        }

        String annotation = enclosingAnnotation(text, prefixStart);
        return annotation == null ? null : new AnnotationAttribute(annotation, prefix);
    }

    static String enclosingAnnotation(String text) {
        return enclosingAnnotation(text, text.length());
    }

    private static String enclosingAnnotation(String text, int endOffset) {
        List<String> parentheses = new ArrayList<>();
        char quote = 0;
        for (int i = 0; i < endOffset; i++) {
            char value = text.charAt(i);
            if ((value == '"' || value == '\'') && !isEscaped(text, i)) {
                if (quote == 0) quote = value;
                else if (quote == value) quote = 0;
                continue;
            }
            if (quote != 0) continue;
            if (value == '(') {
                int end = i;
                int cursor = i - 1;
                while (cursor >= 0 && Character.isWhitespace(text.charAt(cursor))) cursor--;
                end = cursor + 1;
                while (cursor >= 0 && (Character.isJavaIdentifierPart(text.charAt(cursor))
                        || text.charAt(cursor) == '.')) cursor--;
                String qualified = cursor >= 0 && text.charAt(cursor) == '@'
                        ? text.substring(cursor + 1, end) : "";
                int dot = qualified.lastIndexOf('.');
                String name = dot < 0 ? qualified : qualified.substring(dot + 1);
                parentheses.add(name);
            } else if (value == ')' && !parentheses.isEmpty()) {
                parentheses.remove(parentheses.size() - 1);
            }
        }
        for (int i = parentheses.size() - 1; i >= 0; i--) {
            if (!parentheses.get(i).isEmpty()) {
                return parentheses.get(i);
            }
        }
        return null;
    }

    private static boolean isEscaped(String text, int offset) {
        int slashes = 0;
        for (int i = offset - 1; i >= 0 && text.charAt(i) == '\\'; i--) slashes++;
        return (slashes & 1) != 0;
    }

    record AnnotationAttribute(String annotation, String prefix) {}
}
