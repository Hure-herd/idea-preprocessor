package rems.idea.mixincompletion;

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PreprocessorEnterHandler implements EnterHandlerDelegate {
    private static final Pattern NODE = Pattern.compile(
            "(?:def|val|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)[^=]*=\\s*createNode\\s*\\(\\s*['\"][^'\"]+['\"]\\s*,\\s*([0-9_]+)");
    private static final Pattern LINK = Pattern.compile(
            "([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\.\\s*link\\s*\\(\\s*([A-Za-z_$][A-Za-z0-9_$]*)");

    @Override
    public @NotNull Result postProcessEnter(@NotNull PsiFile file,
                                            @NotNull Editor editor,
                                            @NotNull DataContext dataContext) {
        if (!(file instanceof PsiJavaFile)
                || !MixinCompletionSettings.getInstance().isAutoInsertPreprocessorComment()) {
            return Result.Continue;
        }

        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        int line = document.getLineNumber(Math.min(offset, document.getTextLength()));
        if (!insidePreprocessorBlock(document, line)) return Result.Continue;

        int lineStart = document.getLineStartOffset(line);
        int lineEnd = document.getLineEndOffset(line);
        String lineText = document.getText().substring(lineStart, lineEnd);
        int indentLength = 0;
        while (indentLength < lineText.length()
                && Character.isWhitespace(lineText.charAt(indentLength))) {
            indentLength++;
        }

        String content = lineText.substring(indentLength);
        String previousPrefix = previousCodeMarkerPrefix(document, line);
        int mainVersion = mainVersionCode(file);
        boolean needsMarkers = mainVersion >= 0
                ? !PreprocessorLanguage.isLineActive(document, line, Map.of("MC", mainVersion))
                : !previousPrefix.isEmpty();
        if (!needsMarkers) {
            removeGeneratedCommentPrefix(document, editor, lineStart, lineEnd,
                    indentLength, content);
            return Result.Continue;
        }
        int depth = PreprocessorLanguage.conditionalDepthBeforeLine(document, line);
        if (depth <= 0) return Result.Continue;
        String expectedPrefix = PreprocessorLanguage.codePrefixBeforeLine(document, line);
        if (expectedPrefix.isEmpty()) expectedPrefix = previousPrefix;
        expectedPrefix = preservePreviousCodeIndent(document, line, expectedPrefix);
        if (content.startsWith("//$$")) {
            int prefixEnd = leadingMarkerPrefixEnd(content);
            document.replaceString(lineStart + indentLength,
                    lineStart + indentLength + prefixEnd, expectedPrefix);
            editor.getCaretModel().moveToOffset(lineStart + indentLength + expectedPrefix.length());
        } else if (content.startsWith("// ")) {
            document.replaceString(lineStart + indentLength,
                    lineStart + indentLength + 3, expectedPrefix);
            editor.getCaretModel().moveToOffset(lineStart + indentLength + expectedPrefix.length());
        } else if (content.isEmpty()) {
            document.insertString(lineStart + indentLength, expectedPrefix);
            editor.getCaretModel().moveToOffset(lineStart + indentLength + expectedPrefix.length());
        }
        return Result.Continue;
    }

    private static String preservePreviousCodeIndent(Document document, int currentLine,
                                                     String markerPrefix) {
        if (currentLine <= 0 || markerPrefix.isEmpty()) return markerPrefix;
        int start = document.getLineStartOffset(currentLine - 1);
        int end = document.getLineEndOffset(currentLine - 1);
        String previous = document.getText().substring(start, end).stripLeading();
        if (!previous.startsWith(markerPrefix)) return markerPrefix;
        int cursor = markerPrefix.length();
        while (cursor < previous.length()) {
            char value = previous.charAt(cursor);
            if (value != ' ' && value != '\t') break;
            cursor++;
        }
        return markerPrefix + previous.substring(markerPrefix.length(), cursor);
    }

    private static void removeGeneratedCommentPrefix(Document document, Editor editor,
                                                     int lineStart, int lineEnd,
                                                     int indentLength, String content) {
        String rest = content;
        while (rest.startsWith("//$$")) rest = rest.substring(4).stripLeading();
        if (rest.equals("//")) rest = "";
        else if (rest.startsWith("//") && rest.substring(2).isBlank()) rest = "";
        if (!rest.isBlank()) return;
        document.deleteString(lineStart + indentLength, lineEnd);
        editor.getCaretModel().moveToOffset(lineStart + indentLength);
    }

    private static int mainVersionCode(PsiFile context) {
        VirtualFile directory = context.getVirtualFile() == null
                ? null : context.getVirtualFile().getParent();
        while (directory != null) {
            for (String name : List.of("build.gradle", "build.gradle.kts",
                    "settings.gradle", "settings.gradle.kts")) {
                VirtualFile graphFile = directory.findChild(name);
                if (graphFile == null || graphFile.isDirectory()) continue;
                int code = mainVersionCode(context, graphFile);
                if (code >= 0) return code;
            }
            directory = directory.getParent();
        }
        int fallback = Integer.MAX_VALUE;
        for (Module module : ModuleManager.getInstance(context.getProject()).getModules()) {
            int code = PreprocessorLanguage.moduleVersionCode(module.getName());
            if (code >= 0 && module.getName().endsWith(".main")) fallback = Math.min(fallback, code);
        }
        return fallback == Integer.MAX_VALUE ? -1 : fallback;
    }

    private static int mainVersionCode(PsiFile context, VirtualFile graphFile) {
        String text;
        try {
            PsiFile psiFile = PsiManager.getInstance(context.getProject()).findFile(graphFile);
            Document document = psiFile == null ? null
                    : PsiDocumentManager.getInstance(context.getProject()).getDocument(psiFile);
            text = document == null ? VfsUtilCore.loadText(graphFile) : document.getText();
        } catch (IOException ignored) {
            return -1;
        }
        Map<String, Integer> nodes = new LinkedHashMap<>();
        Matcher node = NODE.matcher(text);
        while (node.find()) {
            try {
                nodes.putIfAbsent(node.group(1), Integer.parseInt(node.group(2).replace("_", "")));
            } catch (NumberFormatException ignored) {
                // Ignore malformed graph entries and continue with the remaining nodes.
            }
        }
        if (nodes.isEmpty()) return -1;
        Set<String> incoming = new LinkedHashSet<>();
        Matcher link = LINK.matcher(text);
        while (link.find()) incoming.add(link.group(2));
        for (Map.Entry<String, Integer> entry : nodes.entrySet()) {
            if (!incoming.contains(entry.getKey())) return entry.getValue();
        }
        return nodes.values().iterator().next();
    }

    private static String previousCodeMarkerPrefix(Document document, int currentLine) {
        if (currentLine <= 0) return "";
        int start = document.getLineStartOffset(currentLine - 1);
        int end = document.getLineEndOffset(currentLine - 1);
        String text = document.getText().substring(start, end).stripLeading();
        if (!text.startsWith("//$$")) return "";
        return text.substring(0, leadingMarkerPrefixEnd(text));
    }

    private static int leadingMarkerPrefixEnd(String text) {
        int cursor = 0;
        while (cursor + 4 <= text.length() && text.startsWith("//$$", cursor)) {
            cursor += 4;
            while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
        }
        return cursor;
    }

    private static boolean insidePreprocessorBlock(Document document, int currentLine) {
        int depth = PreprocessorLanguage.conditionalDepthBeforeLine(document, currentLine);
        return depth > 0
                && PreprocessorLanguage.hasClosingConditional(document, currentLine, depth);
    }
}
