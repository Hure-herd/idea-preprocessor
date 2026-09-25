package rems.idea.mixincompletion;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MixinCommentInjectSignatureInspection extends LocalInspectionTool {
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "^\\s*//\\$\\$\\s+(?:(?:public|protected|private|abstract|final|static|synchronized|native|strictfp)\\s+)+"
                    + "[A-Za-z_$][A-Za-z0-9_$.<>?\\[\\]]*\\s+[A-Za-z_$][A-Za-z0-9_$]*\\(");
    private static final Pattern INJECT_METHOD = Pattern.compile(
            "@Inject\\s*\\(.*?\\bmethod\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitComment(@NotNull PsiComment comment) {
                checkComment(comment, holder);
            }
        };
    }

    private static void checkComment(PsiComment comment, ProblemsHolder holder) {
        Matcher declaration = METHOD_DECLARATION.matcher(comment.getText());
        if (!declaration.find()) return;
        PsiFile file = comment.getContainingFile();
        Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null) return;
        int line = document.getLineNumber(comment.getTextOffset());
        String selector = findInjectMethod(document, line);
        if (selector == null) return;
        int opening = comment.getText().indexOf('(', declaration.start());
        if (opening < 0) return;
        ParameterSpan span = findParameterSpan(document, line,
                comment.getTextOffset() + opening);
        if (span == null) return;
        String expected = expectedParameters(comment, document, line, selector);
        if (normalize(span.actual()).equals(normalize(expected))) return;
        holder.registerProblem(comment, TextRange.create(opening, opening + 1),
                "Method signature does not match expected signature for Inject",
                new FixSignatureQuickFix(expected));
    }

    private static ParameterSpan findParameterSpan(Document document, int openingLine,
                                                    int openingOffset) {
        int lastLine = Math.min(document.getLineCount() - 1, openingLine + 20);
        for (int line = openingLine; line <= lastLine; line++) {
            int start = line == openingLine ? openingOffset + 1 : document.getLineStartOffset(line);
            int end = document.getLineEndOffset(line);
            String text = document.getText().substring(start, end);
            if (line > openingLine && !text.stripLeading().startsWith("//$$")) return null;
            int closingInPart = text.indexOf(')');
            if (closingInPart < 0) continue;
            int closingOffset = start + closingInPart;
            String actual = document.getText().substring(openingOffset + 1, closingOffset)
                    .replaceAll("(?m)^\\s*//\\$\\$\\s?", "");
            return new ParameterSpan(openingOffset, closingOffset, openingLine, line, actual);
        }
        return null;
    }

    private static String expectedParameters(PsiComment comment, Document document,
                                             int line, String selector) {
        PsiClass currentTarget = MixinDescriptorCompletionContributor.findMixinTarget(comment);
        if (currentTarget == null) return "CallbackInfo ci";
        PsiClass target = VersionedModuleResolver.retarget(
                currentTarget, comment.getProject(), document, line);
        String name = selector.contains("(")
                ? selector.substring(0, selector.indexOf('(')) : selector;
        PsiMethod[] methods = target.findMethodsByName(name, false);
        return methods.length == 0 ? "CallbackInfo ci" : injectParameters(methods[0]);
    }

    private static String injectParameters(PsiMethod method) {
        List<String> parameters = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            parameters.add(parameter.getType().getPresentableText() + " " + parameter.getName());
        }
        PsiType returnType = method.getReturnType();
        if (returnType == null || "void".equals(returnType.getCanonicalText())) {
            parameters.add("CallbackInfo ci");
        } else {
            parameters.add("CallbackInfoReturnable<" + boxedType(returnType) + "> cir");
        }
        return String.join(", ", parameters);
    }

    private static String boxedType(PsiType type) {
        return switch (type.getCanonicalText()) {
            case "boolean" -> "Boolean";
            case "byte" -> "Byte";
            case "char" -> "Character";
            case "short" -> "Short";
            case "int" -> "Integer";
            case "long" -> "Long";
            case "float" -> "Float";
            case "double" -> "Double";
            default -> type.getPresentableText();
        };
    }

    private static String findInjectMethod(Document document, int currentLine) {
        int firstLine = Math.max(0, currentLine - 12);
        int blockStart = currentLine;
        for (int line = currentLine - 1; line >= firstLine; line--) {
            String text = document.getText().substring(
                    document.getLineStartOffset(line), document.getLineEndOffset(line));
            if (!text.stripLeading().startsWith("//$$")) break;
            blockStart = line;
        }
        StringBuilder logical = new StringBuilder();
        for (int line = blockStart; line < currentLine; line++) {
            String text = document.getText().substring(
                    document.getLineStartOffset(line), document.getLineEndOffset(line)).stripLeading();
            if (!text.startsWith("//$$")) continue;
            logical.append(text.substring(4).stripLeading()).append('\n');
        }
        Matcher matcher = INJECT_METHOD.matcher(logical);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", "").trim();
    }

    private record ParameterSpan(int openingOffset, int closingOffset,
                                 int openingLine, int closingLine, String actual) {}

    private static final class FixSignatureQuickFix implements LocalQuickFix {
        private final String expected;

        private FixSignatureQuickFix(String expected) {
            this.expected = expected;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Fix method signature";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiComment comment = (PsiComment) descriptor.getPsiElement();
            Document document = PsiDocumentManager.getInstance(project)
                    .getDocument(comment.getContainingFile());
            if (document == null) return;
            String text = comment.getText();
            Matcher matcher = METHOD_DECLARATION.matcher(text);
            if (!matcher.find()) return;
            int opening = text.indexOf('(', matcher.start());
            if (opening < 0) return;
            int openingOffset = comment.getTextOffset() + opening;
            int openingLine = document.getLineNumber(openingOffset);
            ParameterSpan span = findParameterSpan(document, openingLine, openingOffset);
            if (span == null) return;
            String replacement = expected;
            if (span.openingLine() != span.closingLine()) {
                int lineStart = document.getLineStartOffset(openingLine);
                String openingText = document.getText().substring(lineStart, openingOffset);
                int marker = openingText.indexOf("//$$");
                String indentation = marker < 0 ? "" : openingText.substring(0, marker);
                String lineSeparator = document.getText().contains("\r\n") ? "\r\n" : "\n";
                replacement = lineSeparator + indentation + "//$$         " + expected
                        + lineSeparator + indentation + "//$$ ";
            }
            document.replaceString(span.openingOffset() + 1, span.closingOffset(), replacement);
            PsiDocumentManager.getInstance(project).commitDocument(document);
        }
    }
}
