package com.dguner.lombokbuilderhelper;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.impl.source.tree.java.PsiIdentifierImpl;
import com.intellij.psi.impl.source.tree.java.PsiReferenceExpressionImpl;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

public class LombokBuilderInspection extends AbstractBaseJavaLocalInspectionTool {
    public static final String QUICK_FIX_NAME = "Add all mandatory fields";
    private static final Logger LOG =
            Logger.getInstance("#com.dguner.lombokbuilderhelper.LombokBuilderInspection");
    private final LbiQuickFix myQuickFix = new LbiQuickFix();

    private List<String> processMissingFields(PsiElement parent, List<String> mandatoryFields) {
        Queue<PsiElement> queue = new LinkedList<>();
        queue.offer(parent);

        while (!queue.isEmpty()) {
            PsiElement cur = queue.poll();
            if (cur instanceof PsiIdentifierImpl) {
                mandatoryFields.remove(cur.getText());
            }

            if (cur instanceof PsiReferenceExpressionImpl) {
                PsiElement resolvedElement = ((PsiReferenceExpressionImpl) cur).resolve();
                if (resolvedElement instanceof PsiLocalVariable) {
                    queue.offer(((PsiLocalVariable) resolvedElement).getInitializer());
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
        return Arrays.stream(aClass.getAnnotations())
                .anyMatch(annotation -> annotation.getQualifiedName() != null
                        && annotation.getQualifiedName().equals("lombok.Builder"));
    }

    private List<String> getMandatoryFields(PsiClass aClass) {
        return Arrays.stream(aClass.getAllFields())
                .filter(field -> Arrays.stream(field.getAnnotations())
                        .anyMatch(annotation -> annotation.getQualifiedName() != null
                                && annotation.getQualifiedName().equals("lombok.NonNull")))
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

                if (resolvedMethod != null && resolvedMethod.toString()
                        .equals("LombokLightMethodBuilder: build")) {
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
