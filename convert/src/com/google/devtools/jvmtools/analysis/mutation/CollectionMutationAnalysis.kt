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

import com.android.tools.lint.detector.api.isKotlin
import com.google.devtools.jvmtools.convert.util.runIf
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
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil.getPackageName
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.light.classes.symbol.annotations.getJvmNameFromAnnotation
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UBinaryExpressionWithType
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UForEachExpression
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
import org.jetbrains.uast.toUElement
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
            Tuple(
              callee.parameterList.parameters.associateWith {
                call.mutationFromDeclaration(callee, it)
              }
            )

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

    return when {
      method == null -> Mutation.READ
      method.returnType == PsiTypes.voidType() -> Mutation.UNUSED
      else -> rootNode.mutationFromDeclaration(method, parameter = null)
    }
  }

  /**
   * Returns the declared mutation of given [parameter], or of the [method]'s return value if
   * [parameter] is `null`.
   */
  @OptIn(KaExperimentalApi::class)
  private fun UElement.mutationFromDeclaration(
    method: PsiMethod,
    parameter: PsiParameter?,
  ): Mutation {
    check(parameter == null || parameter.declarationScope == method) {
      "expected parameter of $method but got $parameter"
    }
    val declaration = parameter ?: method
    if (declaration.hasMutableAnnotation()) return Mutation.MODIFIED
    if (
      !isKotlin(declaration.language) &&
        method.containingClass?.hasAnnotation("kotlin.Metadata") != true
    ) {
      // isKotlin only works for declarations in source, while @Metadata annotations mark Kotlin
      // classes loaded from Jars
      return Mutation.READ
    }

    // If declaration is Kotlin, use Analysis API to look for MutableXxx types. (Don't do this for
    // Java declarations, where the API will use flexible types that include mutable collections.)
    val isMutable =
      sourcePsi?.let { useSite ->
        analyze(KaModuleProvider.getInstance(useSite.project).getModule(useSite, null)) {
          // Local declaration because it needs KaSession in scope
          fun KaType.isMutableCollectionType() =
            isSubtypeOf(ClassId.fromString("kotlin/collections/MutableCollection")) ||
              isSubtypeOf(ClassId.fromString("kotlin/collections/MutableIterator")) ||
              isSubtypeOf(ClassId.fromString("kotlin/collections/MutableMap")) ||
              isSubtypeOf(ClassId.fromString("kotlin/collections/MutableMap.MutableEntry"))

          if (isKotlin(declaration.language)) {
            // KaJavaInteroperabilityComponent doesn't appear to work for Kotlin declarations, but
            // we can use Ka[Expression]TypeProvider from Kotlin PSI if we have sources. The logic
            // for binary declarations below would probably also work, but this is more preceise.
            val type =
              (declaration.toUElement()?.sourcePsi as? KtCallableDeclaration)?.returnType
                // sourcePsi is null for extension receivers, so look them up directly
                ?: (method.toUElementOfType<UMethod>()?.sourcePsi as? KtNamedFunction)
                  ?.receiverTypeReference
                  ?.type
            type?.isMutableCollectionType()
          } else {
            // We'll get here for Kotlin declarations loaded from Jars. Unfortunately,
            // KaJavaInteroperabilityComponent's asKaType, callableSymbol, etc. utilities don't work
            // for binary classes, so we approximate finding the method "manually" below.
            val scope =
              method.containingClass?.qualifiedName?.replace('.', '/')?.replace('$', '.')?.let {
                findClass(ClassId.fromString(it))?.memberScope
              }
                ?: method.containingClass?.let { clazz ->
                  // If we can't find the class it may be synthetic, so look for top-level functions
                  findPackage(FqName(getPackageName(clazz) ?: ""))?.packageScope
                }
                ?: return@analyze null
            val jvmParamIdx =
              if (parameter != null) method.parameterList.getParameterIndex(parameter) else -1

            val candidates =
              if (method.isConstructor) {
                scope.constructors
              } else {
                scope.callables // get all callables so we can filter by JVM name
                  .filterIsInstance<KaFunctionSymbol>()
                  .filter {
                    method.name ==
                      (it.getJvmNameFromAnnotation() ?: it.callableId?.callableName?.asString())
                  }
              }

            // Because overload resolution is complicated and error-prone, we over-approximate here
            // by seeing if any overload uses MutableXxx types in the requested position.
            // TODO: KT-83483 - Find a way to get correct KaCallableSymbol, ideally also from source
            candidates
              .mapNotNull {
                when {
                  parameter == null -> it // use function return type
                  // Extension receiver appears as first parameter so adjust for that
                  jvmParamIdx == 0 && it.isExtension -> it.receiverParameter
                  // Look up the referenced parameter, ignoring methods with too few parameters
                  else -> it.valueParameters.getOrNull(jvmParamIdx - (if (it.isExtension) 1 else 0))
                }
              }
              .any { it.returnType.isMutableCollectionType() }
          }
        }
      }

    return if (isMutable == true) Mutation.MODIFIED else Mutation.READ
  }

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

  @Suppress("DEPRECATION") // UVariable.psi gives us PsiVariable
  override fun visitVariable(
    node: UVariable,
    data: TransferInput<State<Mutation>>,
  ): TransferResult<State<Mutation>> {
    // Propagate from variable to its initializer or iterated collection, if any.
    val rhs =
      node.uastInitializer
        ?: (node.uastParent as? UForEachExpression)?.iteratedValue
        ?: return data.toNormalResult()
    val input = data.value()
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
          "entrySet",
          "keySet",
          "values" -> input.value.getOrUnused(node)
          else -> Mutation.READ
        }
      val result =
        State(
          input.value +
            listOfNotNull(
              node.receiver?.let { it to mutation },
              runIf(callee?.name?.endsWith("All") == true) {
                node.valueArguments.getOrNull(0)?.let { it to Mutation.READ }
              },
            ),
          input.store,
        )
      return TransferResult.normal(result)
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
