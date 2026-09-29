package rems.idea.mixincompletion;

import com.intellij.ide.highlighter.JavaFileHighlighter;
import com.intellij.ide.highlighter.JavaHighlightingColors;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.util.Key;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MixinCommentImmediateHighlighter implements EditorFactoryListener {
    private static final int LAYER = HighlighterLayer.ADDITIONAL_SYNTAX + 1;
    private static final Key<List<RangeHighlighter>> HIGHLIGHTERS =
            Key.create("rems.mixin.comment.immediate.highlighters");
    private static final Key<DocumentListener> LISTENER =
            Key.create("rems.mixin.comment.immediate.listener");

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        if (editor.getProject() == null || editor.getVirtualFile() == null
                || !"java".equalsIgnoreCase(editor.getVirtualFile().getExtension())) return;
        DocumentListener listener = new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                if (!editor.isDisposed() && affectsHighlightedLine(editor, event)) refresh(editor);
            }
        };
        editor.putUserData(LISTENER, listener);
        editor.getDocument().addDocumentListener(listener);
        refresh(editor);
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        DocumentListener listener = editor.getUserData(LISTENER);
        if (listener != null) editor.getDocument().removeDocumentListener(listener);
        clear(editor);
    }

    private static void refresh(Editor editor) {
        clear(editor);
        Document document = editor.getDocument();
        List<RangeHighlighter> highlighters = new ArrayList<>();
        editor.putUserData(HIGHLIGHTERS, highlighters);
        boolean multilineCode = false;
        Deque<TextAttributesKey> conditionalColors = new ArrayDeque<>();
        for (int line = 0; line < document.getLineCount(); line++) {
            int lineStart = document.getLineStartOffset(line);
            int lineEnd = document.getLineEndOffset(line);
            String text = document.getText().substring(lineStart, lineEnd);
            Matcher preprocessor = MixinCommentSyntaxAnnotator.PREPROCESSOR.matcher(text);
            if (preprocessor.find()) {
                TextAttributesKey directiveColor = conditionalColor(
                        conditionalColors, preprocessor.group(2));
                if (directiveColor == null) directiveColor = MixinCommentSyntaxAnnotator.MUTED_ROSE;
                addGroup(editor, highlighters, lineStart, preprocessor, 1,
                        directiveColor);
                addGroup(editor, highlighters, lineStart, preprocessor, 2,
                        directiveColor);
                if (preprocessor.start(3) >= 0) {
                    String argument = preprocessor.group(3);
                    int argumentStart = preprocessor.start(3);
                    int conditionEnd = argument.length();
                    int codeStart = -1;
                    if ("replace".equals(preprocessor.group(2))) {
                        int separator = argument.indexOf('?');
                        if (separator >= 0) {
                            conditionEnd = separator;
                            add(editor, highlighters, lineStart + argumentStart + separator,
                                    lineStart + argumentStart + separator + 1,
                                    MixinCommentSyntaxAnnotator.MUTED_YELLOW);
                            codeStart = separator + 1;
                        }
                    }
                    add(editor, highlighters, lineStart + argumentStart,
                            lineStart + argumentStart + conditionEnd,
                            MixinCommentSyntaxAnnotator.LIGHT_GRAY);
                    String condition = argument.substring(0, conditionEnd);
                    addMatches(editor, highlighters, lineStart + argumentStart,
                            condition, MixinCommentSyntaxAnnotator.PREPROCESSOR_WORD,
                            MixinCommentSyntaxAnnotator.MUTED_PURPLE);
                    addMatches(editor, highlighters, lineStart + argumentStart,
                            condition, MixinCommentSyntaxAnnotator.PREPROCESSOR_OPERATOR,
                            MixinCommentSyntaxAnnotator.MUTED_YELLOW);
                    addMatches(editor, highlighters, lineStart + argumentStart,
                            condition, MixinCommentSyntaxAnnotator.PREPROCESSOR_NUMBER,
                            MixinCommentSyntaxAnnotator.MUTED_BLUE);
                    if (MixinCommentSyntaxAnnotator.isConditionalDirective(preprocessor.group(2))) {
                        addMatches(editor, highlighters, lineStart + argumentStart,
                                condition, MixinCommentSyntaxAnnotator.PREPROCESSOR_CONTROL,
                                MixinCommentSyntaxAnnotator.MUTED_ROSE);
                    }
                    if (codeStart >= 0) {
                        while (codeStart < argument.length() && Character.isWhitespace(argument.charAt(codeStart))) codeStart++;
                        String code = argument.substring(codeStart);
                        add(editor, highlighters, lineStart + argumentStart + codeStart,
                                lineStart + argumentStart + argument.length(),
                                MixinCommentSyntaxAnnotator.LIGHT_GRAY);
                        highlightJava(editor, highlighters,
                                lineStart + argumentStart + codeStart, code);
                    }
                }
                continue;
            }
            Matcher embeddedPreprocessor = MixinCommentSyntaxAnnotator.EMBEDDED_PREPROCESSOR.matcher(text);
            if (embeddedPreprocessor.find()) {
                List<TextAttributesKey> markerColors = conditionalColorsOuterFirst(conditionalColors);
                TextAttributesKey directiveColor = conditionalColor(
                        conditionalColors, embeddedPreprocessor.group(3));
                if (directiveColor == null) directiveColor = MixinCommentSyntaxAnnotator.MUTED_ROSE;
                addGroup(editor, highlighters, lineStart, embeddedPreprocessor, 2, directiveColor);
                addGroup(editor, highlighters, lineStart, embeddedPreprocessor, 3, directiveColor);
                if (embeddedPreprocessor.start(4) >= 0) {
                    String argument = embeddedPreprocessor.group(4);
                    int argumentStart = embeddedPreprocessor.start(4);
                    add(editor, highlighters, lineStart + argumentStart,
                            lineStart + argumentStart + argument.length(),
                            MixinCommentSyntaxAnnotator.LIGHT_GRAY);
                    addMatches(editor, highlighters, lineStart + argumentStart,
                            argument, MixinCommentSyntaxAnnotator.PREPROCESSOR_WORD,
                            MixinCommentSyntaxAnnotator.MUTED_PURPLE);
                    addMatches(editor, highlighters, lineStart + argumentStart,
                            argument, MixinCommentSyntaxAnnotator.PREPROCESSOR_OPERATOR,
                            MixinCommentSyntaxAnnotator.MUTED_YELLOW);
                    addMatches(editor, highlighters, lineStart + argumentStart,
                            argument, MixinCommentSyntaxAnnotator.PREPROCESSOR_NUMBER,
                            MixinCommentSyntaxAnnotator.MUTED_BLUE);
                    if (MixinCommentSyntaxAnnotator.isConditionalDirective(embeddedPreprocessor.group(3))) {
                        addMatches(editor, highlighters, lineStart + argumentStart,
                                argument, MixinCommentSyntaxAnnotator.PREPROCESSOR_CONTROL,
                                MixinCommentSyntaxAnnotator.MUTED_ROSE);
                    }
                }
                addLeadingCodeMarkerColors(editor, highlighters, lineStart, text,
                        embeddedPreprocessor.start(1), embeddedPreprocessor.end(1), markerColors);
                continue;
            }
            int caseMarker = text.indexOf("//?");
            if (caseMarker >= 0 && text.substring(0, caseMarker).isBlank()) {
                int restStart = caseMarker + 3;
                int separator = text.indexOf('?', restStart);
                int conditionEnd;
                int codeStart = -1;
                if (text.substring(restStart).stripLeading().startsWith("else")) {
                    int nameStart = restStart;
                    while (nameStart < text.length() && Character.isWhitespace(text.charAt(nameStart))) nameStart++;
                    conditionEnd = Math.min(text.length(), nameStart + 4);
                    codeStart = conditionEnd;
                } else {
                    conditionEnd = separator < 0 ? text.length() : separator;
                    if (separator >= 0) codeStart = separator + 1;
                }
                add(editor, highlighters, lineStart + caseMarker, lineStart + caseMarker + 3,
                        MixinCommentSyntaxAnnotator.MUTED_ROSE);
                if (conditionEnd > restStart) {
                    add(editor, highlighters, lineStart + restStart, lineStart + conditionEnd,
                            MixinCommentSyntaxAnnotator.LIGHT_GRAY);
                    String condition = text.substring(restStart, conditionEnd);
                    addMatches(editor, highlighters, lineStart + restStart, condition,
                            MixinCommentSyntaxAnnotator.PREPROCESSOR_WORD,
                            MixinCommentSyntaxAnnotator.MUTED_PURPLE);
                    addMatches(editor, highlighters, lineStart + restStart, condition,
                            MixinCommentSyntaxAnnotator.PREPROCESSOR_OPERATOR,
                            MixinCommentSyntaxAnnotator.MUTED_YELLOW);
                    addMatches(editor, highlighters, lineStart + restStart, condition,
                            MixinCommentSyntaxAnnotator.PREPROCESSOR_NUMBER,
                            MixinCommentSyntaxAnnotator.MUTED_BLUE);
                    addMatches(editor, highlighters, lineStart + restStart, condition,
                            MixinCommentSyntaxAnnotator.PREPROCESSOR_CONTROL,
                            MixinCommentSyntaxAnnotator.MUTED_ROSE);
                }
                if (separator >= 0) add(editor, highlighters,
                        lineStart + separator, lineStart + separator + 1,
                        MixinCommentSyntaxAnnotator.MUTED_YELLOW);
                if (codeStart >= 0) {
                    while (codeStart < text.length() && Character.isWhitespace(text.charAt(codeStart))) codeStart++;
                    if (codeStart < text.length()) {
                        add(editor, highlighters, lineStart + codeStart, lineEnd,
                                MixinCommentSyntaxAnnotator.LIGHT_GRAY);
                        highlightJava(editor, highlighters, lineStart + codeStart,
                                text.substring(codeStart));
                    }
                }
                continue;
            }
            int blockStart = text.indexOf("/*$$");
            if (blockStart >= 0 && text.substring(0, blockStart).isBlank()) {
                multilineCode = true;
                add(editor, highlighters, lineStart + blockStart, lineStart + blockStart + 4,
                        activeConditionalColor(conditionalColors));
                int contentStart = blockStart + 4;
                int blockEnd = text.indexOf("$$*/", contentStart);
                int contentEnd = blockEnd < 0 ? text.length() : blockEnd;
                if (contentEnd > contentStart) {
                    add(editor, highlighters, lineStart + contentStart, lineStart + contentEnd,
                            MixinCommentSyntaxAnnotator.LIGHT_GRAY);
                    highlightJava(editor, highlighters, lineStart + contentStart,
                            text.substring(contentStart, contentEnd));
                }
                if (blockEnd >= 0) {
                    add(editor, highlighters, lineStart + blockEnd, lineStart + blockEnd + 4,
                            activeConditionalColor(conditionalColors));
                    multilineCode = false;
                }
                continue;
            }
            if (multilineCode) {
                int blockEnd = text.indexOf("$$*/");
                int contentEnd = blockEnd < 0 ? text.length() : blockEnd;
                if (contentEnd > 0) {
                    add(editor, highlighters, lineStart, lineStart + contentEnd,
                            MixinCommentSyntaxAnnotator.LIGHT_GRAY);
                    highlightJava(editor, highlighters, lineStart, text.substring(0, contentEnd));
                }
                if (blockEnd >= 0) {
                    add(editor, highlighters, lineStart + blockEnd, lineStart + blockEnd + 4,
                            activeConditionalColor(conditionalColors));
                    multilineCode = false;
                }
                continue;
            }
            int marker = text.indexOf("//$$");
            if (marker < 0 || !text.substring(0, marker).isBlank()) continue;
            int codeStart = addLeadingCodeMarkerColors(editor, highlighters, lineStart, text,
                    marker, text.length(), conditionalColorsOuterFirst(conditionalColors));
            if (codeStart >= text.length()) continue;
            String code = text.substring(codeStart);
            add(editor, highlighters, lineStart + codeStart, lineEnd,
                    MixinCommentSyntaxAnnotator.LIGHT_GRAY);
            if (!code.isBlank()) highlightJava(editor, highlighters, lineStart + codeStart, code);
        }
    }

    private static boolean affectsHighlightedLine(Editor editor, DocumentEvent event) {
        String oldText = event.getOldFragment().toString();
        String newText = event.getNewFragment().toString();
        if (oldText.contains("//$$") || oldText.contains("//#") || oldText.contains("//?")
                || oldText.contains("/*$$") || oldText.contains("$$*/")
                || newText.contains("//$$") || newText.contains("//#") || newText.contains("//?")
                || newText.contains("/*$$") || newText.contains("$$*/")) return true;
        Document document = event.getDocument();
        int safeOffset = Math.min(event.getOffset(), document.getTextLength());
        int line = document.getLineNumber(safeOffset);
        for (int current = Math.max(0, line - 1);
             current <= Math.min(document.getLineCount() - 1, line + 1); current++) {
            String text = document.getText().substring(
                    document.getLineStartOffset(current), document.getLineEndOffset(current));
            if (text.contains("//$$") || text.contains("//#") || text.contains("//?")
                    || text.contains("/*$$") || text.contains("$$*/")) return true;
        }
        List<RangeHighlighter> highlighters = editor.getUserData(HIGHLIGHTERS);
        if (highlighters == null) return false;
        int changedEnd = event.getOffset() + Math.max(event.getOldLength(), event.getNewLength());
        for (RangeHighlighter highlighter : highlighters) {
            if (highlighter.getStartOffset() <= changedEnd
                    && highlighter.getEndOffset() >= event.getOffset()) return true;
        }
        return false;
    }

    private static void highlightJava(Editor editor, List<RangeHighlighter> highlighters,
                                      int absoluteStart, String body) {
        JavaFileHighlighter javaHighlighter = new JavaFileHighlighter(LanguageLevel.HIGHEST);
        Lexer lexer = javaHighlighter.getHighlightingLexer();
        lexer.start(body);
        List<Token> tokens = new ArrayList<>();
        while (lexer.getTokenType() != null) {
            IElementType type = lexer.getTokenType();
            int start = lexer.getTokenStart();
            int end = lexer.getTokenEnd();
            String tokenText = body.substring(start, end);
            tokens.add(new Token(type, start, end, tokenText));
            TextAttributesKey key = null;
            if (type == JavaTokenType.AT) {
                key = MixinCommentSyntaxAnnotator.MUTED_YELLOW;
            } else {
                TextAttributesKey[] keys = javaHighlighter.getTokenHighlights(type);
                if (keys.length > 0
                        && MixinCommentSyntaxAnnotator.keepsJavaColor(keys[0])) {
                    key = MixinCommentSyntaxAnnotator.mutedSyntaxColor(keys[0]);
                }
            }
            if (key != null) add(editor, highlighters,
                    absoluteStart + start, absoluteStart + end, key);
            lexer.advance();
        }

        Matcher declaration = MixinCommentSyntaxAnnotator.DECLARATION.matcher(body);
        int declarationStart = declaration.find() ? declaration.start(1) : -1;
        int declarationEnd = declarationStart < 0 ? -1 : declaration.end(1);
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.type() != JavaTokenType.IDENTIFIER) continue;
            Token previous = significant(tokens, index, -1);
            Token next = significant(tokens, index, 1);
            TextAttributesKey key = null;
            if (previous != null && previous.type() == JavaTokenType.AT) {
                key = MixinCommentSyntaxAnnotator.MUTED_YELLOW;
            } else if (MixinCommentSyntaxAnnotator.isAllUppercase(token.text())) {
                key = MixinCommentSyntaxAnnotator.MUTED_PURPLE;
            } else if (next != null && next.type() == JavaTokenType.LPARENTH
                    && declarationStart == token.start()) {
                key = MixinCommentSyntaxAnnotator.MUTED_BLUE;
            } else if (token.start() == declarationStart && token.end() == declarationEnd) {
                key = MixinCommentSyntaxAnnotator.MUTED_PURPLE;
            } else if (previous != null && previous.type() == JavaTokenType.DOT
                    && (next == null || next.type() != JavaTokenType.LPARENTH)) {
                key = MixinCommentSyntaxAnnotator.MUTED_PURPLE;
            }
            if (key != null) add(editor, highlighters,
                    absoluteStart + token.start(), absoluteStart + token.end(), key);
        }
    }

    private static Token significant(List<Token> tokens, int index, int direction) {
        for (int current = index + direction;
             current >= 0 && current < tokens.size(); current += direction) {
            if (!tokens.get(current).text().isBlank()) return tokens.get(current);
        }
        return null;
    }

    private static TextAttributesKey conditionalColor(Deque<TextAttributesKey> colors,
                                                      String directive) {
        if (MixinCommentSyntaxAnnotator.closesConditional(directive)) {
            return colors.isEmpty()
                    ? MixinCommentSyntaxAnnotator.PREPROCESSOR_SCOPE_COLORS[0]
                    : colors.pop();
        }
        if (MixinCommentSyntaxAnnotator.continuesConditional(directive)) {
            return colors.isEmpty()
                    ? MixinCommentSyntaxAnnotator.PREPROCESSOR_SCOPE_COLORS[0]
                    : colors.peek();
        }
        if (MixinCommentSyntaxAnnotator.opensConditional(directive)) {
            TextAttributesKey color = MixinCommentSyntaxAnnotator.PREPROCESSOR_SCOPE_COLORS[
                    colors.size() % MixinCommentSyntaxAnnotator.PREPROCESSOR_SCOPE_COLORS.length];
            colors.push(color);
            return color;
        }
        return null;
    }

    private static TextAttributesKey activeConditionalColor(Deque<TextAttributesKey> colors) {
        return colors.isEmpty() ? MixinCommentSyntaxAnnotator.MUTED_ROSE : colors.peek();
    }

    private static List<TextAttributesKey> conditionalColorsOuterFirst(
            Deque<TextAttributesKey> colors) {
        List<TextAttributesKey> result = new ArrayList<>();
        var iterator = colors.descendingIterator();
        while (iterator.hasNext()) result.add(iterator.next());
        return result;
    }

    private static int addLeadingCodeMarkerColors(Editor editor,
                                                  List<RangeHighlighter> highlighters,
                                                  int lineStart, String text,
                                                  int start, int end,
                                                  List<TextAttributesKey> colors) {
        int index = 0;
        int cursor = start;
        while (cursor < end) {
            while (cursor < end && Character.isWhitespace(text.charAt(cursor))) cursor++;
            if (cursor + 4 > end || !text.startsWith("//$$", cursor)) break;
            TextAttributesKey key = index < colors.size()
                    ? colors.get(index) : MixinCommentSyntaxAnnotator.MUTED_ROSE;
            add(editor, highlighters, lineStart + cursor,
                    lineStart + cursor + 4, key);
            index++;
            cursor += 4;
        }
        return cursor;
    }

    private static void addGroup(Editor editor, List<RangeHighlighter> highlighters,
                                 int lineStart, Matcher matcher, int group,
                                 TextAttributesKey key) {
        if (matcher.start(group) < 0) return;
        add(editor, highlighters, lineStart + matcher.start(group),
                lineStart + matcher.end(group), key);
    }

    private static void addMatches(Editor editor, List<RangeHighlighter> highlighters,
                                   int absoluteStart, String text, Pattern pattern,
                                   TextAttributesKey key) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            add(editor, highlighters, absoluteStart + matcher.start(),
                    absoluteStart + matcher.end(), key);
        }
    }

    private static void add(Editor editor, List<RangeHighlighter> highlighters,
                            int start, int end, TextAttributesKey key) {
        highlighters.add(editor.getMarkupModel().addRangeHighlighter(
                MixinCommentSyntaxAnnotator.themeAwareKey(key), start, end,
                LAYER, HighlighterTargetArea.EXACT_RANGE));
    }

    private static void clear(Editor editor) {
        List<RangeHighlighter> highlighters = editor.getUserData(HIGHLIGHTERS);
        if (highlighters == null) return;
        for (RangeHighlighter highlighter : highlighters) {
            if (highlighter.isValid()) editor.getMarkupModel().removeHighlighter(highlighter);
        }
        highlighters.clear();
    }

    private record Token(IElementType type, int start, int end, String text) {}

}
