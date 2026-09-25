package rems.idea.mixincompletion;

import com.intellij.ide.highlighter.JavaFileHighlighter;
import com.intellij.ide.highlighter.JavaHighlightingColors;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.util.TextRange;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.Color;
import java.awt.Font;

public final class MixinCommentSyntaxAnnotator implements Annotator {
    static final TextAttributesKey MUTED_ROSE = TextAttributesKey.createTextAttributesKey(
            "REMS_MIXIN_COMMENT_MUTED_ROSE",
            new TextAttributes(new JBColor(new Color(0x8E5558), new Color(0xA86F70)),
                    null, null, null, Font.PLAIN));
    static final TextAttributesKey LIGHT_GRAY = TextAttributesKey.createTextAttributesKey(
            "REMS_MIXIN_COMMENT_LIGHT_GRAY",
            new TextAttributes(new JBColor(new Color(0x666B70), new Color(0x858A93)),
                    null, null, null, Font.PLAIN));
    static final TextAttributesKey MUTED_YELLOW = TextAttributesKey.createTextAttributesKey(
            "REMS_MIXIN_COMMENT_MUTED_YELLOW",
            new TextAttributes(new JBColor(new Color(0x806800), new Color(0xA89538)),
                    null, null, null, Font.PLAIN));
    static final TextAttributesKey MUTED_BLUE = TextAttributesKey.createTextAttributesKey(
            "REMS_MIXIN_COMMENT_MUTED_BLUE",
            new TextAttributes(new JBColor(new Color(0x315D7A), new Color(0x4E7C9E)),
                    null, null, null, Font.PLAIN));
    static final TextAttributesKey MUTED_GREEN = TextAttributesKey.createTextAttributesKey(
            "REMS_MIXIN_COMMENT_MUTED_GREEN",
            new TextAttributes(new JBColor(new Color(0x35663D), new Color(0x4F8658)),
                    null, null, null, Font.PLAIN));
    static final TextAttributesKey MUTED_PURPLE = TextAttributesKey.createTextAttributesKey(
            "REMS_MIXIN_COMMENT_MUTED_PURPLE",
            new TextAttributes(new JBColor(new Color(0x67436B), new Color(0x875A8C)),
                    null, null, null, Font.PLAIN));
    static final TextAttributesKey[] PREPROCESSOR_SCOPE_COLORS = {
            scopeColor("REMS_PREPROCESSOR_SCOPE_BLUE", 0x2F668A, 0x4F83A8),
            scopeColor("REMS_PREPROCESSOR_SCOPE_ORANGE", 0x8A5A1F, 0xB17A3E),
            scopeColor("REMS_PREPROCESSOR_SCOPE_PURPLE", 0x70507F, 0x9870AA),
            scopeColor("REMS_PREPROCESSOR_SCOPE_GREEN", 0x3F7049, 0x61916A),
            scopeColor("REMS_PREPROCESSOR_SCOPE_RED", 0x8B4D54, 0xAD6970),
            scopeColor("REMS_PREPROCESSOR_SCOPE_CYAN", 0x2D7273, 0x559798)
    };
    static final Pattern DECLARATION = Pattern.compile(
            "(?:(?:public|protected|private|abstract|final|static|synchronized|native|strictfp|transient|volatile)\\s+)*"
                    + "[A-Za-z_$][A-Za-z0-9_$.<>?, \\[\\]]*\\s+"
                    + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:=|;|\\()");
    static final Pattern PREPROCESSOR = Pattern.compile(
            "^\\s*(//#)([A-Za-z-]+)(?:\\s+(.*))?$");
    static final Pattern EMBEDDED_PREPROCESSOR = Pattern.compile(
            "^\\s*((?://\\$\\$\\s*)+)(//#)([A-Za-z-]+)(?:\\s+(.*))?$");
    static final Pattern PREPROCESSOR_WORD = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    static final Pattern PREPROCESSOR_OPERATOR = Pattern.compile("&&|\\|\\||==|!=|<=|>=|<|>|\\.\\.|\\bin\\b|\\bnot\\b");
    static final Pattern PREPROCESSOR_NUMBER = Pattern.compile("(?<![A-Za-z_$])[0-9][0-9._]*(?![A-Za-z_$])");

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof PsiComment comment)) return;
        String text = comment.getText();
        Matcher preprocessor = PREPROCESSOR.matcher(text);
        if (preprocessor.find()) {
            TextAttributesKey directiveColor = conditionalDirectiveColor(
                    comment, preprocessor.group(2));
            if (directiveColor == null) directiveColor = MUTED_ROSE;
            colorGroup(holder, comment, preprocessor, 1, directiveColor);
            colorGroup(holder, comment, preprocessor, 2, directiveColor);
            if (preprocessor.start(3) >= 0) {
                String argument = preprocessor.group(3);
                int argumentStart = preprocessor.start(3);
                int conditionEnd = argument.length();
                int codeStart = -1;
                if ("replace".equals(preprocessor.group(2))) {
                    int separator = argument.indexOf('?');
                    if (separator >= 0) {
                        conditionEnd = separator;
                        color(holder, comment, 0, argumentStart + separator,
                                argumentStart + separator + 1, MUTED_YELLOW);
                        codeStart = separator + 1;
                    }
                }
                color(holder, comment, 0, argumentStart, argumentStart + conditionEnd, LIGHT_GRAY);
                String condition = argument.substring(0, conditionEnd);
                colorMatches(holder, comment, condition, argumentStart,
                        PREPROCESSOR_WORD, MUTED_PURPLE);
                colorMatches(holder, comment, condition, argumentStart,
                        PREPROCESSOR_OPERATOR, MUTED_YELLOW);
                colorMatches(holder, comment, condition, argumentStart,
                        PREPROCESSOR_NUMBER, MUTED_BLUE);
                if (codeStart >= 0) {
                    while (codeStart < argument.length() && Character.isWhitespace(argument.charAt(codeStart))) codeStart++;
                    String code = argument.substring(codeStart);
                    color(holder, comment, argumentStart + codeStart, 0, code.length(), LIGHT_GRAY);
                    highlightJava(holder, comment, argumentStart + codeStart, code);
                }
            }
            return;
        }
        Matcher embeddedPreprocessor = EMBEDDED_PREPROCESSOR.matcher(text);
        if (embeddedPreprocessor.find()) {
            TextAttributesKey directiveColor = conditionalDirectiveColor(
                    comment, embeddedPreprocessor.group(3));
            if (directiveColor == null) directiveColor = MUTED_ROSE;
            colorGroup(holder, comment, embeddedPreprocessor, 2, directiveColor);
            colorGroup(holder, comment, embeddedPreprocessor, 3, directiveColor);
            if (embeddedPreprocessor.start(4) >= 0) {
                String argument = embeddedPreprocessor.group(4);
                int argumentStart = embeddedPreprocessor.start(4);
                color(holder, comment, 0, argumentStart,
                        argumentStart + argument.length(), LIGHT_GRAY);
                colorMatches(holder, comment, argument, argumentStart,
                        PREPROCESSOR_WORD, MUTED_PURPLE);
                colorMatches(holder, comment, argument, argumentStart,
                        PREPROCESSOR_OPERATOR, MUTED_YELLOW);
                colorMatches(holder, comment, argument, argumentStart,
                        PREPROCESSOR_NUMBER, MUTED_BLUE);
            }
            colorLeadingCodeMarkers(holder, comment, text,
                    embeddedPreprocessor.start(1), embeddedPreprocessor.end(1));
            return;
        }
        if (text.startsWith("/*#case") || text.startsWith("/*?")) {
            int markerEnd = text.startsWith("/*#case") ? Math.min(text.length(), 8) : 3;
            color(holder, comment, 0, 0, markerEnd, MUTED_ROSE);
            if (text.startsWith("/*?")) {
                int separator = text.indexOf('?', 3);
                int commentEnd = text.endsWith("*/") ? text.length() - 2 : text.length();
                int conditionEnd = separator < 0 ? commentEnd : separator;
                if (conditionEnd > 3) {
                    color(holder, comment, 0, 3, conditionEnd, LIGHT_GRAY);
                    String condition = text.substring(3, conditionEnd);
                    colorMatches(holder, comment, condition, 3, PREPROCESSOR_WORD, MUTED_PURPLE);
                    colorMatches(holder, comment, condition, 3, PREPROCESSOR_OPERATOR, MUTED_YELLOW);
                    colorMatches(holder, comment, condition, 3, PREPROCESSOR_NUMBER, MUTED_BLUE);
                }
                if (separator >= 0) {
                    color(holder, comment, 0, separator, separator + 1, MUTED_YELLOW);
                    int bodyStart = separator + 1;
                    while (bodyStart < commentEnd && Character.isWhitespace(text.charAt(bodyStart))) bodyStart++;
                    String body = text.substring(bodyStart, commentEnd);
                    color(holder, comment, bodyStart, 0, body.length(), LIGHT_GRAY);
                    highlightJava(holder, comment, bodyStart, body);
                }
            }
            return;
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
            color(holder, comment, 0, caseMarker, caseMarker + 3, MUTED_ROSE);
            if (conditionEnd > restStart) {
                color(holder, comment, 0, restStart, conditionEnd, LIGHT_GRAY);
                String condition = text.substring(restStart, conditionEnd);
                colorMatches(holder, comment, condition, restStart, PREPROCESSOR_WORD, MUTED_PURPLE);
                colorMatches(holder, comment, condition, restStart, PREPROCESSOR_OPERATOR, MUTED_YELLOW);
                colorMatches(holder, comment, condition, restStart, PREPROCESSOR_NUMBER, MUTED_BLUE);
            }
            if (separator >= 0) color(holder, comment, 0, separator, separator + 1, MUTED_YELLOW);
            if (codeStart >= 0) {
                while (codeStart < text.length() && Character.isWhitespace(text.charAt(codeStart))) codeStart++;
                String code = text.substring(codeStart);
                color(holder, comment, codeStart, 0, code.length(), LIGHT_GRAY);
                highlightJava(holder, comment, codeStart, code);
            }
            return;
        }
        int marker = text.indexOf("//$$");
        boolean block = false;
        if (marker < 0) {
            marker = text.indexOf("/*$$");
            block = marker >= 0;
        }
        if (marker < 0 || !text.substring(0, marker).isBlank()) return;
        int bodyStart = marker + 4;
        int bodyEnd = block ? text.lastIndexOf("$$*/") : text.length();
        if (bodyEnd < bodyStart) bodyEnd = text.length();
        if (!block) {
            int codeStart = colorLeadingCodeMarkers(holder, comment, text, marker, bodyEnd);
            if (codeStart < bodyEnd) {
                String code = text.substring(codeStart, bodyEnd);
                color(holder, comment, codeStart, 0, code.length(), LIGHT_GRAY);
                if (!code.isBlank()) highlightJava(holder, comment, codeStart, code);
            }
            return;
        }
        String body = text.substring(bodyStart, bodyEnd);
        TextAttributesKey markerColor = activeConditionalColor(comment);
        if (markerColor == null) markerColor = MUTED_ROSE;
        color(holder, comment, 0, marker, marker + 4, markerColor);
        if (block && bodyEnd < text.length()) {
            color(holder, comment, 0, bodyEnd, Math.min(text.length(), bodyEnd + 4), markerColor);
        }
        color(holder, comment, bodyStart, 0, body.length(), LIGHT_GRAY);
        if (!body.isBlank()) highlightJava(holder, comment, bodyStart, body);
    }

    private static void highlightJava(AnnotationHolder holder, PsiComment comment,
                                      int bodyStart, String body) {
        JavaFileHighlighter highlighter = new JavaFileHighlighter(LanguageLevel.HIGHEST);
        Lexer lexer = highlighter.getHighlightingLexer();
        lexer.start(body);
        List<Token> tokens = new ArrayList<>();
        while (lexer.getTokenType() != null) {
            IElementType type = lexer.getTokenType();
            int start = lexer.getTokenStart();
            int end = lexer.getTokenEnd();
            tokens.add(new Token(type, start, end, body.substring(start, end)));
            TextAttributesKey[] keys = highlighter.getTokenHighlights(type);
            if (type == JavaTokenType.AT) {
                color(holder, comment, bodyStart, start, end, MUTED_YELLOW);
            } else if (keys.length > 0 && type != JavaTokenType.IDENTIFIER
                    && keepsJavaColor(keys[0])) {
                color(holder, comment, bodyStart, start, end, mutedSyntaxColor(keys[0]));
            }
            lexer.advance();
        }

        Matcher declaration = DECLARATION.matcher(body);
        int declarationStart = declaration.find() ? declaration.start(1) : -1;
        int declarationEnd = declarationStart < 0 ? -1 : declaration.end(1);
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.type() != JavaTokenType.IDENTIFIER) continue;
            Token previous = significant(tokens, index, -1);
            Token next = significant(tokens, index, 1);
            TextAttributesKey key = null;
            if (previous != null && previous.type() == JavaTokenType.AT) {
                key = MUTED_YELLOW;
            } else if (next != null && next.type() == JavaTokenType.EQ
                    && isAnnotationAttribute(comment, bodyStart + token.end())) {
                key = LIGHT_GRAY;
            } else if (isAllUppercase(token.text())) {
                key = MUTED_PURPLE;
            } else if (next != null && next.type() == JavaTokenType.LPARENTH) {
                if (declarationStart == token.start()) {
                    key = MUTED_BLUE;
                }
            } else if (token.start() == declarationStart && token.end() == declarationEnd) {
                key = MUTED_PURPLE;
            } else if (previous != null && previous.type() == JavaTokenType.DOT) {
                key = MUTED_PURPLE;
            }
            if (key != null) {
                color(holder, comment, bodyStart, token.start(), token.end(), key);
            }
        }
    }

    static boolean opensConditional(String directive) {
        return "if".equals(directive) || "ifdef".equals(directive)
                || "ifndef".equals(directive);
    }

    static boolean continuesConditional(String directive) {
        return "elseif".equals(directive) || "elif".equals(directive)
                || "else".equals(directive);
    }

    static boolean closesConditional(String directive) {
        return "endif".equals(directive);
    }

    private static TextAttributesKey conditionalDirectiveColor(PsiComment comment,
                                                                String directive) {
        if (!opensConditional(directive) && !continuesConditional(directive)
                && !closesConditional(directive)) return null;
        Document document = PsiDocumentManager.getInstance(comment.getProject())
                .getDocument(comment.getContainingFile());
        if (document == null) return PREPROCESSOR_SCOPE_COLORS[0];
        int depth = conditionalDepthBeforeLine(document,
                document.getLineNumber(comment.getTextOffset()));
        int level = opensConditional(directive) ? depth : Math.max(0, depth - 1);
        return PREPROCESSOR_SCOPE_COLORS[level % PREPROCESSOR_SCOPE_COLORS.length];
    }

    private static TextAttributesKey activeConditionalColor(PsiComment comment) {
        Document document = PsiDocumentManager.getInstance(comment.getProject())
                .getDocument(comment.getContainingFile());
        if (document == null) return null;
        int depth = conditionalDepthBeforeLine(document,
                document.getLineNumber(comment.getTextOffset()));
        return depth == 0 ? null
                : PREPROCESSOR_SCOPE_COLORS[(depth - 1) % PREPROCESSOR_SCOPE_COLORS.length];
    }

    private static int colorLeadingCodeMarkers(AnnotationHolder holder, PsiComment comment,
                                               String text, int start, int end) {
        Document document = PsiDocumentManager.getInstance(comment.getProject())
                .getDocument(comment.getContainingFile());
        int depth = document == null ? 0 : conditionalDepthBeforeLine(document,
                document.getLineNumber(comment.getTextOffset()));
        int index = 0;
        int cursor = start;
        while (cursor < end) {
            while (cursor < end && Character.isWhitespace(text.charAt(cursor))) cursor++;
            if (cursor + 4 > end || !text.startsWith("//$$", cursor)) break;
            TextAttributesKey key = index < depth
                    ? PREPROCESSOR_SCOPE_COLORS[index % PREPROCESSOR_SCOPE_COLORS.length]
                    : MUTED_ROSE;
            color(holder, comment, 0, cursor, cursor + 4, key);
            index++;
            cursor += 4;
        }
        return cursor;
    }

    private static int conditionalDepthBeforeLine(Document document, int currentLine) {
        int depth = 0;
        for (int line = 0; line < currentLine; line++) {
            String lineText = document.getText().substring(
                    document.getLineStartOffset(line), document.getLineEndOffset(line));
            String previous = directiveOnLine(lineText);
            if (previous == null) continue;
            if (opensConditional(previous)) depth++;
            else if (closesConditional(previous)) depth = Math.max(0, depth - 1);
        }
        return depth;
    }

    private static String directiveOnLine(String lineText) {
        Matcher direct = PREPROCESSOR.matcher(lineText);
        if (direct.find()) return direct.group(2);
        Matcher embedded = EMBEDDED_PREPROCESSOR.matcher(lineText);
        return embedded.find() ? embedded.group(3) : null;
    }

    private static TextAttributesKey scopeColor(String name, int light, int dark) {
        return TextAttributesKey.createTextAttributesKey(name,
                new TextAttributes(new JBColor(new Color(light), new Color(dark)),
                        null, null, null, Font.PLAIN));
    }

    private static boolean isAnnotationAttribute(PsiComment comment, int relativeOffset) {
        Document document = PsiDocumentManager.getInstance(comment.getProject())
                .getDocument(comment.getContainingFile());
        if (document == null) return false;
        String logical = MixinCommentContext.logicalContext(
                document, comment.getTextOffset() + relativeOffset);
        return MixinCommentContext.enclosingAnnotation(logical) != null;
    }

    private static Token significant(List<Token> tokens, int index, int direction) {
        for (int current = index + direction;
             current >= 0 && current < tokens.size(); current += direction) {
            Token token = tokens.get(current);
            if (!token.text().isBlank()) return token;
        }
        return null;
    }

    static boolean isAllUppercase(String value) {
        boolean letter = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isLetter(character)) continue;
            letter = true;
            if (Character.isLowerCase(character)) return false;
        }
        return letter;
    }

    static boolean keepsJavaColor(TextAttributesKey key) {
        return usesFalconScheme()
                || key == JavaHighlightingColors.KEYWORD
                || key == JavaHighlightingColors.NUMBER
                || key == JavaHighlightingColors.STRING
                || key == JavaHighlightingColors.VALID_STRING_ESCAPE
                || key == JavaHighlightingColors.INVALID_STRING_ESCAPE;
    }

    static TextAttributesKey mutedSyntaxColor(TextAttributesKey key) {
        if (usesFalconScheme()) return key;
        if (key == JavaHighlightingColors.KEYWORD) return MUTED_ROSE;
        if (key == JavaHighlightingColors.NUMBER) return MUTED_BLUE;
        if (key == JavaHighlightingColors.STRING
                || key == JavaHighlightingColors.VALID_STRING_ESCAPE) return MUTED_GREEN;
        return key;
    }

    static TextAttributesKey themeAwareKey(TextAttributesKey key) {
        if (!usesFalconScheme()) return key;
        if (key == LIGHT_GRAY) return DefaultLanguageHighlighterColors.IDENTIFIER;
        if (key == MUTED_ROSE) return DefaultLanguageHighlighterColors.KEYWORD;
        if (key == MUTED_YELLOW) return DefaultLanguageHighlighterColors.METADATA;
        if (key == MUTED_BLUE) return DefaultLanguageHighlighterColors.NUMBER;
        if (key == MUTED_GREEN) return DefaultLanguageHighlighterColors.STRING;
        if (key == MUTED_PURPLE) return DefaultLanguageHighlighterColors.FUNCTION_CALL;
        if (key == PREPROCESSOR_SCOPE_COLORS[0]) return DefaultLanguageHighlighterColors.CLASS_NAME;
        if (key == PREPROCESSOR_SCOPE_COLORS[1]) return DefaultLanguageHighlighterColors.METADATA;
        if (key == PREPROCESSOR_SCOPE_COLORS[2]) return DefaultLanguageHighlighterColors.FUNCTION_CALL;
        if (key == PREPROCESSOR_SCOPE_COLORS[3]) return DefaultLanguageHighlighterColors.STRING;
        if (key == PREPROCESSOR_SCOPE_COLORS[4]) return DefaultLanguageHighlighterColors.NUMBER;
        if (key == PREPROCESSOR_SCOPE_COLORS[5]) return DefaultLanguageHighlighterColors.KEYWORD;
        return key;
    }

    private static boolean usesFalconScheme() {
        String name = EditorColorsManager.getInstance().getGlobalScheme().getName();
        return name.startsWith("Relax ") || name.startsWith("Islands Relax ");
    }

    private static void color(AnnotationHolder holder, PsiComment comment, int bodyStart,
                              int start, int end, TextAttributesKey key) {
        int absoluteStart = comment.getTextRange().getStartOffset() + bodyStart + start;
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange.create(absoluteStart, absoluteStart + end - start))
                .textAttributes(themeAwareKey(key))
                .create();
    }

    private static void colorGroup(AnnotationHolder holder, PsiComment comment,
                                   Matcher matcher, int group, TextAttributesKey key) {
        if (matcher.start(group) < 0) return;
        color(holder, comment, 0, matcher.start(group), matcher.end(group), key);
    }

    private static void colorMatches(AnnotationHolder holder, PsiComment comment, String text,
                                     int base, Pattern pattern, TextAttributesKey key) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) color(holder, comment, 0, base + matcher.start(), base + matcher.end(), key);
    }

    private record Token(IElementType type, int start, int end, String text) {}
}
