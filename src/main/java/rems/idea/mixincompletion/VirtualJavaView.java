package rems.idea.mixincompletion;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionInitializationContext;
import com.intellij.codeInsight.completion.CompletionResult;
import com.intellij.codeInsight.completion.CompletionService;
import com.intellij.codeInsight.completion.CompletionProcess;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiImportStatementBase;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Builds a context-bound virtual Java file from preprocessor-commented source. */
final class VirtualJavaView {
    private VirtualJavaView() {}

    static PreparedCompletion prepare(PsiFile hostFile, Document hostDocument,
                                      int hostOffset, int invocationCount,
                                      Editor editor, CompletionProcess process) {
        if (process == null) return null;
        Project project = hostFile.getProject();
        if (hostOffset < 0 || hostOffset > hostDocument.getTextLength()) return null;

        int lookupOffset = Math.max(0, Math.min(hostOffset - 1, hostFile.getTextLength() - 1));
        PsiElement leaf = hostFile.findElementAt(lookupOffset);
        PsiComment comment = PsiTreeUtil.getParentOfType(leaf, PsiComment.class, false);
        PsiClass hostClass = PsiTreeUtil.getParentOfType(comment, PsiClass.class, false);
        if (hostClass == null || hostClass.getLBrace() == null) return null;

        int bodyStart = hostClass.getLBrace().getTextRange().getEndOffset();
        int bodyEnd = hostClass.getRBrace() == null
                ? hostDocument.getTextLength() : hostClass.getRBrace().getTextRange().getStartOffset();
        if (hostOffset < bodyStart || hostOffset > bodyEnd) return null;

        int caretLine = hostDocument.getLineNumber(hostOffset);
        Module selectedModule = resolveModuleForView(hostClass, hostDocument, caretLine);
        PsiClass versionedClass = resolveContextClass(hostClass, selectedModule);
        PsiFile versionedContext = versionedClass.getContainingFile();
        JavaFileView view = buildJavaFileView(hostDocument, selectedModule);
        int firstClassOffset = hostFile instanceof PsiJavaFile javaFile
                && javaFile.getClasses().length > 0
                ? javaFile.getClasses()[0].getTextRange().getStartOffset()
                : hostClass.getTextRange().getStartOffset();
        int importInsertionLineStart = hostDocument.getLineStartOffset(
                hostDocument.getLineNumber(firstClassOffset));
        view = addContextImports(hostFile, versionedContext, selectedModule,
                importInsertionLineStart, view);

        String virtualText = view.text();
        int virtualOffset = view.mapOffset(hostOffset);
        String prefix = identifierPrefix(virtualText, virtualOffset);
        PreparedCompletion completion = prepareJavaCompletion(project, versionedContext,
                virtualText, virtualOffset, invocationCount, editor, process, prefix);
        return completion;
    }

    private static JavaFileView addContextImports(PsiFile hostFile,
                                                  PsiFile versionedContext, Module selectedModule,
                                                  int insertionHostOffset, JavaFileView view) {
        Set<String> importsInView = new LinkedHashSet<>();
        for (String line : view.text().lines().toList()) {
            String importText = line.strip();
            if (!isJavaImportLine(importText)) continue;
            int semicolon = importText.indexOf(';');
            if (semicolon >= 0) importText = importText.substring(0, semicolon + 1);
            importsInView.add(normalizeImport(importText));
        }

        List<String> missingImports = new ArrayList<>();
        collectContextImports(hostFile, selectedModule, importsInView, missingImports);
        if (versionedContext != null && versionedContext != hostFile) {
            collectContextImports(versionedContext, selectedModule, importsInView, missingImports);
        }
        if (missingImports.isEmpty()) return view;

        String importBlock = String.join("\n", missingImports) + "\n\n";
        int insertionOffset = view.mapOffset(insertionHostOffset);
        StringBuilder withImports = new StringBuilder(view.text());
        withImports.insert(insertionOffset, importBlock);
        return new JavaFileView(withImports.toString(), view.importSemicolonOffsets(),
                insertionHostOffset, importBlock.length());
    }

    private static void collectContextImports(PsiFile contextFile, Module selectedModule,
                                              Set<String> importsInView,
                                              List<String> missingImports) {
        if (!(contextFile instanceof PsiJavaFile javaFile)) return;
        String source = contextFile.getText();
        List<String> lines = source.lines().toList();
        int version = selectedModule == null ? -1
                : PreprocessorLanguage.moduleVersionCode(selectedModule.getName());
        Map<String, Integer> variables = version < 0 ? Map.of() : Map.of("MC", version);
        boolean[] activeByLine = variables.isEmpty()
                ? null : PreprocessorLanguage.computeLineActivity(lines, variables);

        for (PsiImportStatementBase statement : javaFile.getImportList().getAllImportStatements()) {
            if (!variables.isEmpty()) {
                int line = lineNumberAt(source, statement.getTextRange().getStartOffset());
                if (!activeByLine[line]) continue;
            }
            String importText = statement.getText().strip();
            if (!importText.endsWith(";")) importText += ";";
            if (importsInView.add(normalizeImport(importText))) missingImports.add(importText);
        }
    }

    private static int lineNumberAt(String source, int offset) {
        int line = 0;
        for (int index = 0; index < Math.min(offset, source.length()); index++) {
            if (source.charAt(index) == '\n') line++;
        }
        return line;
    }

    private static String normalizeImport(String importText) {
        return importText.replaceAll("\\s+", "").replace(";", "");
    }

    private static PreparedCompletion prepareJavaCompletion(Project project, PsiFile contextFile,
                                                            String virtualText, int virtualOffset,
                                                            int invocationCount, Editor editor,
                                                            CompletionProcess process,
                                                            String prefix) {
        virtualOffset = Math.min(virtualOffset, virtualText.length());
        String dummyIdentifier = CompletionInitializationContext.DUMMY_IDENTIFIER;
        String completionText = virtualText.substring(0, virtualOffset)
                + dummyIdentifier
                + virtualText.substring(virtualOffset);
        int completionOffset = virtualOffset;
        VirtualFile originalVirtualFile = contextFile.getVirtualFile();
        PsiFile virtualFile = PsiFileFactory.getInstance(project).createFileFromText(
                contextFile.getName(), contextFile.getLanguage(), completionText,
                true, true, false, originalVirtualFile);
        if (!(virtualFile instanceof PsiJavaFile)) return null;
        virtualFile.putUserData(PsiFileFactory.ORIGINAL_FILE, contextFile);
        Document virtualDocument = PsiDocumentManager.getInstance(project).getDocument(virtualFile);
        if (virtualDocument == null || virtualDocument.getTextLength() == 0) return null;

        completionOffset = Math.min(completionOffset, virtualDocument.getTextLength());
        int elementOffset = Math.max(0, Math.min(completionOffset,
                virtualDocument.getTextLength() - 1));
        PsiElement position = virtualFile.findElementAt(elementOffset);
        if (position == null) return null;

        CompletionParameters parameters = new CompletionParameters(
                position, virtualFile, CompletionType.BASIC,
                completionOffset, invocationCount, editor, process);
        return new PreparedCompletion(prefix, parameters);
    }

    static final class PreparedCompletion {
        private final String prefix;
        private final CompletionParameters parameters;

        private PreparedCompletion(String prefix, CompletionParameters parameters) {
            this.prefix = prefix;
            this.parameters = parameters;
        }

        String prefix() {
            return prefix;
        }

        void streamTo(Consumer<CompletionResult> consumer) {
            Set<LookupKey> emitted = new HashSet<>();
            CompletionService.getCompletionService().getVariantsFromContributors(
                    parameters, null, (CompletionResult completion) -> {
                    LookupElement item = completion.getLookupElement();
                    if (item != null) {
                        Object object = item.getObject();
                        Object identity = object;
                        if (object instanceof PsiVariable variable) {
                            identity = new VariableKey(object.getClass(), variable.getName(),
                                    variable.getType().getPresentableText());
                        }
                        LookupKey key = new LookupKey(
                                item.getLookupString(), identity, item.getClass());
                        if (emitted.add(key)) consumer.accept(completion);
                    }
                });
        }
    }

    private static Module resolveModuleForView(PsiClass hostClass, Document document, int caretLine) {
        Project project = hostClass.getProject();
        Module selected = VersionedModuleResolver.resolveModule(project, document, caretLine);
        if (selected != null) return selected;

        Module containing = ModuleUtilCore.findModuleForPsiElement(hostClass);
        if (containing != null && PreprocessorLanguage.moduleVersionCode(containing.getName()) >= 0) {
            return containing;
        }

        String source = document.getText();
        if (!source.contains("//#if") && !source.contains("//#case")
                && !source.contains("//?")) return null;
        Module best = null;
        int bestVersion = Integer.MAX_VALUE;
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            int version = PreprocessorLanguage.moduleVersionCode(module.getName());
            if (version < 0 || !module.getName().endsWith(".main")
                    || !PreprocessorLanguage.isLineActive(document, caretLine, Map.of("MC", version))) {
                continue;
            }
            if (version < bestVersion) {
                best = module;
                bestVersion = version;
            }
        }
        return best;
    }

    private static PsiClass resolveContextClass(PsiClass hostClass, Module selectedModule) {
        if (selectedModule == null) return hostClass;
        String qualifiedName = hostClass.getQualifiedName();
        if (qualifiedName == null) return hostClass;
        GlobalSearchScope scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(
                selectedModule, false);
        PsiClass[] matches = JavaPsiFacade.getInstance(hostClass.getProject())
                .findClasses(qualifiedName, scope);
        return matches.length == 0 ? hostClass : matches[0];
    }

    private static JavaFileView buildJavaFileView(Document document, Module selectedModule) {
        int bodyStart = 0;
        int bodyEnd = document.getTextLength();
        CharSequence sourceText = document.getCharsSequence();
        char[] text = sourceText.toString().toCharArray();
        int firstLine = 0;
        int lastLine = Math.max(0, document.getLineCount() - 1);
        int version = selectedModule == null ? -1
                : PreprocessorLanguage.moduleVersionCode(selectedModule.getName());
        Map<String, Integer> variables = version < 0 ? Map.of() : Map.of("MC", version);
        List<String> lines = new ArrayList<>(lastLine + 1);
        for (int line = firstLine; line <= lastLine; line++) {
            lines.add(sourceText.subSequence(document.getLineStartOffset(line),
                    document.getLineEndOffset(line)).toString());
        }
        boolean[] activeByLine = variables.isEmpty()
                ? null : PreprocessorLanguage.computeLineActivity(lines, variables);
        List<Integer> importSemicolonOffsets = new ArrayList<>();
        boolean insideMarkedBlock = false;
        boolean markedBlockActive = true;

        for (int line = firstLine; line <= lastLine; line++) {
            int absoluteStart = document.getLineStartOffset(line);
            int absoluteEnd = document.getLineEndOffset(line);
            String lineText = lines.get(line);
            int localStart = Math.max(0, absoluteStart - bodyStart);
            int localEnd = Math.min(text.length, absoluteEnd - bodyStart);
            if (localStart >= localEnd) continue;

            boolean opensMarkedBlock = lineText.contains("/*$$");
            boolean closesMarkedBlock = lineText.contains("$$*/");
            if (opensMarkedBlock) {
                insideMarkedBlock = true;
                markedBlockActive = variables.isEmpty()
                        || activeByLine[line];
            }
            if (insideMarkedBlock && !markedBlockActive) {
                blank(text, localStart, localEnd);
                if (closesMarkedBlock) insideMarkedBlock = false;
                continue;
            }

            PreprocessorLanguage.ParsedDirective directive =
                    PreprocessorLanguage.parseDirective(lineText);
            if (directive != null) {
                if (directive.name().equals("import")) {
                    if (!variables.isEmpty()
                            && !activeByLine[line]) {
                        blank(text, localStart, localEnd);
                    } else {
                        // Keep active //#import directives as real Java imports while
                        // blanking only the preprocessor marker; this preserves all offsets.
                        stripLineMarker(text, localStart, localEnd, lineText, bodyStart, absoluteStart);
                        int marker = lineText.indexOf("//#");
                        if (marker >= 0) {
                            int start = absoluteStart + marker - bodyStart;
                            blank(text, start, start + 3);
                        }
                        if (!directive.argument().endsWith(";")) {
                            int statementEnd = lineText.stripTrailing().length();
                            if (statementEnd > 0) {
                                importSemicolonOffsets.add(absoluteStart + statementEnd - bodyStart);
                            }
                        }
                    }
                    continue;
                }
                if (directive.name().equals("replace")) {
                    int marker = lineText.indexOf("//#replace");
                    int question = directive.argument().indexOf('?');
                    if (marker >= 0 && question >= 0
                            && (variables.isEmpty()
                            || activeByLine[line])) {
                        int conditionStart = marker + "//#replace".length();
                        while (conditionStart < lineText.length()
                                && Character.isWhitespace(lineText.charAt(conditionStart))) conditionStart++;
                        int codeStart = conditionStart + question + 1;
                        blank(text, localStart, Math.min(localEnd,
                                absoluteStart + codeStart - bodyStart));
                        stripLineMarker(text, localStart, localEnd, lineText, bodyStart, absoluteStart);
                        continue;
                    }
                }
                blank(text, localStart, localEnd);
                continue;
            }

            // An unmarked import inside //#if is still branch-specific source.
            // Keep only imports active for the selected version so the virtual
            // Java file sees the same import set as preprocessing would.
            if (!variables.isEmpty() && isJavaImportLine(lineText)
                    && !activeByLine[line]) {
                blank(text, localStart, localEnd);
                continue;
            }

            boolean preprocessorCode = lineText.stripLeading().startsWith("//$$")
                    || lineText.stripLeading().startsWith("//?")
                    || lineText.contains("/*$$");
            if (!variables.isEmpty() && preprocessorCode
                    && !activeByLine[line]) {
                blank(text, localStart, localEnd);
                continue;
            }

            int caseBody = caseBodyStart(lineText);
            if (caseBody >= 0) {
                blank(text, localStart,
                        Math.min(localEnd, absoluteStart + caseBody - bodyStart));
            } else if (lineText.stripLeading().startsWith("//?")) {
                blank(text, localStart, localEnd);
                continue;
            }
            stripLineMarker(text, localStart, localEnd, lineText, bodyStart, absoluteStart);
            if (closesMarkedBlock) insideMarkedBlock = false;
        }

        String view = new String(text);
        view = maskToken(view, "/*$$");
        view = maskToken(view, "$$*/");
        if (importSemicolonOffsets.isEmpty()) {
            return new JavaFileView(view, List.of(), -1, 0);
        }

        StringBuilder withSemicolons = new StringBuilder(view);
        for (int index = importSemicolonOffsets.size() - 1; index >= 0; index--) {
            withSemicolons.insert(importSemicolonOffsets.get(index).intValue(), ';');
        }
        return new JavaFileView(withSemicolons.toString(), List.copyOf(importSemicolonOffsets), -1, 0);
    }

    private static boolean isJavaImportLine(String line) {
        int cursor = 0;
        while (cursor < line.length()) {
            while (cursor < line.length() && Character.isWhitespace(line.charAt(cursor))) cursor++;
            if (!line.startsWith("//$$", cursor)) break;
            cursor += 4;
        }
        while (cursor < line.length() && Character.isWhitespace(line.charAt(cursor))) cursor++;
        return line.startsWith("import", cursor)
                && (cursor + 6 == line.length() || Character.isWhitespace(line.charAt(cursor + 6)));
    }

    private static int caseBodyStart(String line) {
        int marker = line.indexOf("//?");
        if (marker < 0 || !line.substring(0, marker).isBlank()) return -1;
        int cursor = marker + 3;
        while (cursor < line.length() && Character.isWhitespace(line.charAt(cursor))) cursor++;
        if (line.startsWith("else", cursor)) {
            int end = cursor + 4;
            if (end < line.length() && Character.isWhitespace(line.charAt(end))) {
                while (end < line.length() && Character.isWhitespace(line.charAt(end))) end++;
                return end;
            }
            return -1;
        }
        int separator = line.indexOf('?', cursor);
        return separator < 0 ? -1 : separator + 1;
    }

    private static void stripLineMarker(char[] text, int localStart, int localEnd,
                                        String line, int bodyStart, int absoluteStart) {
        int cursor = 0;
        while (cursor < line.length()) {
            while (cursor < line.length() && Character.isWhitespace(line.charAt(cursor))) cursor++;
            if (!line.startsWith("//$$", cursor)) return;
            int start = absoluteStart + cursor - bodyStart;
            blank(text, Math.max(localStart, start), Math.min(localEnd, start + 4));
            cursor += 4;
        }
    }

    private static void blank(char[] text, int start, int end) {
        for (int index = Math.max(0, start); index < Math.min(text.length, end); index++) {
            text[index] = ' ';
        }
    }

    private static String maskToken(String text, String token) {
        char[] chars = text.toCharArray();
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            blank(chars, offset, offset + token.length());
            offset += token.length();
        }
        return new String(chars);
    }

    private static String identifierPrefix(String text, int offset) {
        int end = Math.max(0, Math.min(offset, text.length()));
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        return text.substring(start, end);
    }

    private record JavaFileView(String text, List<Integer> importSemicolonOffsets,
                                int contextImportsInsertionOffset, int contextImportsLength) {
        private int mapOffset(int hostOffset) {
            int mapped = hostOffset;
            for (int insertionOffset : importSemicolonOffsets) {
                if (insertionOffset > hostOffset) break;
                mapped++;
            }
            if (contextImportsInsertionOffset >= 0 && hostOffset >= contextImportsInsertionOffset) {
                mapped += contextImportsLength;
            }
            return mapped;
        }
    }

    private record LookupKey(String lookupString, Object identity, Class<?> elementClass) {}
    private record VariableKey(Class<?> variableClass, String name, String typeText) {}
}
