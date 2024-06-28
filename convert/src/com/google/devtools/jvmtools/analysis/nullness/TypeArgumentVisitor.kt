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

import com.google.devtools.jvmtools.convert.util.runIf
import com.google.devtools.jvmtools.analysis.State
import com.google.devtools.jvmtools.analysis.UDataflowContext
import com.google.devtools.jvmtools.analysis.join
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDisjunctionType
import com.intellij.psi.PsiEllipsisType
import com.intellij.psi.PsiIntersectionType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiTypeVisitor
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.TypeConversionUtil.getSuperClassSubstitutor
import com.intellij.psi.util.TypeConversionUtil.isPrimitiveAndNotNull
import org.jetbrains.uast.UArrayAccessExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UExpressionList
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.ULabeledExpression
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.visitor.UastTypedVisitor

/**
 * Returns nullness of the given expression, which is recursively inferred from [analysis] results
 * and user-declared type annotations if [Nullness.PARAMETRIC] type parameters are involved. For
 * instance, this will return [Nullness.NONULL] for `identity("foo")` and [Nullness.NULLABLE] for
 * `identity(null)`, where `identity` is a generic method that returns its argument.
 *
 * @param selector to retrieve nullness of the given type argument of this expression's type (use
 *   `null` for array components)
 * @return inference result or `null` if inference wasn't able to produce any information
 */
internal fun UExpression.resultNullness(
  analysis: UDataflowContext<State<Nullness>>,
  vararg selector: PsiTypeParameter?,
): Nullness? = accept(TypeArgumentVisitor(this, analysis), selector.toList())

/**
 * Sequence of [PsiTypeParameter]s to "point to" a generic type component, using `null` for array
 * components. The [PsiTypeParameter]s' owners must be classes (not methods). Using this
 * representation allows identifying type components relative to a particular reference type, which
 * is helpful when dealing with subtypes: for instance, we can retrieve an [Iterable]'s type
 * argument even when given a `StringList` or similar custom type that itself may declare no or
 * different type parameters but declares itself as a subtype of `Iterable<String>`.
 *
 * @see [TypeArgumentVisitor.componentNullness]
 */
private typealias TypeComponentSelector = List<PsiTypeParameter?>

/**
 * Visitor that tries to infer [Nullness] for a given [TypeComponentSelector], often by recursively
 * visiting subexpressions and using their [analysis] results or explicitly declared annotations.
 * For instance, will infer [Nullness.NULLABLE] as the result type argument's nullness qualifier for
 * `List.of("foo", null)`, i.e., the inferred type is `List<@Nullable String>`.
 *
 * Similar to [org.jspecify.annotations.NullMarked] methods, the initial implementation requires
 * `@Nullable` type annotations on type arguments in local variable and method parameter types to
 * work correctly. It's also Java 5-like, in that it visits subexpressions but doesn't take the
 * target/needed type into account.
 */
private class TypeArgumentVisitor(
  private val startNode: UExpression,
  private val analysis: UDataflowContext<State<Nullness>>,
) : UastTypedVisitor<TypeComponentSelector, Nullness?> {
  override fun visitElement(node: UElement, data: TypeComponentSelector): Nullness? {
    TODO("Not yet implemented: $node $data")
  }

  override fun visitExpression(node: UExpression, data: TypeComponentSelector): Nullness? =
    if (node != startNode && data.isEmpty()) {
      analysis[node].value
    } else {
      expressionTypeNullness(node, data)
    }

  private fun expressionTypeNullness(
    node: UExpression,
    data: TypeComponentSelector,
    referencedDeclaration: PsiModifierListOwner? = null,
  ): Nullness? = node.getExpressionType()?.componentNullness(data, referencedDeclaration)

  override fun visitCallExpression(node: UCallExpression, data: TypeComponentSelector): Nullness? {
    if (node != startNode && data.isEmpty()) {
      return analysis[node].value
    }

    when (node.kind) {
      UastCallKind.NEW_ARRAY_WITH_DIMENSIONS ->
        // Assume new Foo[N] object arrays have nullable elements but are themselves non-null
        // TODO(kmb): could check if the innermost dimension is 0 and use non-null in that case
        return if (
          data.size < node.valueArgumentCount ||
            isPrimitiveAndNotNull(node.returnType?.deepComponentType)
        ) {
          Nullness.NONULL
        } else {
          Nullness.NULLABLE
        }
      UastCallKind.NEW_ARRAY_WITH_INITIALIZER,
      UastCallKind.NESTED_ARRAY_INITIALIZER ->
        return if (data.isEmpty()) {
          Nullness.NONULL
        } else {
          val componentSelector = data.subList(1)
          node.valueArguments
            .mapNotNull { it.accept(this, componentSelector) }
            .reduceOrNull(Nullness::join) ?: expressionTypeNullness(node, data)
        }
    }

    val callee = node.resolve() ?: return visitExpression(node, data)

    val declaredReturnType =
      if (callee.isConstructor) callee.containingClass?.fakeReceiverType() else callee.returnType
    return declaredReturnType?.componentNullness(data, callee) { remainingSelector ->
      inferFromArguments(node, callee, wantedTypeComponent = this, remainingSelector)
    } ?: return expressionTypeNullness(node, data, callee)
  }

  /**
   * Applies the given selector as far as possible and invokes [parametricNullness] if it lands on a
   * parametric type variable and the result of [nullnessFromAnnotation] otherwise.
   */
  private fun PsiType.componentNullness(
    selector: TypeComponentSelector,
    decl: PsiModifierListOwner?,
    fromIndex: Int = 0,
    parametricNullness: PsiType.(TypeComponentSelector) -> Nullness? = {
      if (it.isEmpty()) Nullness.PARAMETRIC else null
    },
  ): Nullness? {
    if (fromIndex == selector.size) {
      // Found it! Annotations take precedence even if this is a type parameter.
      val annotated =
        nullnessFromAnnotation(
          this,
          runIf(fromIndex == 0) { decl }, // only consider declaration annos at top level
          selector.lastOrNull(),
        )
      return if (annotated == Nullness.PARAMETRIC) parametricNullness(emptyList()) else annotated
    }

    // PsiTypeVisitor automatically "sees through" lambda/method reference types (b/349161001).
    // It's a bit inefficient to create a new one on each recursion, but allows the logic above to
    // run on all types and without creating a visitor.
    return accept(
      object : PsiTypeVisitor<Nullness?>() {
        override fun visitType(type: PsiType): Nullness? {
          TODO("Don't know how to traverse $this for $selector [$fromIndex]")
        }

        override fun visitArrayType(arrayType: PsiArrayType): Nullness? =
          arrayType.componentType.componentNullness(
            selector,
            decl,
            fromIndex + 1,
            parametricNullness,
          )

        override fun visitClassType(classType: PsiClassType): Nullness? {
          val generics: PsiClassType.ClassResolveResult = classType.resolveGenerics()
          val clazz = generics.element ?: return null
          if (clazz is PsiTypeParameter) return parametricNullness(selector.subList(fromIndex))

          val selectedParam = selector[fromIndex] ?: return null // expected array type
          val paramClass =
            requireNotNull(selectedParam.owner as? PsiClass) {
              "Selectors must be class type parameters: $selectedParam"
            }
          // This can happen when visiting wildcard bounds, in particular, Object
          if (!InheritanceUtil.isInheritorOrSelf(clazz, paramClass, /* checkDeep= */ true)) {
            return null
          }
          // Extract the selected type argument relative to the parameter's declaring class (see
          // getSuperClassSubstitutor's javadoc).
          val substitutor = getSuperClassSubstitutor(paramClass, clazz, generics.substitutor)
          val selectedType = substitutor.substitute(selectedParam)
          return selectedType?.componentNullness(selector, decl, fromIndex + 1, parametricNullness)
        }

        override fun visitWildcardType(wildcardType: PsiWildcardType): Nullness? {
          val wildcardedParam = selector[fromIndex - 1]
          val implicitBounds =
            wildcardedParam?.extendsList?.referencedTypes ?: PsiClassType.EMPTY_ARRAY
          // If there are implicit bounds, collect their constraints, otherwise ignore since the
          // implicit bound is Object on which we can't match a non-empty selector anyway.
          val implicitUpper =
            runIf(implicitBounds.isNotEmpty()) {
              implicitBounds
                .mapNotNull { it.componentNullness(selector, decl, fromIndex, parametricNullness) }
                .reduceOrNull(Nullness::equate)
            }
          return when {
            wildcardType.isSuper -> {
              val lowerBound =
                wildcardType.superBound.componentNullness(
                  selector,
                  decl,
                  fromIndex,
                  parametricNullness,
                )
              implicitUpper join lowerBound
            }
            wildcardType.isExtends -> {
              val upperBound =
                wildcardType.extendsBound.componentNullness(
                  selector,
                  decl,
                  fromIndex,
                  parametricNullness,
                )
              when {
                upperBound == null -> implicitUpper
                implicitUpper == null -> upperBound
                else -> implicitUpper.equate(upperBound)
              }
            }
            else -> implicitUpper
          }
        }

        override fun visitIntersectionType(intersectionType: PsiIntersectionType): Nullness? =
          intersectionType.superTypes
            .mapNotNull { it.componentNullness(selector, decl, fromIndex, parametricNullness) }
            .reduceOrNull(Nullness::equate)

        override fun visitDisjunctionType(disjunctionType: PsiDisjunctionType): Nullness? =
          disjunctionType.disjunctions
            .mapNotNull { it.componentNullness(selector, decl, fromIndex, parametricNullness) }
            .reduceOrNull(Nullness::join)
      }
    )
  }

  private fun inferFromArguments(
    node: UCallExpression,
    callee: PsiMethod,
    wantedTypeComponent: PsiType,
    remainingSelector: TypeComponentSelector,
  ): Nullness? {
    val constraints = mutableListOf<Nullness>()
    node.receiverType?.let { actualReceiverType ->
      // If there's a receiver, collect constraints from it, but use a fake declared receiver type
      // that refers to the type parameter we're trying to infer
      // TODO(b/308816245): can we do anything with implicit receivers?
      val receiver = node.receiver ?: return@let
      val fakeReceiverType = callee.containingClass?.fakeReceiverType(wantedTypeComponent)
      collectConstraintsFromArgument(
        constraints,
        wantedTypeComponent,
        remainingSelector,
        fakeReceiverType ?: actualReceiverType,
        receiver,
      )
    }
    for ((paramIndex, param) in callee.parameterList.parameters.withIndex()) {
      val arg = node.getArgumentForParameter(paramIndex) ?: continue
      val paramType = param.type
      if (param.isVarArgs && paramType is PsiEllipsisType && arg is UExpressionList) {
        for (element in arg.expressions) {
          collectConstraintsFromArgument(
            constraints,
            wantedTypeComponent,
            remainingSelector,
            paramType.componentType,
            element,
          )
        }
      } else {
        collectConstraintsFromArgument(
          constraints,
          wantedTypeComponent,
          remainingSelector,
          paramType,
          arg,
        )
      }
    }
    return constraints.reduceOrNull(Nullness::join).let {
      if (it == Nullness.NULL) Nullness.NULLABLE else it
    }
  }

  private fun collectConstraintsFromArgument(
    dest: MutableList<Nullness>,
    wantedTypeComponent: PsiType,
    remainingSelector: TypeComponentSelector,
    parameterType: PsiType,
    arg: UExpression,
  ) {
    parameterType.visitTypeComponents { typeComponent, paramTypeSelector ->
      if (wantedTypeComponent.isAssignableFrom(typeComponent)) {
        val fromArg = arg.accept(this, paramTypeSelector + remainingSelector)
        if (fromArg != null) dest += fromArg
      }
    }
  }

  override fun visitArrayAccessExpression(
    node: UArrayAccessExpression,
    data: TypeComponentSelector,
  ): Nullness? =
    if (node != startNode && data.isEmpty()) {
      analysis[node].value
    } else {
      node.receiver.accept(this, ARRAY_COMPONENT_SELECTOR + data)
    }

  override fun visitIfExpression(node: UIfExpression, data: TypeComponentSelector): Nullness? =
    if (node.isTernary) {
      val thenNullness = node.thenExpression?.accept(this, data)
      val elseNullness = node.elseExpression?.accept(this, data)
      thenNullness join elseNullness
    } else {
      super.visitIfExpression(node, data)
    }

  override fun visitParenthesizedExpression(
    node: UParenthesizedExpression,
    data: TypeComponentSelector,
  ): Nullness? = node.expression.accept(this, data)

  override fun visitLabeledExpression(
    node: ULabeledExpression,
    data: TypeComponentSelector,
  ): Nullness? = node.expression.accept(this, data)

  override fun visitQualifiedReferenceExpression(
    node: UQualifiedReferenceExpression,
    data: TypeComponentSelector,
  ): Nullness? =
    if (node.selector is UCallExpression) {
      // UAST models method calls with receiver as a UCallExpr nested into UQualifiedReferenceExpr.
      // Just analyze the nested method call in that case (which will still look at the receiver).
      node.selector.accept(this, data)
    } else {
      super.visitQualifiedReferenceExpression(node, data)
    }

  private companion object {
    val ARRAY_COMPONENT_SELECTOR: TypeComponentSelector = listOf(null)

    /** Calls [block] for each (transitive) type component of this type. */
    fun PsiType.visitTypeComponents(block: (PsiType, TypeComponentSelector) -> Unit) {
      accept(
        object : PsiTypeVisitor<Unit>() {
          private val selector = ArrayDeque<PsiTypeParameter?>()

          override fun visitType(type: PsiType) {
            block(type, selector)
          }

          override fun visitArrayType(arrayType: PsiArrayType) {
            super.visitArrayType(arrayType)
            selector.addLast(null)
            arrayType.componentType.accept(this)
            selector.removeLast()
          }

          override fun visitClassType(classType: PsiClassType) {
            super.visitClassType(classType)
            val typeParams = classType.resolve()?.typeParameters ?: PsiTypeParameter.EMPTY_ARRAY
            for ((typeArgIndex, typeArg) in classType.parameters.withIndex()) {
              selector.addLast(typeParams.getOrNull(typeArgIndex) ?: continue)
              typeArg.accept(this)
              selector.removeLast()
            }
          }
        }
      )
    }

    fun PsiClass.fakeReceiverType(knownTypeArg: PsiType? = null): PsiClassType {
      val fixedTypeParam = (knownTypeArg as? PsiClassType)?.resolve() as? PsiTypeParameter
      var substitutor = PsiSubstitutor.EMPTY
      if (fixedTypeParam != null) substitutor = substitutor.put(fixedTypeParam, knownTypeArg)
      return JavaPsiFacade.getElementFactory(project).createType(this, substitutor)
    }

    fun <T> List<T>.subList(fromIndex: Int): List<T> = subList(fromIndex, size)
  }
}
