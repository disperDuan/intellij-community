/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection;

import com.intellij.codeInsight.FileModificationService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.ClassUtil;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Pavel.Dolgov
 */
public class SimplifyStreamApiCallChainsInspection extends BaseJavaBatchLocalInspectionTool {
  private static final String FOR_EACH_METHOD = "forEach";
  private static final String FOR_EACH_ORDERED_METHOD = "forEachOrdered";
  private static final String STREAM_METHOD = "stream";
  private static final String EMPTY_METHOD = "empty";
  private static final String AS_LIST_METHOD = "asList";
  private static final String OF_METHOD = "of";
  private static final String EMPTY_LIST_METHOD = "emptyList";
  private static final String EMPTY_SET_METHOD = "emptySet";
  private static final String SINGLETON_LIST_METHOD = "singletonList";
  private static final String SINGLETON_METHOD = "singleton";

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
    if (!PsiUtil.isLanguageLevel8OrHigher(holder.getFile())) {
      return PsiElementVisitor.EMPTY_VISITOR;
    }

    return new JavaElementVisitor() {
      @Override
      public void visitMethodCallExpression(PsiMethodCallExpression methodCall) {
        final PsiMethod method = methodCall.resolveMethod();
        if (isCallOf(method, CommonClassNames.JAVA_UTIL_COLLECTION, STREAM_METHOD, 0)) {
          final PsiMethodCallExpression qualifierCall = getQualifierMethodCall(methodCall);
          if (qualifierCall == null) return;
          final PsiMethod qualifier = qualifierCall.resolveMethod();
          ReplaceCollectionStreamFix fix = null;
          if (isCallOf(qualifier, CommonClassNames.JAVA_UTIL_ARRAYS, AS_LIST_METHOD, 1)) {
            if (hasSingleArrayArgument(qualifierCall)) {
              fix = new ArraysAsListSingleArrayFix();
            }
            else {
              fix = new ReplaceWithStreamOfFix("Arrays.asList()");
            }
          }
          else if (isCallOf(qualifier, CommonClassNames.JAVA_UTIL_COLLECTIONS, SINGLETON_LIST_METHOD, 1)) {
            if(!hasSingleArrayArgument(qualifierCall)) {
              fix = new ReplaceSingletonWithStreamOfFix("Collections.singletonList()");
            }
          }
          else if (isCallOf(qualifier, CommonClassNames.JAVA_UTIL_COLLECTIONS, SINGLETON_METHOD, 1)) {
            if(!hasSingleArrayArgument(qualifierCall)) {
              fix = new ReplaceSingletonWithStreamOfFix("Collections.singleton()");
            }
          }
          else if (isCallOf(qualifier, CommonClassNames.JAVA_UTIL_COLLECTIONS, EMPTY_LIST_METHOD, 0)) {
            fix = new ReplaceWithStreamEmptyFix(EMPTY_LIST_METHOD);
          }
          else if (isCallOf(qualifier, CommonClassNames.JAVA_UTIL_COLLECTIONS, EMPTY_SET_METHOD, 0)) {
            fix = new ReplaceWithStreamEmptyFix(EMPTY_SET_METHOD);
          }
          if (fix != null) {
            holder.registerProblem(methodCall, null, fix.getMessage(), fix);
          }
        }
        else {
          final String name;
          if (isCallOf(method, CommonClassNames.JAVA_UTIL_STREAM_STREAM, FOR_EACH_METHOD, 1)) {
            name = FOR_EACH_METHOD;
          }
          else if (isCallOf(method, CommonClassNames.JAVA_UTIL_STREAM_STREAM, FOR_EACH_ORDERED_METHOD, 1)) {
            name = FOR_EACH_ORDERED_METHOD;
          }
          else {
            return;
          }
          final PsiMethodCallExpression qualifierCall = getQualifierMethodCall(methodCall);
          if (qualifierCall == null) return;
          final PsiMethod qualifier = qualifierCall.resolveMethod();
          if (isCallOf(qualifier, CommonClassNames.JAVA_UTIL_COLLECTION, STREAM_METHOD, 0)) {
            String message = "Collection.stream()." + name + "() can be replaced with Collection.forEach()";
            final LocalQuickFix fix;
            if (FOR_EACH_METHOD.equals(name)) {
              fix = new CollectionForEachFix();
            }
            else {
              fix = new CollectionForEachOrderedFix();
              message += " (may change semantics)";
            }
            holder.registerProblem(methodCall, getCallChainRange(methodCall, qualifierCall), message, fix);
          }
        }
      }
    };
  }

  static boolean hasSingleArrayArgument(PsiMethodCallExpression qualifierCall) {
    final PsiExpression[] argumentExpressions = qualifierCall.getArgumentList().getExpressions();
    if (argumentExpressions.length == 1) {
      PsiType type = argumentExpressions[0].getType();
      if(type instanceof PsiArrayType) {
        PsiType methodType = qualifierCall.getType();
        // Rule out cases like Arrays.<String[]>asList(stringArr)
        if(methodType instanceof PsiClassType) {
          PsiType[] parameters = ((PsiClassType)methodType).getParameters();
          if(parameters.length == 1 && parameters[0].equals(type))
            return false;
        }
        return true;
      }
    }
    return false;
  }

  @Nullable
  private static PsiMethodCallExpression getQualifierMethodCall(PsiMethodCallExpression methodCall) {
    final PsiExpression qualifierExpression = methodCall.getMethodExpression().getQualifierExpression();
    if (qualifierExpression instanceof PsiMethodCallExpression) {
      return (PsiMethodCallExpression)qualifierExpression;
    }
    return null;
  }

  @NotNull
  protected TextRange getCallChainRange(@NotNull PsiMethodCallExpression expression,
                                        @NotNull PsiMethodCallExpression qualifierExpression) {
    final PsiReferenceExpression qualifierMethodExpression = qualifierExpression.getMethodExpression();
    final PsiElement qualifierNameElement = qualifierMethodExpression.getReferenceNameElement();
    final int startOffset = (qualifierNameElement != null ? qualifierNameElement : qualifierMethodExpression).getTextOffset();
    final int endOffset = expression.getMethodExpression().getTextRange().getEndOffset();
    return new TextRange(startOffset, endOffset).shiftRight(-expression.getTextOffset());
  }

  @Contract("null, _, _, _ -> false")
  protected static boolean isCallOf(@Nullable PsiMethod method,
                                    @NotNull String className,
                                    @NotNull String methodName,
                                    int parametersCount) {
    if (method == null) return false;
    if (methodName.equals(method.getName()) && method.getParameterList().getParametersCount() == parametersCount) {
      final PsiClass containingClass = method.getContainingClass();
      if (containingClass != null && className.equals(containingClass.getQualifiedName())) {
        return true;
      }
    }
    return false;
  }

  private static abstract class CallChainFixBase implements LocalQuickFix {
    @Nls
    @NotNull
    @Override
    public String getName() {
      return getFamilyName();
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      final PsiElement element = descriptor.getStartElement();
      if (element instanceof PsiMethodCallExpression) {
        if (!FileModificationService.getInstance().preparePsiElementForWrite(element.getContainingFile())) return;
        final PsiMethodCallExpression expression = (PsiMethodCallExpression)element;
        final PsiExpression forEachMethodQualifier = expression.getMethodExpression().getQualifierExpression();
        if (forEachMethodQualifier instanceof PsiMethodCallExpression) {
          final PsiMethodCallExpression previousExpression = (PsiMethodCallExpression)forEachMethodQualifier;
          final PsiExpression qualifierExpression = previousExpression.getMethodExpression().getQualifierExpression();
          replaceMethodCall(expression, previousExpression, qualifierExpression);
        }
      }
    }

    protected abstract void replaceMethodCall(@NotNull PsiMethodCallExpression methodCall,
                                              @NotNull PsiMethodCallExpression qualifierCall,
                                              @Nullable PsiExpression qualifierExpression);
  }

  private static abstract class ReplaceCollectionStreamFix extends CallChainFixBase {
    private final String myClassName;
    private final String myMethodName;
    private final String myQualifierCall;

    private ReplaceCollectionStreamFix(String qualifierCall, String className, String methodName) {
      myQualifierCall = qualifierCall;
      myClassName = className;
      myMethodName = methodName;
    }

    String getMessage() {
      return myQualifierCall + ".stream() can be replaced with " + ClassUtil.extractClassName(myClassName) + "." + myMethodName + "()";
    }

    @Nls
    @NotNull
    @Override
    public String getName() {
      return "Replace " + myQualifierCall + ".stream() with " + ClassUtil.extractClassName(myClassName) + "." + myMethodName + "()";
    }

    @Nullable
    protected String getTypeParameter(@NotNull PsiMethodCallExpression qualifierCall) {
      PsiType[] parameters = qualifierCall.getMethodExpression().getTypeParameters();
      return parameters.length == 1 ? parameters[0].getCanonicalText() : null;
    }

    @Override
    protected void replaceMethodCall(@NotNull PsiMethodCallExpression methodCall,
                                     @NotNull PsiMethodCallExpression qualifierCall,
                                     @Nullable PsiExpression qualifierExpression) {
      methodCall.getArgumentList().replace(qualifierCall.getArgumentList());

      final Project project = methodCall.getProject();
      String typeParameter = getTypeParameter(qualifierCall);
      String replacement;
      if (typeParameter != null) {
        replacement = myClassName + ".<" + typeParameter + ">" + myMethodName;
      }
      else {
        replacement = myClassName + "." + myMethodName;
      }
      final PsiExpression newMethodExpression = JavaPsiFacade.getElementFactory(project).createExpressionFromText(replacement, methodCall);
      JavaCodeStyleManager.getInstance(project).shortenClassReferences(methodCall.getMethodExpression().replace(newMethodExpression));
    }
  }

  private static class ReplaceWithStreamOfFix extends ReplaceCollectionStreamFix {
    private ReplaceWithStreamOfFix(String qualifierCall) {
      super(qualifierCall, CommonClassNames.JAVA_UTIL_STREAM_STREAM, OF_METHOD);
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return "Replace with Stream.of()";
    }
  }

  private static class ReplaceSingletonWithStreamOfFix extends ReplaceWithStreamOfFix {
    private ReplaceSingletonWithStreamOfFix(String qualifierCall) {
      super(qualifierCall);
    }

    @Nullable
    @Override
    protected String getTypeParameter(@NotNull PsiMethodCallExpression qualifierCall) {
      String typeParameter = super.getTypeParameter(qualifierCall);
      if(typeParameter != null)
        return typeParameter;
      PsiType[] argTypes = qualifierCall.getArgumentList().getExpressionTypes();
      if(argTypes.length == 1) {
        PsiType argType = argTypes[0];
        if(argType instanceof PsiArrayType) {
          return argType.getCanonicalText();
        }
      }
      return null;
    }
  }

  private static class ArraysAsListSingleArrayFix extends ReplaceCollectionStreamFix {
    private ArraysAsListSingleArrayFix() {
      super("Arrays.asList()", CommonClassNames.JAVA_UTIL_ARRAYS, STREAM_METHOD);
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return "Replace Arrays.asList().stream() with Arrays.stream()";
    }
  }

  private static class ReplaceWithStreamEmptyFix extends ReplaceCollectionStreamFix {
    private ReplaceWithStreamEmptyFix(String qualifierMethodName) {
      super("Collections." + qualifierMethodName + "()", CommonClassNames.JAVA_UTIL_STREAM_STREAM, EMPTY_METHOD);
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return "Replace with Stream.empty()";
    }
  }

  private static class CollectionForEachFix extends CallChainFixBase {
    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return "Replace Collection.stream()." + FOR_EACH_METHOD + "() with Collection.forEach()";
    }

    @Override
    protected void replaceMethodCall(@NotNull PsiMethodCallExpression methodCall,
                                     @NotNull PsiMethodCallExpression qualifierCall,
                                     @Nullable PsiExpression qualifierExpression) {
      if (qualifierExpression != null) {
        qualifierCall.replace(qualifierExpression);
      }
    }
  }

  private static class CollectionForEachOrderedFix extends CollectionForEachFix {
    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return "Replace Collection.stream()." + FOR_EACH_ORDERED_METHOD + "() with Collection.forEach() (may change semantics)";
    }

    @Override
    protected void replaceMethodCall(@NotNull PsiMethodCallExpression methodCall,
                                     @NotNull PsiMethodCallExpression qualifierCall,
                                     @Nullable PsiExpression qualifierExpression) {
      if (qualifierExpression != null) {
        final PsiElement nameElement = methodCall.getMethodExpression().getReferenceNameElement();
        if (nameElement != null) {
          qualifierCall.replace(qualifierExpression);
          final Project project = methodCall.getProject();
          PsiIdentifier forEachIdentifier = JavaPsiFacade.getElementFactory(project).createIdentifier(FOR_EACH_METHOD);
          nameElement.replace(forEachIdentifier);
        }
      }
    }
  }
}
