package com.dguner.lombokbuilderhelper;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.impl.source.tree.java.PsiIdentifierImpl;
import com.intellij.psi.impl.source.tree.java.PsiMethodCallExpressionImpl;
import com.intellij.psi.impl.source.tree.java.PsiReferenceExpressionImpl;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.Query;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

public class LombokBuilderInspection extends AbstractBaseJavaLocalInspectionTool {

    public static final String QUICK_FIX_NAME = "Add all mandatory fields";
    private static final String REGISTRY_KEY_TREAT_PRIMITIVES_AS_MANDATORY = "lombok.builder.helper.treat.primitives.as.mandatory";
    private static final String REGISTRY_KEY_TREAT_JPA_NULLABLE_FALSE_AS_MANDATORY = "lombok.builder.helper.treat.jpa.nullable.false.as.mandatory";
    private static final Logger LOG = Logger.getInstance("#com.dguner.lombokbuilderhelper.LombokBuilderInspection");

    private final LbiQuickFix myQuickFix = new LbiQuickFix();

    private List<String> processMissingFields(PsiElement expression, List<String> mandatoryFields) {
        Queue<PsiElement> queue = new LinkedList<>();
        Set<PsiElement> seenElements = new HashSet<>();
        queue.offer(expression);

        while (!queue.isEmpty()) {
            PsiElement cur = queue.poll();
            if (cur == null) {
                continue;
            }
            seenElements.add(cur);
            if (cur instanceof PsiIdentifierImpl) {
                mandatoryFields.remove(cur.getText());
            }

            if (cur instanceof PsiMethodCallExpressionImpl) {
                PsiMethod resolvedMethod = ((PsiMethodCallExpressionImpl) cur).resolveMethod();
                if (resolvedMethod != null) {
                    // If the resolved method is not a lombok method, add the return statement to the queue
                    // to visit its nodes too
                    if (!Objects.equals(
                        resolvedMethod.getClass().getName(),
                        "de.plushnikov.intellij.plugin.psi.LombokLightMethodBuilder"
                    )) {
                        for (PsiReturnStatement returnStatement : PsiUtil.findReturnStatements(resolvedMethod)) {
                            queue.offer(returnStatement.getReturnValue());
                        }
                        // If we are calling build on an element that was a result of a toBuilder call we assume
                        // that the builder already has all mandatory fields set
                    } else if (Objects.equals(resolvedMethod.getName(), "toBuilder")) {
                        mandatoryFields.clear();
                        break;
                    }
                }
            }

            if (cur instanceof PsiReferenceExpressionImpl) {
                PsiElement resolvedElement = ((PsiReferenceExpressionImpl) cur).resolve();
                if (resolvedElement instanceof PsiLocalVariable) {
                    PsiElement initializer = ((PsiLocalVariable) resolvedElement).getInitializer();
                    if (!seenElements.contains(initializer)) {
                        queue.offer(initializer);
                    }

                    final Query<PsiReference> searchResults = ReferencesSearch.search(
                        resolvedElement, GlobalSearchScope.fileScope(resolvedElement.getContainingFile()), false
                    );
                    Arrays.stream(searchResults.toArray(PsiReference.EMPTY_ARRAY)).forEach(reference -> {
                        if (reference.getElement().getTextRange().getStartOffset() < cur.getTextRange()
                                                                                        .getStartOffset()) {
                            PsiElement referenceParent = reference.getElement().getParent();
                            if (!seenElements.contains(referenceParent)) {
                                queue.offer(referenceParent);
                            }
                        }
                    });
                }
            }

            for (PsiElement child : cur.getChildren()) {
                if (!seenElements.contains(child) && (
                    child instanceof PsiIdentifierImpl
                    || child instanceof PsiMethodCallExpressionImpl
                    || child instanceof PsiReferenceExpressionImpl
                )) {
                    queue.offer(child);
                }
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
        final Set<String> builderClassQualifiedNames = Set.of(
            "lombok.Builder",
            "lombok.experimental.SuperBuilder"
        );
        return Arrays.stream(aClass.getAnnotations()).anyMatch(
            annotation -> builderClassQualifiedNames.contains(annotation.getQualifiedName())
        );
    }

    private boolean hasJpaNullableFalse(PsiField field) {
        final Set<String> columnAnnotationNames = Set.of(
            "javax.persistence.Column",
            "jakarta.persistence.Column"
        );
        return Arrays.stream(field.getAnnotations()).filter(
            annotation -> columnAnnotationNames.contains(annotation.getQualifiedName())
        ).anyMatch(annotation -> {
            PsiAnnotationMemberValue nullableValue = annotation.findAttributeValue("nullable");
            // If nullable is explicitly set to false, treat as mandatory
            return nullableValue != null && "false".equals(nullableValue.getText());
        });
    }

    private boolean isHibernateAutoGenerated(PsiField field) {
        // Hibernate and JPA annotations that indicate auto-generated values
        final Set<String> autoGeneratedAnnotations = Set.of(
            // Hibernate annotations
            "org.hibernate.annotations.CreationTimestamp",
            "org.hibernate.annotations.UpdateTimestamp",
            "org.hibernate.annotations.Generated",
            "org.hibernate.annotations.GeneratedValue",
            "org.hibernate.annotations.UuidGenerator",
            "org.hibernate.annotations.GenericGenerator",
            // JPA annotations (both javax and jakarta)
            "javax.persistence.GeneratedValue",
            "jakarta.persistence.GeneratedValue",
            "javax.persistence.Id", // IDs are often auto-generated
            "jakarta.persistence.Id"
        );
        return Arrays.stream(field.getAnnotations()).anyMatch(
            annotation -> autoGeneratedAnnotations.contains(annotation.getQualifiedName())
        );
    }

    private List<String> getMandatoryFields(PsiClass aClass) {
        final Set<String> nonNullAnnotations = Set.of(
            "lombok.NonNull",
            "org.jetbrains.annotations.NotNull",
            "javax.validation.constraints.NotNull",
            "jakarta.validation.constraints.NotNull"
        );
        final String defaultBuilderValueAnnotation = "lombok.Builder.Default";
        final boolean treatPrimitivesAsMandatory = Registry.is(REGISTRY_KEY_TREAT_PRIMITIVES_AS_MANDATORY);
        final boolean treatJpaNullableFalseAsMandatory = Registry.is(REGISTRY_KEY_TREAT_JPA_NULLABLE_FALSE_AS_MANDATORY);

        return Arrays.stream(aClass.getAllFields()).filter(field -> {
            final PsiAnnotation[] annotations = field.getAnnotations();
            final PsiModifierList modifiers = field.getModifierList();
            final boolean isPrimitiveType = field.getType() instanceof PsiPrimitiveType;
            final boolean isStaticField = modifiers != null && modifiers.hasModifierProperty(PsiModifier.STATIC);

            // Exclude auto-generated fields (timestamps, UUIDs, IDs, etc.)
            if (isHibernateAutoGenerated(field)) {
                return false;
            }

            final boolean hasNonNullAnnotation = Arrays.stream(annotations).anyMatch(
                annotation -> nonNullAnnotations.contains(annotation.getQualifiedName())
            );
            final boolean hasJpaNotNull = treatJpaNullableFalseAsMandatory && hasJpaNullableFalse(field);
            final boolean isMandatory = (treatPrimitivesAsMandatory && isPrimitiveType) || hasNonNullAnnotation || hasJpaNotNull;
            final boolean hasDefaultValue = Arrays.stream(annotations).anyMatch(
                annotation -> Objects.equals(annotation.getQualifiedName(), defaultBuilderValueAnnotation)
            );
            return !isStaticField && isMandatory && !hasDefaultValue;
        }).map(PsiField::getName).collect(Collectors.toList());
    }

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(
        @NotNull final ProblemsHolder holder,
        boolean isOnTheFly
    ) {
        return new JavaElementVisitor() {

            private static final String DESCRIPTION_TEMPLATE = "Lombok builder is missing non nullable fields";

            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiMethod resolvedMethod = expression.resolveMethod();

                if (resolvedMethod == null
                    || !Objects.equals(resolvedMethod.getClass().getName(), "de.plushnikov.intellij.plugin.psi.LombokLightMethodBuilder")
                    || !Objects.equals(resolvedMethod.getName(), "build")) {
                    return;
                }

                PsiClass builderClass = getContainingBuilderClass(resolvedMethod);
                if (builderClass == null || processMissingFields(expression, getMandatoryFields(builderClass)).size() <= 0) {
                    return;
                }

                holder.registerProblem(expression, DESCRIPTION_TEMPLATE, ProblemHighlightType.WEAK_WARNING, myQuickFix);
            }
        };
    }

    private class LbiQuickFix implements LocalQuickFix {

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiMethodCallExpression expression = (PsiMethodCallExpression) descriptor.getPsiElement();
            PsiMethod resolvedMethod = expression.resolveMethod();

            if (resolvedMethod == null) {
                LOG.error("Resolved method null when applying fix");
                return;
            }

            List<String> missingFields = processMissingFields(
                expression, getMandatoryFields(getContainingBuilderClass(resolvedMethod))
            );

            if (missingFields.isEmpty()) {
                return;
            }

            String errorText = expression.getText();

            PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
            PsiMethodCallExpression fixedMethodExpression = (PsiMethodCallExpression) factory.createExpressionFromText(
                errorText.replace(".build()", "." + String.join("().", missingFields) + "().build()"), null
            );

            expression.replace(fixedMethodExpression);
        }

        @NotNull
        public String getFamilyName() {
            return QUICK_FIX_NAME;
        }
    }
}
