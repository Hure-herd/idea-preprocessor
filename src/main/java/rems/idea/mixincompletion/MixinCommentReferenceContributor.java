package rems.idea.mixincompletion;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MixinCommentReferenceContributor extends PsiReferenceContributor {
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "\\b(method|target)\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern CLASS_MEMBER = Pattern.compile(
            "\\b([A-Z][A-Za-z0-9_$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)\\b");
    private static final Pattern AT_VALUE = Pattern.compile(
            "@At\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern SHADOW_ANNOTATION = Pattern.compile("@Shadow\\b");
    private static final Pattern ANNOTATION = Pattern.compile("@([A-Z][A-Za-z0-9_$]*)\\b");
    private static final Pattern ANNOTATION_ATTRIBUTE = Pattern.compile(
            "\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*=");
    private static final Pattern CLASS_NAME = Pattern.compile("\\b([A-Z][A-Za-z0-9_$]*)\\b");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b([a-z_$][A-Za-z0-9_$]*)\\b");
    private static final Pattern INSTANCE_MEMBER = Pattern.compile(
            "\\b([a-z_$][A-Za-z0-9_$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)\\b");
    private static final Pattern SHADOW_METHOD = Pattern.compile(
            "\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\([^;{}]*\\)\\s*;");
    private static final Pattern SHADOW_FIELD = Pattern.compile(
            "\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:=[^;]*)?;");
    private static final Pattern DECLARATION_NAME = Pattern.compile(
            "\\b(?:(?:public|protected|private|abstract|final|static|synchronized|native|strictfp|transient|volatile)\\s+)*"
                    + "[A-Za-z_$][A-Za-z0-9_$.<>?, \\[\\]]*\\s+"
                    + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*(=|;|\\()");
    private static final Pattern PREPROCESSOR_IMPORT = Pattern.compile(
            "^\\s*(?://\\$\\$\\s*)*//#import\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*;?\\s*$");
    @Override
    public void registerReferenceProviders(@NotNull com.intellij.psi.PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(PsiComment.class),
                new PsiReferenceProvider() {
                    @Override
                    public PsiReference @NotNull [] getReferencesByElement(
                            @NotNull PsiElement element, @NotNull ProcessingContext context) {
                        PsiComment comment = (PsiComment) element;
                        Matcher importMatcher = PREPROCESSOR_IMPORT.matcher(comment.getText());
                        if (importMatcher.matches()) {
                            return new PsiReference[]{new ImportClassReference(comment,
                                    TextRange.create(importMatcher.start(1), importMatcher.end(1)),
                                    importMatcher.group(1))};
                        }
                        String leading = comment.getText().stripLeading();
                        if (!leading.startsWith("//$$") && !leading.startsWith("/*$$")
                                && !leading.startsWith("//?")) {
                            return PsiReference.EMPTY_ARRAY;
                        }

                        List<PsiReference> references = new ArrayList<>();
                        Matcher declarationMatcher = DECLARATION_NAME.matcher(comment.getText());
                        TextRange declarationRange = null;
                        if (declarationMatcher.find()) {
                            declarationRange = TextRange.create(
                                    declarationMatcher.start(1), declarationMatcher.end(1));
                            references.add(new VersionDeclarationReference(
                                    comment, declarationRange, declarationMatcher.group(1),
                                    "(".equals(declarationMatcher.group(2))));
                        }
                        Matcher matcher = ATTRIBUTE.matcher(comment.getText());
                        while (matcher.find()) {
                            references.add(new CommentReference(
                                    comment,
                                    TextRange.create(matcher.start(2), matcher.end(2)),
                                    matcher.group(1),
                                    matcher.group(2)));
                        }
                        Matcher memberMatcher = CLASS_MEMBER.matcher(comment.getText());
                        while (memberMatcher.find()) {
                            references.add(new MemberReference(
                                    comment,
                                    TextRange.create(memberMatcher.start(2), memberMatcher.end(2)),
                                    memberMatcher.group(1),
                                    memberMatcher.group(2)));
                        }
                        Matcher atMatcher = AT_VALUE.matcher(comment.getText());
                        while (atMatcher.find()) {
                            references.add(new AtValueReference(
                                    comment,
                                    TextRange.create(atMatcher.start(1), atMatcher.end(1)),
                                    atMatcher.group(1)));
                        }
                        Matcher shadowAnnotation = SHADOW_ANNOTATION.matcher(comment.getText());
                        while (shadowAnnotation.find()) {
                            references.add(new ClassReference(
                                    comment,
                                    TextRange.create(shadowAnnotation.start() + 1, shadowAnnotation.end()),
                                    "org.spongepowered.asm.mixin.Shadow"));
                        }
                        Matcher annotationMatcher = ANNOTATION.matcher(comment.getText());
                        while (annotationMatcher.find()) {
                            String name = annotationMatcher.group(1);
                            if ("Shadow".equals(name)) continue;
                            references.add(new AnnotationReference(
                                    comment,
                                    TextRange.create(annotationMatcher.start(1), annotationMatcher.end(1)),
                                    name));
                        }
                        Matcher attributeNameMatcher = ANNOTATION_ATTRIBUTE.matcher(comment.getText());
                        while (attributeNameMatcher.find()) {
                            String annotationName = enclosingAnnotation(
                                    comment, attributeNameMatcher.end(1));
                            if (annotationName == null) continue;
                            references.add(new AnnotationAttributeReference(
                                    comment,
                                    TextRange.create(attributeNameMatcher.start(1),
                                            attributeNameMatcher.end(1)),
                                    annotationName, attributeNameMatcher.group(1)));
                        }
                        Matcher classMatcher = CLASS_NAME.matcher(comment.getText());
                        while (classMatcher.find()) {
                            int start = classMatcher.start(1);
                            if (start > 0 && comment.getText().charAt(start - 1) == '@') continue;
                            references.add(new ClassNameReference(
                                    comment, TextRange.create(start, classMatcher.end(1)),
                                    classMatcher.group(1)));
                        }
                        Matcher identifierMatcher = IDENTIFIER.matcher(comment.getText());
                        while (identifierMatcher.find()) {
                            String name = identifierMatcher.group(1);
                            if (declarationRange != null
                                    && declarationRange.getStartOffset() == identifierMatcher.start(1)
                                    && declarationRange.getEndOffset() == identifierMatcher.end(1)) {
                                continue;
                            }
                            if (isJavaWord(name) || isAnnotationAttributeAt(
                                    comment.getText(), identifierMatcher.start(1), identifierMatcher.end(1))) {
                                continue;
                            }
                            PsiComment declaration = findVariableDeclaration(comment, name);
                            if (declaration != null) {
                                references.add(new VariableReference(
                                        comment,
                                        TextRange.create(identifierMatcher.start(1),
                                                identifierMatcher.end(1)),
                                        declaration));
                            }
                        }
                        Matcher instanceMemberMatcher = INSTANCE_MEMBER.matcher(comment.getText());
                        while (instanceMemberMatcher.find()) {
                            references.add(new InstanceMemberReference(
                                    comment,
                                    TextRange.create(instanceMemberMatcher.start(2),
                                            instanceMemberMatcher.end(2)),
                                    instanceMemberMatcher.group(1), instanceMemberMatcher.group(2)));
                        }
                        if (isShadowDeclaration(comment)) {
                            Matcher shadowMethod = SHADOW_METHOD.matcher(comment.getText());
                            if (shadowMethod.find() && !"Shadow".equals(shadowMethod.group(1))) {
                                references.add(new ShadowMemberReference(
                                        comment,
                                        TextRange.create(shadowMethod.start(1), shadowMethod.end(1)),
                                        shadowMethod.group(1), true));
                            } else {
                                Matcher shadowField = SHADOW_FIELD.matcher(comment.getText());
                                if (shadowField.find()) {
                                    references.add(new ShadowMemberReference(
                                            comment,
                                            TextRange.create(shadowField.start(1), shadowField.end(1)),
                                            shadowField.group(1), false));
                                }
                            }
                        }
                        return references.toArray(PsiReference.EMPTY_ARRAY);
                    }
                });
    }

    private static final class VersionDeclarationReference
            extends PsiPolyVariantReferenceBase<PsiComment> {
        private final String memberName;
        private final boolean method;

        private VersionDeclarationReference(PsiComment element, TextRange range,
                                            String memberName, boolean method) {
            super(element, range, true);
            this.memberName = memberName;
            this.method = method;
        }

        @Override
        public com.intellij.psi.ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
            PsiElement target = resolveVersionDeclaration(getElement(), memberName, method);
            if (target == null) return com.intellij.psi.ResolveResult.EMPTY_ARRAY;
            Resolution resolution = resolution(getElement());
            if (resolution == null || resolution.module() == null) {
                return new com.intellij.psi.ResolveResult[]{new PsiElementResolveResult(target)};
            }
            List<PsiElement> all = new ArrayList<>();
            List<PsiElement> source = new ArrayList<>();
            for (PsiReference reference : ReferencesSearch.search(
                    target, GlobalSearchScope.moduleScope(resolution.module())).findAll()) {
                PsiElement element = reference.getElement();
                if (!element.isValid() || element instanceof PsiComment) continue;
                all.add(element);
                PsiFile usageFile = element.getContainingFile();
                String path = usageFile == null || usageFile.getVirtualFile() == null
                        ? "" : usageFile.getVirtualFile().getPath().replace('\\', '/');
                if (!path.contains("/build/preprocessed/")) source.add(element);
            }
            List<PsiElement> preferred = source.isEmpty() ? all : source;
            if (preferred.isEmpty()) preferred = List.of(target);
            java.util.LinkedHashMap<String, PsiElement> unique = new java.util.LinkedHashMap<>();
            for (PsiElement element : preferred) {
                PsiFile usageFile = element.getContainingFile();
                String path = usageFile == null || usageFile.getVirtualFile() == null
                        ? "" : usageFile.getVirtualFile().getPath();
                unique.putIfAbsent(path + ':' + element.getTextOffset(), element);
            }
            return unique.values().stream()
                    .map(PsiElementResolveResult::new)
                    .toArray(com.intellij.psi.ResolveResult[]::new);
        }
    }

    static @Nullable PsiElement resolveVersionDeclaration(
            PsiComment comment, String memberName, boolean method) {
        Resolution resolution = resolution(comment);
        if (resolution == null || resolution.module() == null) return null;
        PsiClass containingClass = PsiTreeUtil.getParentOfType(comment, PsiClass.class, false);
        if (containingClass == null || containingClass.getName() == null) return null;
        PsiClass selectedClass = MixinDescriptorCompletionContributor.findVersionSourceClass(
                containingClass, resolution.module(), containingClass.getName());
        if (selectedClass == null) return null;
        if (!method) return selectedClass.findFieldByName(memberName, false);
        PsiMethod[] methods = selectedClass.findMethodsByName(memberName, false);
        return methods.length == 0 ? null : methods[0];
    }

    private static final class ClassNameReference extends PsiReferenceBase<PsiComment> {
        private final String className;

        private ClassNameReference(PsiComment element, TextRange range, String className) {
            super(element, range, true);
            this.className = className;
        }

        @Override
        public @Nullable PsiElement resolve() {
            Resolution resolution = resolution(getElement());
            if (resolution == null) return null;
            PsiClass sourceClass = MixinDescriptorCompletionContributor.findVersionSourceClass(
                    resolution.target(), resolution.module(), className);
            if (sourceClass != null) return sourceClass;
            PsiClass[] classes = PsiShortNamesCache.getInstance(getElement().getProject())
                    .getClassesByName(className, resolution.scope());
            if (classes.length > 0) return classes[0];
            return resolveStaticImportedField(getElement(), className, resolution);
        }
    }

    private static final class AnnotationAttributeReference extends PsiReferenceBase<PsiComment> {
        private final String annotationName;
        private final String attributeName;

        private AnnotationAttributeReference(PsiComment element, TextRange range,
                                             String annotationName, String attributeName) {
            super(element, range, true);
            this.annotationName = annotationName;
            this.attributeName = attributeName;
        }

        @Override
        public @Nullable PsiElement resolve() {
            PsiClass annotationClass = resolveAnnotationClass(getElement(), annotationName);
            if (annotationClass == null) return null;
            PsiMethod[] methods = annotationClass.findMethodsByName(attributeName, false);
            return methods.length == 0 ? null : methods[0];
        }
    }

    private static final class VariableReference extends PsiReferenceBase<PsiComment> {
        private final PsiComment declaration;

        private VariableReference(PsiComment element, TextRange range, PsiComment declaration) {
            super(element, range, true);
            this.declaration = declaration;
        }

        @Override
        public PsiElement resolve() {
            return declaration.isValid() ? declaration : null;
        }
    }

    private static final class InstanceMemberReference extends PsiReferenceBase<PsiComment> {
        private final String qualifier;
        private final String memberName;

        private InstanceMemberReference(PsiComment element, TextRange range,
                                        String qualifier, String memberName) {
            super(element, range, true);
            this.qualifier = qualifier;
            this.memberName = memberName;
        }

        @Override
        public @Nullable PsiElement resolve() {
            Resolution resolution = resolution(getElement());
            if (resolution == null) return null;
            Document document = PsiDocumentManager.getInstance(getElement().getProject())
                    .getDocument(getElement().getContainingFile());
            if (document == null) return null;
            int line = document.getLineNumber(getElement().getTextOffset());
            PsiClass type = MixinDescriptorCompletionContributor.resolveQualifierType(
                    resolution.target(), resolution.module(), document, line,
                    qualifier, resolution.scope());
            if (type == null) return null;
            PsiField field = type.findFieldByName(memberName, true);
            if (field != null) return field;
            PsiMethod[] methods = type.findMethodsByName(memberName, true);
            return methods.length == 0 ? null : methods[0];
        }
    }

    private static final class CommentReference extends PsiReferenceBase<PsiComment> {
        private final String attribute;
        private final String value;

        private CommentReference(PsiComment element, TextRange range, String attribute, String value) {
            super(element, range, true);
            this.attribute = attribute;
            this.value = value;
        }

        @Override
        public @Nullable PsiElement resolve() {
            PsiClass versionTarget = resolveVersionTarget(getElement());
            if (versionTarget == null) return null;

            if ("method".equals(attribute)) {
                return findMethod(versionTarget, value, false);
            }
            return resolveAtTarget(versionTarget, value);
        }

        private @Nullable PsiMethod resolveAtTarget(PsiClass versionTarget, String target) {
            if (!target.startsWith("L")) return null;
            int ownerEnd = target.indexOf(';');
            int descriptorStart = target.indexOf('(', ownerEnd + 1);
            if (ownerEnd < 2 || descriptorStart < 0) return null;

            String ownerName = target.substring(1, ownerEnd)
                    .replace('/', '.')
                    .replace('$', '.');
            String selector = target.substring(ownerEnd + 1);
            Resolution resolution = resolution(getElement());
            GlobalSearchScope scope = resolution == null
                    ? versionTarget.getResolveScope() : resolution.scope();
            PsiClass owner = JavaPsiFacade.getInstance(getElement().getProject())
                    .findClass(ownerName, scope);
            return owner == null ? null : findMethod(owner, selector, true);
        }

        private static @Nullable PsiMethod findMethod(PsiClass owner, String selector, boolean includeBases) {
            int descriptorStart = selector.indexOf('(');
            String name = descriptorStart < 0 ? selector : selector.substring(0, descriptorStart);
            String descriptor = descriptorStart < 0 ? null : selector.substring(descriptorStart);
            for (PsiMethod method : owner.findMethodsByName(name, includeBases)) {
                if (descriptor == null || descriptor.equals(JvmDescriptors.methodDescriptor(method))) {
                    return method;
                }
            }
            return null;
        }
    }

    private static final class MemberReference extends PsiReferenceBase<PsiComment> {
        private final String className;
        private final String memberName;

        private MemberReference(PsiComment element, TextRange range,
                                String className, String memberName) {
            super(element, range, true);
            this.className = className;
            this.memberName = memberName;
        }

        @Override
        public @Nullable PsiElement resolve() {
            Resolution resolution = resolution(getElement());
            if (resolution == null) return null;

            PsiClass selectedClass = MixinDescriptorCompletionContributor.findVersionSourceClass(
                    resolution.target(), resolution.module(), className);
            PsiClass[] classes = PsiShortNamesCache.getInstance(getElement().getProject())
                    .getClassesByName(className, resolution.scope());
            for (PsiClass candidate : classes) {
                if (resolution.module() != null
                        && resolution.module().equals(ModuleUtilCore.findModuleForPsiElement(candidate))) {
                    selectedClass = candidate;
                    break;
                }
                if (selectedClass == null) selectedClass = candidate;
            }
            if (selectedClass == null) return null;

            PsiField field = selectedClass.findFieldByName(memberName, true);
            if (field != null) return field;
            PsiMethod[] methods = selectedClass.findMethodsByName(memberName, true);
            return methods.length == 0 ? null : methods[0];
        }
    }

    private static final class AtValueReference extends PsiReferenceBase<PsiComment> {
        private final String code;

        private AtValueReference(PsiComment element, TextRange range, String code) {
            super(element, range, true);
            this.code = code;
        }

        @Override
        public @Nullable PsiElement resolve() {
            Resolution resolution = resolution(getElement());
            if (resolution == null) return null;
            if (code.indexOf('.') > 0) {
                return JavaPsiFacade.getInstance(getElement().getProject())
                        .findClass(code, resolution.scope());
            }
            return MixinMetadataResolver.findInjectionPoint(
                    getElement().getProject(), resolution.scope(), code);
        }
    }

    private static final class AnnotationReference extends PsiReferenceBase<PsiComment> {
        private final String shortName;

        private AnnotationReference(PsiComment element, TextRange range, String shortName) {
            super(element, range, true);
            this.shortName = shortName;
        }

        @Override
        public @Nullable PsiElement resolve() {
            return resolveAnnotationClass(getElement(), shortName);
        }
    }

    private static final class ClassReference extends PsiReferenceBase<PsiComment> {
        private final String qualifiedName;

        private ClassReference(PsiComment element, TextRange range, String qualifiedName) {
            super(element, range, true);
            this.qualifiedName = qualifiedName;
        }

        @Override
        public @Nullable PsiElement resolve() {
            Resolution resolution = resolution(getElement());
            if (resolution == null) return null;
            return JavaPsiFacade.getInstance(getElement().getProject())
                    .findClass(qualifiedName, resolution.scope());
        }
    }

    private static final class ImportClassReference extends PsiReferenceBase<PsiComment> {
        private final String qualifiedName;

        private ImportClassReference(PsiComment element, TextRange range, String qualifiedName) {
            super(element, range, true);
            this.qualifiedName = qualifiedName;
        }

        @Override
        public @Nullable PsiElement resolve() {
            Document document = PsiDocumentManager.getInstance(getElement().getProject())
                    .getDocument(getElement().getContainingFile());
            if (document == null) return null;
            int line = document.getLineNumber(getElement().getTextOffset());
            Module module = VersionedModuleResolver.resolveModule(
                    getElement().getProject(), document, line);
            GlobalSearchScope scope = module == null
                    ? GlobalSearchScope.allScope(getElement().getProject())
                    : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false);
            return JavaPsiFacade.getInstance(getElement().getProject()).findClass(qualifiedName, scope);
        }
    }

    private static final class ShadowMemberReference extends PsiReferenceBase<PsiComment> {
        private final String memberName;
        private final boolean method;

        private ShadowMemberReference(PsiComment element, TextRange range,
                                      String memberName, boolean method) {
            super(element, range, true);
            this.memberName = memberName;
            this.method = method;
        }

        @Override
        public @Nullable PsiElement resolve() {
            PsiClass target = resolveVersionTarget(getElement());
            if (target == null) return null;
            if (!method) return target.findFieldByName(memberName, true);
            PsiMethod[] methods = target.findMethodsByName(memberName, true);
            return methods.length == 0 ? null : methods[0];
        }
    }

    private static boolean isShadowDeclaration(PsiComment comment) {
        if (SHADOW_ANNOTATION.matcher(comment.getText()).find()) return true;
        Document document = PsiDocumentManager.getInstance(comment.getProject())
                .getDocument(comment.getContainingFile());
        if (document == null) return false;
        int line = document.getLineNumber(comment.getTextOffset());
        for (int previous = line - 1; previous >= Math.max(0, line - 3); previous--) {
            int start = document.getLineStartOffset(previous);
            int end = document.getLineEndOffset(previous);
            String text = document.getText().substring(start, end);
            if (!text.stripLeading().startsWith("//$$")) return false;
            if (SHADOW_ANNOTATION.matcher(text).find()) return true;
            if (!text.strip().equals("//$$")) return false;
        }
        return false;
    }

    private static String enclosingAnnotation(PsiComment comment, int relativeOffset) {
        Document document = PsiDocumentManager.getInstance(comment.getProject())
                .getDocument(comment.getContainingFile());
        if (document == null) return null;
        String logical = MixinCommentContext.logicalContext(
                document, comment.getTextOffset() + relativeOffset);
        return MixinCommentContext.enclosingAnnotation(logical);
    }

    private static boolean isAnnotationAttributeAt(String text, int start, int end) {
        Matcher matcher = ANNOTATION_ATTRIBUTE.matcher(text);
        while (matcher.find()) {
            if (matcher.start(1) == start && matcher.end(1) == end) return true;
        }
        return false;
    }

    private static boolean isJavaWord(String value) {
        return switch (value) {
            case "if", "else", "for", "while", "return", "new", "this", "super",
                    "true", "false", "null", "private", "protected", "public",
                    "static", "final", "abstract", "void", "boolean", "byte", "char",
                    "short", "int", "long", "float", "double", "class", "interface",
                    "enum", "record", "extends", "implements", "instanceof", "throw",
                    "throws", "try", "catch", "finally", "switch", "case", "default" -> true;
            default -> false;
        };
    }

    private static PsiComment findVariableDeclaration(PsiComment usage, String name) {
        Document document = PsiDocumentManager.getInstance(usage.getProject())
                .getDocument(usage.getContainingFile());
        if (document == null) return null;
        int currentLine = document.getLineNumber(usage.getTextOffset());
        Pattern declaration = Pattern.compile(
                "\\b(?:[A-Z][A-Za-z0-9_$.]*(?:\\s*<[^;=(){}]+>)?(?:\\s*\\[\\])*"
                        + "|boolean|byte|char|short|int|long|float|double|var)\\s+"
                        + Pattern.quote(name) + "\\b");
        for (int line = currentLine; line >= Math.max(0, currentLine - 100); line--) {
            int start = document.getLineStartOffset(line);
            int end = document.getLineEndOffset(line);
            String text = document.getText().substring(start, end);
            if (!text.stripLeading().startsWith("//$$")) break;
            if (!declaration.matcher(text).find()) continue;
            PsiElement element = usage.getContainingFile().findElementAt(
                    Math.min(end - 1, start + Math.max(0, text.indexOf("//$$"))));
            PsiComment comment = PsiTreeUtil.getParentOfType(element, PsiComment.class, false);
            if (comment != null) return comment;
        }
        return null;
    }

    private static PsiClass resolveAnnotationClass(PsiComment comment, String shortName) {
        Resolution resolution = resolution(comment);
        if (resolution == null) return null;
        return MixinMetadataResolver.findAnnotationClass(
                comment, shortName, resolution.scope());
    }

    private static @Nullable PsiField resolveStaticImportedField(
            PsiComment comment, String fieldName, Resolution resolution) {
        if (!(comment.getContainingFile() instanceof PsiJavaFile javaFile)) return null;
        PsiImportList imports = javaFile.getImportList();
        if (imports == null) return null;
        JavaPsiFacade facade = JavaPsiFacade.getInstance(comment.getProject());
        for (PsiImportStaticStatement statement : imports.getImportStaticStatements()) {
            if (!statement.isOnDemand() && !fieldName.equals(statement.getReferenceName())) continue;
            PsiClass importedClass = statement.resolveTargetClass();
            if (importedClass == null) continue;
            String qualifiedName = importedClass.getQualifiedName();
            PsiClass selectedClass = qualifiedName == null
                    ? importedClass : facade.findClass(qualifiedName, resolution.scope());
            if (selectedClass == null) selectedClass = importedClass;
            PsiField field = selectedClass.findFieldByName(fieldName, true);
            if (field != null) return field;
        }
        return null;
    }

    private static Resolution resolution(PsiComment comment) {
        Document document = PsiDocumentManager.getInstance(comment.getProject())
                .getDocument(comment.getContainingFile());
        if (document == null) return null;
        int line = document.getLineNumber(comment.getTextOffset());
        PsiClass target = resolveVersionTarget(comment);
        if (target == null) return null;
        Module module = VersionedModuleResolver.resolveModule(
                comment.getProject(), document, line);
        GlobalSearchScope scope = module == null
                ? target.getResolveScope()
                : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false);
        return new Resolution(target, module, scope);
    }

    private record Resolution(PsiClass target, Module module, GlobalSearchScope scope) {}

    private static @Nullable PsiClass resolveVersionTarget(PsiComment comment) {
        PsiClass currentTarget = MixinDescriptorCompletionContributor.findMixinTarget(comment);
        if (currentTarget == null) {
            currentTarget = PsiTreeUtil.getParentOfType(comment, PsiClass.class, false);
        }
        if (currentTarget == null) return null;
        Document document = PsiDocumentManager.getInstance(comment.getProject())
                .getDocument(comment.getContainingFile());
        if (document == null) return null;
        int line = document.getLineNumber(comment.getTextOffset());
        return VersionedModuleResolver.retarget(
                currentTarget, comment.getProject(), document, line);
    }
}
