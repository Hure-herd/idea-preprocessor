package rems.idea.mixincompletion;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

public final class MixinCommentTypedHandler extends TypedHandlerDelegate {
    private static final Logger LOG = Logger.getInstance(MixinCommentTypedHandler.class);
    private static final Pattern COMPLETE_MC_EXPRESSION = Pattern.compile(
            "^\\s*(?://\\$\\$\\s*)*//#(?:if|elseif|elif|replace)\\s+MC\\s*$");

    @Override
    public @NotNull Result beforeCharTyped(char charTyped,
                                           @NotNull Project project,
                                           @NotNull Editor editor,
                                           @NotNull PsiFile file,
                                           @NotNull FileType fileType) {
        if (!(file instanceof PsiJavaFile)) {
            return Result.CONTINUE;
        }
        int caretOffset = editor.getCaretModel().getOffset();
        if (LookupManager.getActiveLookup(editor) != null
                && (MixinCommentContext.isPreprocessorCompletionPosition(
                        editor.getDocument(), caretOffset)
                || MixinCommentContext.isCompletionPosition(
                        editor.getDocument(), caretOffset))) {
            LookupManager.getInstance(project).hideActiveLookup();
        }
        if ("(){}[]\"".indexOf(charTyped) < 0) return Result.CONTINUE;
        Document document = editor.getDocument();
        int offset = caretOffset;
        int line = document.getLineNumber(Math.min(offset, document.getTextLength()));
        int lineStart = document.getLineStartOffset(line);
        String beforeCaret = MixinCommentContext.javaCodeBeforeCaret(document, offset);
        if (beforeCaret == null) {
            return Result.CONTINUE;
        }

        if (")}]}\"".indexOf(charTyped) >= 0
                && offset < document.getTextLength()
                && document.getCharsSequence().charAt(offset) == charTyped) {
            editor.getCaretModel().moveToOffset(offset + 1);
            return Result.STOP;
        }

        if (charTyped == '"') {
            if ((countUnescapedQuotes(beforeCaret) & 1) != 0) return Result.CONTINUE;
            return insertPair(document, editor, offset, "\"\"");
        }
        return switch (charTyped) {
            case '(' -> insertPair(document, editor, offset, "()");
            case '{' -> insertPair(document, editor, offset, "{}");
            case '[' -> insertPair(document, editor, offset, "[]");
            default -> Result.CONTINUE;
        };
    }

    @Override
    public @NotNull Result charTyped(char charTyped,
                                     @NotNull Project project,
                                     @NotNull Editor editor,
                                     @NotNull PsiFile file) {
        if (!(file instanceof PsiJavaFile)) {
            return Result.CONTINUE;
        }

        if (!isCompletionCharacter(charTyped)) return Result.CONTINUE;

        int offset = editor.getCaretModel().getOffset();
        if (appendDirectiveSpace(editor.getDocument(), editor, offset)) {
            return Result.CONTINUE;
        }
        if (MixinCommentContext.isPreprocessorCompletionPosition(editor.getDocument(), offset)) {
            schedulePreprocessorLookup(project, editor, file);
            return Result.CONTINUE;
        }
        if (!MixinCommentContext.isCompletionPosition(editor.getDocument(), offset)) {
            return Result.CONTINUE;
        }

        LookupElement[] items = MixinDescriptorCompletionContributor.commentItems(
                file, editor.getDocument(), offset);
        LOG.info("//$$ completion candidates: " + items.length);
        showLookup(project, editor, items);
        return Result.CONTINUE;
    }

    private static boolean isCompletionCharacter(char value) {
        return Character.isJavaIdentifierPart(value) || ".,@#<>=!&|[?".indexOf(value) >= 0;
    }

    private static void showLookup(Project project, Editor editor, LookupElement[] items) {
        LookupManager lookupManager = LookupManager.getInstance(project);
        if (LookupManager.getActiveLookup(editor) != null) {
            lookupManager.hideActiveLookup();
        }
        if (items.length > 0) lookupManager.showLookup(editor, items);
    }

    private static void schedulePreprocessorLookup(Project project, Editor editor, PsiFile file) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (editor.isDisposed() || project.isDisposed()) return;
            Document document = editor.getDocument();
            int offset = Math.min(editor.getCaretModel().getOffset(), document.getTextLength());
            if (!MixinCommentContext.isPreprocessorCompletionPosition(document, offset)) return;
            int line = document.getLineNumber(offset);
            int lineStart = document.getLineStartOffset(line);
            String beforeCaret = document.getText().substring(lineStart, offset);
            LookupElement[] items = MixinDescriptorCompletionContributor.preprocessorItems(
                    file, beforeCaret);
            if (items.length > 0 && COMPLETE_MC_EXPRESSION.matcher(beforeCaret).matches()) {
                CodeCompletionHandlerBase.createHandler(CompletionType.BASIC)
                        .invokeCompletion(project, editor, 1);
                return;
            }
            showLookup(project, editor, items);
        });
    }

    private static boolean appendDirectiveSpace(Document document, Editor editor, int offset) {
        int line = document.getLineNumber(Math.min(offset, document.getTextLength()));
        int lineStart = document.getLineStartOffset(line);
        String beforeCaret = document.getText().substring(lineStart, offset).stripLeading();
        PreprocessorLanguage.ParsedDirective parsed = PreprocessorLanguage.parseDirective(beforeCaret);
        if (parsed == null || !parsed.argument().isEmpty()) return false;
        PreprocessorLanguage.Directive directive = PreprocessorLanguage.directive(parsed.name());
        if (directive == null || directive.argument() == PreprocessorLanguage.Argument.NONE) return false;
        document.insertString(offset, " ");
        editor.getCaretModel().moveToOffset(offset + 1);
        return true;
    }

    private static Result insertPair(Document document, Editor editor, int offset, String pair) {
        document.insertString(offset, pair);
        editor.getCaretModel().moveToOffset(offset + 1);
        return Result.STOP;
    }

    private static int countUnescapedQuotes(String text) {
        int unescapedQuotes = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                unescapedQuotes++;
            }
        }
        return unescapedQuotes;
    }
}
