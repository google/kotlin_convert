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
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationOwner
import com.intellij.psi.PsiCapturedWildcardType
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDisjunctionType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIntersectionType
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiTypeVisitor
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.util.TypeConversionUtil

fun hasNullableAnno(owner: PsiModifierListOwner?): Boolean =
  indicatesNullable(owner?.modifierList, useTypeAnnos = false)

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
      if (
        (type is PsiClassType && type.resolve() is PsiTypeParameter) ||
          type is PsiWildcardType ||
          type is PsiCapturedWildcardType
      ) {
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
      if (
        parent.hasAnnotation("org.jspecify.annotations.NullMarked") ||
          parent.hasAnnotation("org.jspecify.annotations.NullMarked")
      ) {
        return true
      }
      if (
        parent.hasAnnotation("org.jspecify.annotations.NullUnmarked") ||
          parent.hasAnnotation("org.jspecify.annotations.NullUnmarked")
      ) {
        return false
      }
    }
    parent = parent.parent
  }
  // TODO(b/308816245): check package and module @Null[Un]Marked
  return false
}
