/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.jvmtools.analysis.nullness

import com.intellij.codeInsight.AnnotationTargetUtil.isTypeAnnotation
import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationOwner
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiCapturedWildcardType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiDisjunctionType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIntersectionType
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiParenthesizedExpression
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiTypeVisitor
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiVariable
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.TypeConversionUtil
import com.intellij.psi.util.parentOfType

fun hasNullableAnno(owner: PsiModifierListOwner?): Boolean =
  // Kotlin emits declaration annotations only, even though org.jetbrains.annotations.Nullable
  // is a type annotation, so honor type annotations if the owner isn't Java (b/354753804)
  indicatesNullable(owner?.modifierList, useTypeAnnos = owner?.isJava() != true)

/** Returns if the receiver is defined in Java. */
fun PsiModifierListOwner.isJavaDeclaration() =
  language == JavaLanguage.INSTANCE &&
    // Language is always Java if defined in binary, so additionally check for Kotlin metadata
    parentOfType<PsiClass>(withSelf = true)?.hasAnnotation("kotlin.Metadata") != true

private fun PsiModifierListOwner.isJava() = isJavaDeclaration()

fun indicatesNullable(owner: PsiAnnotationOwner?, useTypeAnnos: Boolean = true): Boolean =
  // applicableAnnotations is sometimes available where .annotations throws. Note it doesn't work
  // correctly for array annos, where useTypeAnnos is still helpful.
  owner?.applicableAnnotations?.any { it.isNullableAnno(useTypeAnnos) } == true

// TODO(b/308816245): use annotation names from JSpecify reference checker (or Error-Prone), see
// https://github.com/jspecify/jspecify-reference-checker/blob/main/src/main/java/com/google/jspecify/nullness/NullSpecAnnotatedTypeFactory.java
fun PsiAnnotation.isNullableAnno(useTypeAnnos: Boolean = true) =
  (qualifiedName?.endsWith(".Nullable") == true ||
    qualifiedName?.endsWith(".NullableDecl") == true || // checkerframework
    qualifiedName == "javax.annotation.CheckForNull") && // used in Guava
    // This treats annotations that are type and declarations "conservatively" as type annotations
    // but that should (?) be ok as long as we look for both. Note
    // com.intellij.codeInsight.AnnotationTargetUtil.isStrictlyTypeUseAnnotation doesn't work
    // outside IntelliJ but treats nullness annotations as type annotations anyway.
    (useTypeAnnos || !isTypeAnnotation(this))

fun indicatesNonNull(owner: PsiAnnotationOwner?, useTypeAnnos: Boolean = true): Boolean =
  owner?.applicableAnnotations?.any { it.isNonNullAnno(useTypeAnnos) } == true

fun PsiAnnotation.isNonNullAnno(useTypeAnnos: Boolean = true) =
  (qualifiedName?.endsWith(".NonNull") == true ||
    qualifiedName?.endsWith(".NotNull") == true ||
    qualifiedName?.endsWith(".NonNullDecl") == true || // checkerframework
    (qualifiedName == "javax.annotation.Nonnull" &&
      findAttributeValue("when")?.textMatches("javax.annotation.meta.When.ALWAYS") == true)) &&
    (useTypeAnnos || !isTypeAnnotation(this))

internal fun nullnessFromAnnotation(
  type: PsiType?,
  declaration: PsiModifierListOwner? = null,
  typeParameter: PsiTypeParameter? = null,
): Nullness =
  when {
    type == PsiTypes.nullType() -> Nullness.NULL
    type is PsiPrimitiveType -> Nullness.NONULL
    hasNullableAnno(declaration) || indicatesNullable(type) -> Nullness.NULLABLE
    indicatesNonNull(declaration?.modifierList, useTypeAnnos = false) || indicatesNonNull(type) ->
      Nullness.NONULL
    type?.hasNullableBound() == true && typeParameter?.hasNullableBound() != false ->
      if ((type is PsiClassType && type.resolve() is PsiTypeParameter) || type is PsiWildcardType) {
        Nullness.PARAMETRIC
      } else {
        Nullness.NULLABLE
      }
    else -> Nullness.NONULL
  }

/**
 * Returns if this type has a nullable upper bound, in particular:
 * * it [indicatesNullable] itself, or
 * * it represents a type variable or captured wildcard that has a nullable bound.
 */
fun PsiType.hasNullableBound(): Boolean = accept(TypeBoundVisitor)

private object TypeBoundVisitor : PsiTypeVisitor<Boolean>() {
  override fun visitType(type: PsiType): Boolean = indicatesNullable(type)

  override fun visitCapturedWildcardType(capturedWildcardType: PsiCapturedWildcardType): Boolean =
    (!capturedWildcardType.wildcard.isExtends || capturedWildcardType.upperBound.accept(this)) &&
      capturedWildcardType.typeParameter?.hasNullableBound() != false

  override fun visitClassType(classType: PsiClassType): Boolean {
    if (indicatesNullable(classType)) return true
    if (indicatesNonNull(classType)) return false // Don't check bounds below for `@NonNull T`
    // Class type can refer to a type parameter, which in turn may be annotated
    val referee = classType.resolve()
    return (referee is PsiTypeParameter && referee.hasNullableBound())
  }

  override fun visitDisjunctionType(disjunctionType: PsiDisjunctionType): Boolean =
    disjunctionType.disjunctions.any { it.accept(this) }

  override fun visitIntersectionType(intersectionType: PsiIntersectionType): Boolean =
    intersectionType.conjuncts.all { it.accept(this) }

  override fun visitPrimitiveType(primitiveType: PsiPrimitiveType): Boolean =
    TypeConversionUtil.isNullType(primitiveType)

  override fun visitWildcardType(wildcardType: PsiWildcardType): Boolean =
    !wildcardType.isExtends || wildcardType.extendsBound.accept(this)
}

private fun PsiTypeParameter.hasNullableBound(): Boolean {
  val bounds = extendsList.referencedTypes
  return if (bounds.isEmpty()) !isInNullMarkedScope() else bounds.all { it.hasNullableBound() }
}

fun PsiElement.isInNullMarkedScope(): Boolean {
  var parent: PsiElement? = this
  while (parent != null && parent !is PsiFile) {
    if (parent is PsiModifierListOwner) {
      if (parent.hasAnnotation("org.jspecify.annotations.NullMarked")) {
        return true
      }
      if (parent.hasAnnotation("org.jspecify.annotations.NullUnmarked")) {
        return false
      }
    }
    parent = parent.parent
  }
  // TODO(b/308816245): check package and module @Null[Un]Marked
  return false
}

fun isNullAwareHelperMethod(callee: PsiMethod): Boolean {
  if (callee.parameterList.parametersCount != 1) return false
  val ownerFqn = callee.containingClass?.qualifiedName
  return ((callee.name == "isNull" || callee.name == "nonNull") &&
    ownerFqn == "java.util.Objects") ||
    (callee.name == "ofNullable" && ownerFqn == "java.util.Optional") ||
    (callee.name == "fromNullable" && ownerFqn == "com.google.common.base.Optional")
}

fun isExplicitlyNonNullReturn(method: PsiMethod): Boolean =
  indicatesNonNull(method.modifierList, useTypeAnnos = method.returnType !is PsiArrayType) ||
    indicatesNonNull(method.returnTypeElement) ||
    indicatesNonNull(method.returnType)

private fun isExplicitlyNonNullVariable(variable: PsiVariable): Boolean =
  indicatesNonNull(variable.modifierList, useTypeAnnos = variable.type !is PsiArrayType) ||
    indicatesNonNull(variable.typeElement) ||
    indicatesNonNull(variable.type)

/**
 * Returns whether [method] lacks an explicit `@NonNull` return annotation, overrides at least one
 * supertype method whose return contract is `@Nullable` (directly or via generic substitution), and
 * does not override any supertype method that requires a non-null Kotlin return contract.
 */
fun overridesNullableReturn(method: PsiMethod): Boolean {
  if (
    method.isConstructor ||
      method.hasModifierProperty(PsiModifier.STATIC) ||
      method.returnType is PsiPrimitiveType ||
      isExplicitlyNonNullReturn(method)
  ) {
    return false
  }
  val subClass = method.containingClass
  val visited = HashSet<PsiMethod>()
  val queue = ArrayDeque<PsiMethod>()
  queue.addAll(method.findSuperMethods())
  var foundNullableSuper = false
  while (queue.isNotEmpty()) {
    val superMethod = queue.removeFirst()
    if (!visited.add(superMethod)) continue
    val substitutedReturn = substitutedSuperReturnType(superMethod, subClass)
    val superIsNullable =
      hasNullableAnno(superMethod) ||
        indicatesNullable(superMethod.returnTypeElement) ||
        indicatesNullable(superMethod.returnType) ||
        indicatesNullable(substitutedReturn)
    // Use the return type as seen from the overriding class, so that a type parameter with a
    // nullable bound (e.g., Kotlin `T`) instantiated with a non-null type argument counts as
    // non-null.
    val superRequiresNonNull =
      isExplicitlyNonNullReturn(superMethod) ||
        indicatesNonNull(substitutedReturn) ||
        (!superIsNullable &&
          (substitutedReturn ?: superMethod.returnType)?.hasNullableBound() != true &&
          (!superMethod.isJava() ||
            (superMethod.isInNullMarkedScope() && superMethod.body == null)))
    if (superRequiresNonNull) {
      return false
    }
    if (superIsNullable) {
      foundNullableSuper = true
    }
    queue.addAll(superMethod.findSuperMethods())
  }
  return foundNullableSuper
}

/**
 * Returns [superMethod]'s return type with the type arguments that [subClass] supplies for
 * [superMethod]'s declaring class substituted, or `null` if that can't be determined.
 */
fun substitutedSuperReturnType(superMethod: PsiMethod, subClass: PsiClass?): PsiType? {
  val superClass = superMethod.containingClass ?: return null
  if (subClass == null || !InheritanceUtil.isInheritorOrSelf(subClass, superClass, true)) {
    return null
  }
  return TypeConversionUtil.getSuperClassSubstitutor(superClass, subClass, PsiSubstitutor.EMPTY)
    .substitute(superMethod.returnType)
}

/**
 * Returns whether [method]'s body directly returns an unannotated Java platform call or field
 * (whose nullability in Kotlin is a flexible platform type `T!`), possibly through local variables
 * declared with `var`, whose Kotlin translation also has a flexible inferred type.
 *
 * Locals with explicit types and helper methods in the same file are not traced through, since
 * their translated Kotlin declarations are non-null and would throw before the value is returned.
 */
fun methodReturnsFlexiblePlatformValue(method: PsiMethod): Boolean {
  val body = method.body ?: return false
  var foundFlexibleReturn = false
  body.accept(
    object : JavaRecursiveElementWalkingVisitor() {
      override fun visitClass(aClass: PsiClass) {}

      override fun visitLambdaExpression(expression: PsiLambdaExpression) {}

      override fun visitReturnStatement(statement: PsiReturnStatement) {
        if (foundFlexibleReturn) {
          stopWalking()
          return
        }
        if (exprHasFlexiblePlatformNullness(statement.returnValue, method, emptySet())) {
          foundFlexibleReturn = true
          stopWalking()
          return
        }
        super.visitReturnStatement(statement)
      }
    }
  )
  return foundFlexibleReturn
}

private fun exprHasFlexiblePlatformNullness(
  expr: PsiExpression?,
  enclosingMethod: PsiMethod,
  visitedVars: Set<PsiVariable>,
): Boolean =
  when (expr) {
    null -> false
    is PsiParenthesizedExpression ->
      exprHasFlexiblePlatformNullness(expr.expression, enclosingMethod, visitedVars)
    is PsiConditionalExpression ->
      exprHasFlexiblePlatformNullness(expr.thenExpression, enclosingMethod, visitedVars) ||
        exprHasFlexiblePlatformNullness(expr.elseExpression, enclosingMethod, visitedVars)
    is PsiAssignmentExpression ->
      expr.operationSign.text == "=" &&
        exprHasFlexiblePlatformNullness(expr.rExpression, enclosingMethod, visitedVars)
    is PsiMethodCallExpression -> {
      val callee = expr.resolveMethod()
      callee != null &&
        !callee.isConstructor &&
        callee.returnType !is PsiPrimitiveType &&
        // Same-file methods get translated with explicit Kotlin return types
        callee.containingFile != enclosingMethod.containingFile &&
        callee.isJava() &&
        !callee.isInNullMarkedScope() &&
        !isExplicitlyNonNullReturn(callee)
    }
    is PsiReferenceExpression -> {
      val ref = expr.resolve()
      when {
        ref is PsiField ->
          ref.containingFile != enclosingMethod.containingFile &&
            ref !is PsiEnumConstant &&
            ref.type !is PsiPrimitiveType &&
            ref.isJava() &&
            !ref.isInNullMarkedScope() &&
            !isExplicitlyNonNullVariable(ref)
        // Only `var` locals keep a flexible type in Kotlin; other locals get explicit types.
        ref is PsiLocalVariable &&
          ref !in visitedVars &&
          ref.typeElement.isInferredType &&
          !isExplicitlyNonNullVariable(ref) ->
          exprHasFlexiblePlatformNullness(ref.initializer, enclosingMethod, visitedVars + ref)
        else -> false
      }
    }
    else -> false
  }
