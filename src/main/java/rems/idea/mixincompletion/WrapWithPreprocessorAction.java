package rems.idea.mixincompletion;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidator;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class WrapWithPreprocessorAction extends DumbAwareAction {
    private static final String CONDITION_KEY =
            "rems.mixincompletion.last.preprocessor.condition";
    public WrapWithPreprocessorAction() {
        super("Wrap with MC Preprocessor",
                "Add //#if, //$$ line prefixes, and //#endif to the selected code",
                AllIcons.Actions.RefactoringBulb);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        String selectedText = editor == null ? null
                : editor.getSelectionModel().getSelectedText();
        boolean available = editor != null && editor.getVirtualFile() != null
                && "java".equalsIgnoreCase(editor.getVirtualFile().getExtension())
                && selectedText != null && !selectedText.isBlank();
        event.getPresentation().setEnabledAndVisible(available);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) return;
        PropertiesComponent properties = PropertiesComponent.getInstance(project);
        String previous = properties.getValue(CONDITION_KEY, "MC>=12101");
        String condition = Messages.showInputDialog(
                project,
                "Enter the preprocessor condition, for example MC>=12101:",
                "Wrap with MC Preprocessor",
                AllIcons.Actions.RefactoringBulb,
                previous,
                new InputValidator() {
                    @Override
                    public boolean checkInput(String inputString) {
                        return inputString != null
                                && PreprocessorLanguage.isValidExpression(normalizeCondition(inputString));
                    }

                    @Override
                    public boolean canClose(String inputString) {
                        return checkInput(inputString);
                    }
                });
        if (condition == null) return;
        condition = normalizeCondition(condition);
        properties.setValue(CONDITION_KEY, condition);
        String finalCondition = condition;
        WriteCommandAction.runWriteCommandAction(project,
                "Wrap with MC Preprocessor", null,
                () -> wrapSelection(editor, finalCondition));
    }

    private static String normalizeCondition(String value) {
        String normalized = value.strip();
        if (normalized.startsWith("//#if")) normalized = normalized.substring(5).strip();
        else if (normalized.startsWith("if")) normalized = normalized.substring(2).strip();
        return normalized;
    }

    private static void wrapSelection(Editor editor, String condition) {
        Document document = editor.getDocument();
        SelectionModel selection = editor.getSelectionModel();
        int selectionStart = selection.getSelectionStart();
        int selectionEnd = selection.getSelectionEnd();
        int startLine = document.getLineNumber(selectionStart);
        int endProbe = selectionEnd;
        if (selectionEnd > selectionStart
                && selectionEnd == document.getLineStartOffset(document.getLineNumber(selectionEnd))) {
            endProbe--;
        }
        int endLine = document.getLineNumber(Math.max(selectionStart, endProbe));
        int replaceStart = document.getLineStartOffset(startLine);
        int replaceEnd = document.getLineEndOffset(endLine);

        List<String> lines = new ArrayList<>();
        String baseIndent = null;
        for (int line = startLine; line <= endLine; line++) {
            String text = document.getText().substring(
                    document.getLineStartOffset(line), document.getLineEndOffset(line));
            lines.add(text);
            if (!text.isBlank()) {
                String indent = leadingWhitespace(text);
                baseIndent = baseIndent == null ? indent : commonPrefix(baseIndent, indent);
            }
        }
        if (baseIndent == null) return;

        List<String> wrapped = new ArrayList<>();
        wrapped.add(baseIndent + "//#if " + condition);
        for (String line : lines) {
            String content = line.startsWith(baseIndent)
                    ? line.substring(baseIndent.length()) : line.stripLeading();
            wrapped.add(baseIndent + "//$$" + (content.isEmpty() ? "" : " " + content));
        }
        wrapped.add(baseIndent + "//#endif");
        String replacement = String.join("\n", wrapped);
        document.replaceString(replaceStart, replaceEnd, replacement);
        selection.setSelection(replaceStart, replaceStart + replacement.length());
        editor.getCaretModel().moveToOffset(replaceStart + replacement.length());
    }

    private static String leadingWhitespace(String value) {
        int end = 0;
        while (end < value.length() && Character.isWhitespace(value.charAt(end))) end++;
        return value.substring(0, end);
    }

    private static String commonPrefix(String first, String second) {
        int length = Math.min(first.length(), second.length());
        int index = 0;
        while (index < length && first.charAt(index) == second.charAt(index)) index++;
        return first.substring(0, index);
    }
}
