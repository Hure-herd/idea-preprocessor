package rems.idea.mixincompletion;

import com.intellij.codeInsight.completion.CompletionConfidence;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ThreeState;
import org.jetbrains.annotations.NotNull;

public final class MixinCommentCompletionConfidence extends CompletionConfidence {
    @Override
    public @NotNull ThreeState shouldSkipAutopopup(@NotNull Editor editor,
                                                   @NotNull PsiElement contextElement,
                                                   @NotNull PsiFile psiFile,
                                                   int offset) {
        return (MixinCommentContext.isCompletionPosition(editor.getDocument(), offset)
                || MixinCommentContext.isPreprocessorCompletionPosition(editor.getDocument(), offset))
                ? ThreeState.NO
                : ThreeState.UNSURE;
    }
}
