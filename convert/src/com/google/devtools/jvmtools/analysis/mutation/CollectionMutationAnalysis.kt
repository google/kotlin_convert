/*
 * Copyright 2025 Google LLC
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

package com.google.devtools.jvmtools.analysis.mutation

import com.google.devtools.jvmtools.analysis.CfgRoot
import com.google.devtools.jvmtools.analysis.CfgRootKey
import com.google.devtools.jvmtools.analysis.InterproceduralAnalysisBuilder
import com.google.devtools.jvmtools.analysis.InterproceduralResult
import com.google.devtools.jvmtools.analysis.State
import com.google.devtools.jvmtools.analysis.TransferInput
import com.google.devtools.jvmtools.analysis.TransferResult
import com.google.devtools.jvmtools.analysis.Tuple
import com.google.devtools.jvmtools.analysis.UTransferFunction
import com.google.devtools.jvmtools.analysis.Value
import com.google.devtools.jvmtools.analysis.passthroughResult
import com.google.devtools.jvmtools.analysis.toNormalResult
import com.google.errorprone.annotations.Immutable
import com.intellij.psi.LambdaUtil.getFunctionalInterfaceMethod
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UBinaryExpressionWithType
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UReturnExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.UastBinaryExpressionWithTypeKind
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.getParameterForArgument
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.tryResolve
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Totally ordered lattice representing [UNUSED] (bottom), [READ] (only), or [MODIFIED] (and
 * possibly read) collections.
 *
 * Declared in ascending order so [implies] and [join] can be implemented using `<=`.
 */
@Immutable
enum class Mutation : Value<Mutation> {
  UNUSED,
  READ,
  MODIFIED;

  override val isBottom: Boolean
    get() = this == UNUSED

  override fun implies(other: Mutation): Boolean = this <= other

  override fun join(other: Mutation): Mutation = maxOf(this, other)
}

@Immutable
data class MutationKey(override val callee: CfgRoot, val resultMutation: Mutation) : CfgRootKey

/** [collectionMutation] aims to infer collections that Kotlin would consider `MutableList` etc. */
object CollectionMutationAnalysis {
  internal val BOTTOM = State.empty<Mutation>()

  /** Infers mutated collections in the given [UFile]. */
  fun UFile.collectionMutation(): InterproceduralResult<MutationKey, State<Mutation>> {
    val result =
      InterproceduralAnalysisBuilder<MutationKey, State<Mutation>>(BOTTOM) { ctx ->
        CollectionMutationTransfer(ctx.analysisKey.resultMutation) mutations@{ call, resultMutation
          ->
          val callee = call.resolve() ?: return@mutations Tuple.empty()
          val declaredMutations: Tuple<PsiVariable, Mutation> =
            @Suppress("Immutable") // PSI can't be annotated
            Tuple(callee.parameterList.parameters.associateWith { mutationFromAnnotation(it) })

          // Limit scope of analysis to given file for now
          if (callee.containingFile != sourcePsi) return@mutations declaredMutations
          val method = callee.toUElementOfType<UMethod>()
          // If we have a method body for the called method, analyze it even if the method is open
          // or public. Since we're optimistically assuming readonly collections above that's not
          // really worse than just going by annotations for open methods, but ideally we'd
          // analyze any overrides of open methods (at least visible ones).
          // TODO(b/309967546): handle other files and analyze possible callees for open methods
          if (method?.uastBody != null) {
            val root = CfgRoot.of(method)
            // Always honor @Mutable annotations by joining analysis results with declared
            declaredMutations join
              ctx
                .dataflowResult(
                  MutationKey(root, root.declaredResultMutation() join resultMutation),
                  BOTTOM,
                )
                .finalResult
                .store
          } else {
            declaredMutations
          }
        }
      }

    accept(
      object : AbstractUastVisitor() {
        override fun visitMethod(node: UMethod): Boolean {
          addRoot(CfgRoot.of(node))
          return super.visitMethod(node)
        }

        override fun visitLambdaExpression(node: ULambdaExpression): Boolean {
          addRoot(CfgRoot.of(node))
          return super.visitLambdaExpression(node)
        }

        private fun addRoot(root: CfgRoot) {
          result.addRoot(MutationKey(root, root.declaredResultMutation())) { BOTTOM }
        }
      }
    )

    return result.build()
  }

  private fun CfgRoot.declaredResultMutation(): Mutation {
    val method: PsiMethod? =
      when (this) {
        is CfgRoot.Method -> rootNode
        is CfgRoot.Lambda -> getFunctionalInterfaceMethod(rootNode.functionalInterfaceType)
      }

    return if (method?.returnType == PsiTypes.voidType()) {
      Mutation.UNUSED
    } else {
      mutationFromAnnotation(method)
    }
  }

  // TODO(b/309967546): handle MutableXxx parameters / return types for methods defined in Kotlin
  private fun mutationFromAnnotation(declaration: PsiModifierListOwner?): Mutation =
    if (declaration?.hasMutableAnnotation() == true) Mutation.MODIFIED else Mutation.READ

  /** Returns true if there's a `@Mutable` annotation. */
  private fun PsiModifierListOwner.hasMutableAnnotation() =
    hasAnnotation("kotlin.annotations.jvm.Mutable")
}

/**
 * Defines a backwards dataflow analysis that records mutations to collections, including through
 * local variables and iterators etc.
 *
 * @param assumedReturn the mutation to assume for the return value of the method
 * @param calleeMutations a function that returns the mutations of parameters of the called method
 */
private class CollectionMutationTransfer(
  val assumedReturn: Mutation,
  val calleeMutations: (UCallExpression, Mutation) -> Tuple<PsiVariable, Mutation>,
) : UTransferFunction<State<Mutation>> {
  override val isBackwards: Boolean
    get() = true

  override val bottom: State<Mutation>
    get() = CollectionMutationAnalysis.BOTTOM

  override fun visitVariable(
    node: UVariable,
    data: TransferInput<State<Mutation>>,
  ): TransferResult<State<Mutation>> {
    val rhs = node.uastInitializer ?: return data.toNormalResult()
    val input = data.value()
    @Suppress("DEPRECATION") // .psi gives us PsiVariable
    return TransferResult.normal(input.withValue(rhs, input.store.getOrUnused(node.psi)))
  }

  override fun visitReturnExpression(
    node: UReturnExpression,
    data: TransferInput<State<Mutation>>,
  ): TransferResult<State<Mutation>> {
    return TransferResult.normal(data.value().withValue(node.returnExpression, assumedReturn))
  }

  override fun visitBinaryExpression(
    node: UBinaryExpression,
    data: TransferInput<State<Mutation>>,
  ): TransferResult<State<Mutation>> =
    if (node.operator == UastBinaryOperator.ASSIGN) {
      val lhs = node.leftOperand.tryResolve() as? PsiVariable
      if (lhs != null) {
        val input = data.value()
        // propagate from left to right (i.e., backwards), then reset left
        val result = input.value.withMapping(node.rightOperand, input.store.getOrUnused(lhs))
        TransferResult.normal(State(result, input.store.withMapping(lhs, Mutation.UNUSED)))
      } else {
        data.passthroughResult(node)
      }
    } else {
      visitPolyadicExpression(node, data)
    }

  override fun visitSimpleNameReferenceExpression(
    node: USimpleNameReferenceExpression,
    data: TransferInput<State<Mutation>>,
  ): TransferResult<State<Mutation>> {
    val variable = node.resolve() as? PsiVariable ?: return data.passthroughResult(node)
    val input = data.value()
    // update store with mutations recorded for this variable use
    return TransferResult.normal(
      State(
        input.value,
        input.store.withMapping(
          variable,
          input.value.getOrUnused(node) join input.store.getOrUnused(variable),
        ),
      )
    )
  }

  override fun visitIfExpression(
    node: UIfExpression,
    data: TransferInput<State<Mutation>>,
  ): TransferResult<State<Mutation>> =
    if (node.isTernary) {
      val input = data.value()
      val value = input.value.getOrUnused(node)
      TransferResult.normal(
        State(
          input.value + listOfNotNull(node.thenExpression, node.elseExpression).map { it to value },
          input.store,
        )
      )
    } else {
      super.visitIfExpression(node, data)
    }

  override fun visitQualifiedReferenceExpression(
    node: UQualifiedReferenceExpression,
    data: TransferInput<State<Mutation>>,
  ): TransferResult<State<Mutation>> =
    // Propagate to selector--this helps dealing with method calls being modeled as qualified
    // ref expressions. It's at first glance a bit odd for field references, but in the current
    // analysis that just ends up merging all accesses to the same field regardless of access path,
    // which is fine considering we just want to see if there's any mutating accesses that would
    // make the field mutable.
    propagateToSubexpr(node, node.selector, data)

  override fun visitParenthesizedExpression(
    node: UParenthesizedExpression,
    data: TransferInput<State<Mutation>>,
  ): TransferResult<State<Mutation>> = propagateToSubexpr(node, node.expression, data)

  override fun visitBinaryExpressionWithType(
    node: UBinaryExpressionWithType,
    data: TransferInput<State<Mutation>>,
  ): TransferResult<State<Mutation>> =
    if (node.operationKind is UastBinaryExpressionWithTypeKind.TypeCast) {
      propagateToSubexpr(node, node.operand, data)
    } else {
      super.visitBinaryExpressionWithType(node, data)
    }

  override fun visitCallExpression(
    node: UCallExpression,
    data: TransferInput<State<Mutation>>,
  ): TransferResult<State<Mutation>> {
    val input = data.value()
    val callee = node.resolve()

    // Handle collection operations
    if (
      InheritanceUtil.isInheritor(callee?.containingClass, "java.lang.Iterable") ||
        InheritanceUtil.isInheritor(callee?.containingClass, "java.util.Map") ||
        InheritanceUtil.isInheritor(callee?.containingClass, "java.util.Map.Entry") ||
        InheritanceUtil.isInheritor(callee?.containingClass, "java.util.Iterator")
    ) {
      val mutation =
        when (callee?.name) {
          "add", // collection
          "addAll", // collection
          "put", // map
          "putAll", // map
          "putIfAbsent", // map
          "set", // collection, iterator
          "setValue", // Map.Entry
          "replace",
          "compute",
          "computeIfAbsent",
          "computeIfPresent",
          "merge",
          "remove",
          "removeAll" -> Mutation.MODIFIED
          // merge iterator etc.'s mutations into its originating collection
          "iterator",
          "listIterator",
          "entries",
          "keySet",
          "values" -> input.value.getOrUnused(node)
          else -> Mutation.READ
        }
      return TransferResult.normal(input.withValue(node.receiver, mutation))
    }

    val value = input.value.getOrUnused(node)
    val calleeMutations: Tuple<PsiVariable, Mutation> = calleeMutations(node, value)
    // Map callee mutations to the corresponding call arguments
    return TransferResult.normal(
      State(
        input.value +
          node.valueArguments.map { arg ->
            val paramNullness = node.getParameterForArgument(arg)?.let { calleeMutations[it] }
            arg to (paramNullness ?: Mutation.UNUSED)
          },
        input.store,
      )
    )
  }

  private fun propagateToSubexpr(
    node: UExpression,
    subexpr: UExpression,
    data: TransferInput<State<Mutation>>,
  ): TransferResult<State<Mutation>> {
    val input = data.value()
    return TransferResult.normal(input.withValue(subexpr, input.value.getOrUnused(node)))
  }

  private fun State<Mutation>.withValue(key: UExpression?, newValue: Mutation): State<Mutation> =
    if (key != null) State(value.withMapping(key, newValue), store) else this

  private fun <T : Any> Tuple<T, Mutation>.getOrUnused(key: T): Mutation =
    getOrDefault(key, Mutation.UNUSED)
}
