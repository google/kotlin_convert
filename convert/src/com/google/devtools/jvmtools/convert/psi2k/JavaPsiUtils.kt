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

package com.google.devtools.jvmtools.convert.psi2k

import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiJavaToken
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierList
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.impl.JavaConstantExpressionEvaluator.computeConstantExpression
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.parentOfType

val PsiModifierListOwner.isAbstract: Boolean
  get() = hasModifierProperty(PsiModifier.ABSTRACT)
val PsiModifierListOwner.isFinal: Boolean
  get() = hasModifierProperty(PsiModifier.FINAL)
val PsiModifierListOwner.isStatic: Boolean
  get() = hasModifierProperty(PsiModifier.STATIC)
val PsiModifierListOwner.isPrivate: Boolean
  get() = hasModifierProperty(PsiModifier.PRIVATE)

val PsiModifierList.isStatic: Boolean
  get() = hasModifierProperty(PsiModifier.STATIC)
val PsiModifierList.isPrivate: Boolean
  get() = hasModifierProperty(PsiModifier.PRIVATE)

internal fun PsiVariable.isEffectivelyFinal(): Boolean {
  if (hasModifierProperty(PsiModifier.FINAL)) return true
  val scope =
    when (this) {
      is PsiParameter -> declarationScope
      is PsiLocalVariable -> parentOfType<PsiCodeBlock>() ?: containingFile
      else -> return false // don't do anything clever for fields
    }
  // The following isn't accurate for variables that are initialized after declaration; need flow
  // analysis here.
  var effectivelyFinal = true
  scope.accept(
    object : JavaRecursiveElementWalkingVisitor() {
      override fun visitReferenceExpression(expr: PsiReferenceExpression) {
        if (expr.resolve() == this@isEffectivelyFinal && PsiUtil.isAccessedForWriting(expr)) {
          effectivelyFinal = false
          stopWalking()
        }
      }
    }
  )
  return effectivelyFinal
}

internal fun PsiElement.tokenOrNull(): String? = (this as? PsiJavaToken)?.text

internal fun PsiElement.isToken(value: String): Boolean = tokenOrNull() == value

internal fun PsiExpression.constantValue(): Any? =
  computeConstantExpression(this, /* throwExceptionOnOverflow= */ false)

/** Get name of a class with all its outer class names */
internal fun PsiClass.getNameOfPossiblyInnerClass(): String {
  val outerName = (parent as? PsiClass)?.getNameOfPossiblyInnerClass()
  return if (outerName == null) {
    name ?: ""
  } else {
    "$outerName.$name"
  }
}
