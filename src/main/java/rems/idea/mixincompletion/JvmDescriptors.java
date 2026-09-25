package rems.idea.mixincompletion;

import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.Nullable;

final class JvmDescriptors {
    private JvmDescriptors() {}

    static @Nullable String methodDescriptor(PsiMethod method) {
        StringBuilder result = new StringBuilder("(");
        for (var parameter : method.getParameterList().getParameters()) {
            String descriptor = typeDescriptor(parameter.getType());
            if (descriptor == null) return null;
            result.append(descriptor);
        }
        result.append(')');
        if (method.isConstructor()) {
            return result.append('V').toString();
        }
        String returnDescriptor = typeDescriptor(method.getReturnType());
        return returnDescriptor == null ? null : result.append(returnDescriptor).toString();
    }

    static @Nullable String owner(PsiClass psiClass) {
        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName == null) return null;

        PsiClass containingClass = psiClass.getContainingClass();
        if (containingClass == null) return qualifiedName.replace('.', '/');

        String outer = owner(containingClass);
        return outer == null ? null : outer + '$' + psiClass.getName();
    }

    private static @Nullable String typeDescriptor(@Nullable PsiType type) {
        if (type == null) return null;
        if (type instanceof PsiPrimitiveType primitive) {
            return switch (primitive.getCanonicalText()) {
                case "void" -> "V";
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "char" -> "C";
                case "short" -> "S";
                case "int" -> "I";
                case "long" -> "J";
                case "float" -> "F";
                case "double" -> "D";
                default -> null;
            };
        }
        if (type instanceof PsiArrayType arrayType) {
            String component = typeDescriptor(arrayType.getComponentType());
            return component == null ? null : '[' + component;
        }
        if (type instanceof PsiClassType classType) {
            PsiClass psiClass = classType.resolve();
            String owner = psiClass == null ? null : owner(psiClass);
            return owner == null ? null : 'L' + owner + ';';
        }
        return null;
    }
}
