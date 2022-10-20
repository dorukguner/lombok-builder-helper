package com.dguner.lombokbuilderhelper;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.tree.java.PsiIdentifierImpl;
import com.intellij.psi.impl.source.tree.java.PsiMethodCallExpressionImpl;
import com.intellij.psi.impl.source.tree.java.PsiReferenceExpressionImpl;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;

public class LombokBuilderInspection extends AbstractBaseJavaLocalInspectionTool {
    public static final String QUICK_FIX_NAME = "Add all mandatory fields";
    private static final Logger LOG =
            Logger.getInstance("#com.dguner.lombokbuilderhelper.LombokBuilderInspection");
    private final LbiQuickFix myQuickFix = new LbiQuickFix();

    private List<String> processMissingFields(PsiElement expression, List<String> mandatoryFields) {
        Queue<PsiElement> queue = new LinkedList<>();
        Set<PsiElement> seenElements = new HashSet<>();
        queue.offer(expression);

        while (!queue.isEmpty()) {
            PsiElement cur = queue.poll();
            seenElements.add(cur);
            if (cur instanceof PsiIdentifierImpl) {
                mandatoryFields.remove(cur.getText());
            }

            if (cur instanceof PsiMethodCallExpressionImpl) {
                // If we are calling build on an element that was a result of a toBuilder call we assume
                // that the builder already has all mandatory fields set
                PsiMethod resolvedMethod = ((PsiMethodCallExpressionImpl) cur).resolveMethod();
                if (resolvedMethod != null && Objects.equals(resolvedMethod.getClass().getName(),
                        "de.plushnikov.intellij.plugin.psi.LombokLightMethodBuilder")
                        && Objects.equals(resolvedMethod.getName(), "toBuilder")) {
                    mandatoryFields.clear();
                    break;
                }
            }

            if (cur instanceof PsiReferenceExpressionImpl) {
                PsiElement resolvedElement = ((PsiReferenceExpressionImpl) cur).resolve();
                if (resolvedElement instanceof PsiLocalVariable) {
                    PsiElement initializer = ((PsiLocalVariable) resolvedElement).getInitializer();
                    if (!seenElements.contains(initializer)) {
                        queue.offer(initializer);
                    }

                    Arrays.stream(ReferencesSearch.search(resolvedElement,
                                    GlobalSearchScope.fileScope(resolvedElement.getContainingFile()), false)
                            .toArray(PsiReference.EMPTY_ARRAY)).forEach(reference -> {
                        PsiElement referenceParent = reference.getElement().getParent();
                        if (!seenElements.contains(referenceParent)) {
                            queue.offer(referenceParent);
                        }
                    });
                }
            }

            for (PsiElement child : cur.getChildren()) {
                queue.offer(child);
            }
        }

        return mandatoryFields;
    }

    private PsiClass getContainingBuilderClass(PsiMethod element) {
        PsiClass aClass = element.getContainingClass();
        while (aClass != null && !isClassBuilder(aClass)) {
            aClass = aClass.getContainingClass();
        }

        return aClass;
    }

    private boolean isClassBuilder(PsiClass aClass) {
        final Set<String> builderClassQualifiedNames = Set.of("lombok.Builder", "lombok.experimental.SuperBuilder");
        return Arrays.stream(aClass.getAnnotations())
                .anyMatch(annotation -> builderClassQualifiedNames.contains(annotation.getQualifiedName()));
    }

    private List<String> getMandatoryFields(PsiClass aClass) {
        final Set<String> nonNullAnnotations = Set.of("lombok.NonNull", "org.jetbrains.annotations.NotNull");
        final String defaultBuilderValueAnnotation = "lombok.Builder.Default";
        return Arrays.stream(aClass.getAllFields())
                .filter(field -> {
                    final PsiAnnotation[] annotations = field.getAnnotations();
                    final PsiModifierList modifiers = field.getModifierList();
                    final boolean isStaticField = modifiers != null && modifiers.hasModifierProperty(PsiModifier.STATIC);
                    return !isStaticField
                            && Arrays.stream(annotations).anyMatch(annotation -> nonNullAnnotations.contains(annotation.getQualifiedName()))
                            && Arrays.stream(annotations).noneMatch(annotation ->
                                Objects.equals(annotation.getQualifiedName(), defaultBuilderValueAnnotation));
                })
                .map(PsiField::getName)
                .collect(Collectors.toList());
    }

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder,
            boolean isOnTheFly) {
        return new JavaElementVisitor() {
            private final String DESCRIPTION_TEMPLATE = "Builder is missing @NonNull fields";

            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiMethod resolvedMethod = expression.resolveMethod();

                if (resolvedMethod != null && Objects.equals(resolvedMethod.getClass().getName(),
                        "de.plushnikov.intellij.plugin.psi.LombokLightMethodBuilder")
                        && Objects.equals(resolvedMethod.getName(), "build")) {
                    PsiClass builderClass = getContainingBuilderClass(resolvedMethod);
                    if (builderClass != null && processMissingFields(expression,
                            getMandatoryFields(builderClass)).size() > 0) {
                        holder.registerProblem(expression, DESCRIPTION_TEMPLATE,
                                ProblemHighlightType.GENERIC_ERROR, myQuickFix);
                    }
                }
            }
        };
    }

    private class LbiQuickFix implements LocalQuickFix {
        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiMethodCallExpression expression =
                    (PsiMethodCallExpression) descriptor.getPsiElement();
            PsiMethod resolvedMethod = expression.resolveMethod();

            if (resolvedMethod != null) {
                List<String> missingFields = processMissingFields(expression,
                        getMandatoryFields(getContainingBuilderClass(resolvedMethod)));

                String errorText = expression.getText();

                PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
                PsiMethodCallExpression fixedMethodExpression =
                        (PsiMethodCallExpression) factory.createExpressionFromText(
                                errorText.replace(".build()",
                                        "." + String.join("().", missingFields) + "().build()"),
                                null);

                expression.replace(fixedMethodExpression);
            } else {
                LOG.error("Resolved method null when applying fix");
            }
        }

        @NotNull
        public String getFamilyName() {
            return QUICK_FIX_NAME;
        }
    }
}
