package rems.idea.mixincompletion;

import com.intellij.codeInsight.lookup.CharFilter;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiJavaFile;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

public final class MixinCompletionCharFilter extends CharFilter {
    private static final Pattern PRIMARY_VERSION_PREFIX = Pattern.compile(
            "^\\s*(?://\\$\\$\\s*)*//#(?:if|elseif|elif|replace)\\s+M$");

    @Override
    public @Nullable Result acceptChar(char value, int prefixLength, Lookup lookup) {
        if (!(lookup.getPsiFile() instanceof PsiJavaFile)) return null;
        Editor editor = lookup.getEditor();
        Document document = editor.getDocument();
        int offset = Math.min(editor.getCaretModel().getOffset(), document.getTextLength());
        if (!MixinCommentContext.isPreprocessorCompletionPosition(document, offset)
                && !MixinCommentContext.isCompletionPosition(document, offset)) {
            return null;
        }

        if (value == 'C') {
            int line = document.getLineNumber(offset);
            int lineStart = document.getLineStartOffset(line);
            String beforeCaret = document.getText().substring(lineStart, offset);
            if (PRIMARY_VERSION_PREFIX.matcher(beforeCaret).matches()) {
                // Close the MC name lookup first. Otherwise IDEA consumes C as another
                // lookup-prefix character and the typed handler never gets a chance to
                // open the operator lookup for the completed MC expression.
                return Result.HIDE_LOOKUP;
            }
        }

        // Typed characters must never accept a candidate. Tab and Enter are actions rather
        // than typed characters, so IDEA can continue to use them to finish the lookup.
        if (Character.isJavaIdentifierPart(value) || value == '.') {
            return Result.ADD_TO_PREFIX;
        }
        return Result.HIDE_LOOKUP;
    }
}
