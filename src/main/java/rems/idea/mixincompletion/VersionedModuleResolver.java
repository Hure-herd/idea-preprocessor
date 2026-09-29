package rems.idea.mixincompletion;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.Map;

final class VersionedModuleResolver {
    private static final Logger LOG = Logger.getInstance(VersionedModuleResolver.class);
    private VersionedModuleResolver() {}

    static PsiClass retarget(PsiClass currentTarget, Project project, Document document, int currentLine) {
        String qualifiedName = currentTarget.getQualifiedName();
        Module selectedModule = resolveModule(project, document, currentLine);
        if (qualifiedName == null || selectedModule == null) return currentTarget;

        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(
                selectedModule, false);
        PsiClass[] matches = facade.findClasses(qualifiedName, scope);
        if (matches.length > 0) {
            LOG.info("//$$ completion module: " + selectedModule.getName());
            return matches[0];
        }
        return currentTarget;
    }

    static Module resolveModule(Project project, Document document, int currentLine) {
        if (!PreprocessorLanguage.hasVersionContext(document, currentLine)) return null;

        List<String> lines = new ArrayList<>(document.getLineCount());
        CharSequence source = document.getCharsSequence();
        for (int line = 0; line < document.getLineCount(); line++) {
            lines.add(source.subSequence(document.getLineStartOffset(line),
                    document.getLineEndOffset(line)).toString());
        }
        Map<String, String> definitions = PreprocessorLanguage.collectDefinitions(lines);
        List<VersionedModule> candidates = new ArrayList<>();
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            int code = PreprocessorLanguage.moduleVersionCode(module.getName());
            if (code >= 0 && module.getName().endsWith(".main")
                    && PreprocessorLanguage.isLineActive(lines, currentLine,
                    Map.of("MC", code), definitions)) {
                candidates.add(new VersionedModule(code, module));
            }
        }
        if (candidates.isEmpty()) return null;

        Comparator<VersionedModule> comparator = Comparator.comparingInt(VersionedModule::code);
        candidates.sort(comparator);

        return candidates.get(0).module();
    }

    private record VersionedModule(int code, Module module) {}
}
