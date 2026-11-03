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
import com.google.devtools.jvmtools.analysis.Cfg
import com.google.devtools.jvmtools.analysis.CfgForeachIteratedExpression
import com.google.devtools.jvmtools.analysis.CfgRoot
import com.google.devtools.jvmtools.analysis.CfgSwitchBranchExpression
import com.google.devtools.jvmtools.analysis.InterproceduralAnalysisBuilder
import com.google.devtools.jvmtools.analysis.InterproceduralResult
import com.google.devtools.jvmtools.analysis.State
import com.google.devtools.jvmtools.analysis.TransferInput
import com.google.devtools.jvmtools.analysis.TransferResult
import com.google.devtools.jvmtools.analysis.Tuple
import com.google.devtools.jvmtools.analysis.UDataflowResult
import com.google.devtools.jvmtools.analysis.UInterproceduralAnalysisContext
import com.google.devtools.jvmtools.analysis.UTransferFunction
import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.LambdaUtil
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiCall
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil.extractIterableTypeParameter
import org.jetbrains.uast.UArrayAccessExpression
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UBinaryExpressionWithType
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UCallableReferenceExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UClassLiteralExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UField
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UForEachExpression
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.ULocalVariable
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UObjectLiteralExpression
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UReturnExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.USuperExpression
import org.jetbrains.uast.UThisExpression
import org.jetbrains.uast.UUnaryExpression
import org.jetbrains.uast.UastBinaryExpressionWithTypeKind
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.UastPrefixOperator
import org.jetbrains.uast.getParameterForArgument
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.internal.acceptList
import org.jetbrains.uast.resolveToUElementOfType
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor
import org.jetbrains.uast.visitor.UastTypedVisitor

/** [Nullness] dataflow analysis that aims to imitate Kotlin's nullness type system. */
object NullnessAnalysis {
  internal val BOTTOM = State.empty<Nullness>()

  fun UFile.nullness(): InterproceduralResult<CfgRoot, State<Nullness>> {
    val result =
      InterproceduralAnalysisBuilder<CfgRoot, State<Nullness>>(BOTTOM) { ctx ->
        fun analyzeLambda(lambda: ULambdaExpression): State<Nullness> =
          ctx.dataflowResult(CfgRoot.of(lambda)).finalResult

        NullnessTransfer(
          cfgRoot = ctx.analysisKey.callee,
          analyzeLambda = ::analyzeLambda,
          analyzeCallee = callee@{ call, state ->
              val callee = call.resolveToUElementOfType<UMethod>() ?: return@callee null
              // Trust annotations for non-private methods for now, since we can't fully analyze
              // their call graphs.
              if (!callee.hasModifierProperty(PsiModifier.PRIVATE)) return@callee null

              // Methods with type parameters are complicated because they might be parametric,
              // so skip those for now as well.
              // TODO(b/384955376): include methods with type parameters in interprocedural analysis
              if (callee.hasTypeParameters()) return@callee null

              val storeContents = mutableMapOf<PsiVariable, Nullness>()
              callee.parameterList.parameters.forEachIndexed { index, param ->
                if ((param.type as? PsiClassType)?.resolve() is PsiTypeParameter) return@callee null
                // Always honor callee annotations but join with what we know about arguments
                storeContents[param] =
                  nullnessFromAnnotation(param.type, param) join
                    (state.value[call.getArgumentForParameter(index)] ?: Nullness.BOTTOM)
              }

              val root = CfgRoot.of(callee)
              val dataflowResult =
                ctx.dataflowResult(root, ctx.capturedState(root, storeContents)).finalResult

              callee
                .returnValueNullness(dataflowResult, ::analyzeLambda)
                .takeIf { it != Nullness.PARAMETRIC }
                ?.let {
                  // If we get a result, join it with callee annotation
                  it join nullnessFromAnnotation(callee.returnType, callee)
                }
            },
        )
      }
    accept(
      object : AbstractUastVisitor() {
        override fun visitMethod(node: UMethod): Boolean {
          if (node.sourcePsi != null) {
            val root = CfgRoot.of(node)
            result.addRoot(root) { ctx -> ctx.initialState(root) }
          }
          return super.visitMethod(node)
        }

        override fun visitLambdaExpression(node: ULambdaExpression): Boolean {
          // We'll usually visit lambdas in the course of analyzing the surrounding method, but to
          // guarantee data for all lambdas we also treat them as roots.
          val root = CfgRoot.of(node)
          result.addRoot(root) { ctx -> ctx.initialState(root) }
          return super.visitLambdaExpression(node)
        }
      }
    )
    return result.build()
  }

  private fun UInterproceduralAnalysisContext<CfgRoot, State<Nullness>>.dataflowResult(
    root: CfgRoot
  ): UDataflowResult<State<Nullness>> = dataflowResult(root, initialState(root))

  /**
   * Returns the initial [State.store] to use for the given analysis [root], which includes:
   * 1. the given root's parameters based on their annotations, and
   * 2. any captured final variables with the inferred analysis result at the point in the outer
   *    scope where the variable is captured.
   */
  private fun UInterproceduralAnalysisContext<CfgRoot, State<Nullness>>.initialState(
    root: CfgRoot
  ): State<Nullness> {
    val storeContents = mutableMapOf<PsiVariable, Nullness>()

    @Suppress("DEPRECATION") // .psi gives us a PsiParameter
    when (root) {
      is CfgRoot.Method -> root.rootNode.uastParameters
      is CfgRoot.Lambda -> root.rootNode.valueParameters
    }.associateTo(storeContents) { it.psi to nullnessFromAnnotation(it.type, it) }

    if (root is CfgRoot.Lambda) {
      // Try to refine lambda parameters based on surrounding call arguments
      val enclosing =
        when (
          val rootNode =
            root.rootNode.getParentOfType(
              strict = true,
              UMethod::class.java,
              ULambdaExpression::class.java,
            )
        ) {
          is UMethod -> CfgRoot.of(rootNode)
          is ULambdaExpression -> CfgRoot.of(rootNode)
          else -> null
        }
      if (enclosing != null) {
        root.rootNode.refineFromArguments(
          storeContents,
          analysis = { dataflowResult(enclosing)[it] },
          analyzeLambda = { dataflowResult(CfgRoot.of(it)).finalResult },
        )
      }
    }

    return capturedState(root, storeContents)
  }

  private fun UInterproceduralAnalysisContext<CfgRoot, State<Nullness>>.capturedState(
    root: CfgRoot,
    storeContents: MutableMap<PsiVariable, Nullness>,
  ): State<Nullness> {
    val seen = referencedVariables(root)
    seen.removeAll(storeContents.keys)
    if (root.rootNode.lang != JavaLanguage.INSTANCE) {
      // In Java, captured variables are effectively final; otherwise, exclude non-final variables
      seen.removeIf { !it.hasModifierProperty(PsiModifier.FINAL) }
    }
    if (seen.isEmpty()) return State.withStore(storeContents) // no captured variables

    // Node to query analysis results at, which is the enclosing object literal or lambda
    var query: UExpression? =
      when (root) {
        is CfgRoot.Method -> root.rootNode.getParentOfType<UObjectLiteralExpression>()
        is CfgRoot.Lambda -> root.rootNode
      }
    // Analysis root for `query`, which we'll search for by walking query's parents
    var queryRoot: UElement? = query
    while (seen.isNotEmpty() && query != null && queryRoot != null) {
      queryRoot = queryRoot.uastParent
      if (queryRoot is UMethod) {
        val cfgRoot = CfgRoot.of(queryRoot)
        dataflowResult(cfgRoot)[query].store.addAvailableKeys(storeContents, seen)
        // Continue with enclosing object literal if any
        query = queryRoot.getParentOfType<UObjectLiteralExpression>()
        queryRoot = query
      } else if (queryRoot is ULambdaExpression) {
        val cfgRoot = CfgRoot.of(queryRoot)
        dataflowResult(cfgRoot)[query].store.addAvailableKeys(storeContents, seen)
        query = queryRoot
      }
    }
    return State.withStore(storeContents)
  }

  private fun referencedVariables(root: CfgRoot): MutableSet<PsiVariable> {
    val seen = mutableSetOf<PsiVariable>()
    root.rootNode.accept(
      object : AbstractUastVisitor() {
        override fun visitMethod(node: UMethod): Boolean =
          node != root.rootNode // don't visit nested methods

        override fun visitClass(node: UClass): Boolean = true // don't visit nested classes

        override fun visitLambdaExpression(node: ULambdaExpression): Boolean =
          node != root.rootNode // don't visit nested lambdas

        override fun visitObjectLiteralExpression(node: UObjectLiteralExpression): Boolean {
          node.receiver?.accept(this)
          node.valueArguments.acceptList(this)
          return true // don't visit object literal body
        }

        override fun visitSimpleNameReferenceExpression(
          node: USimpleNameReferenceExpression
        ): Boolean {
          val referee = node.resolve()
          if (referee is PsiLocalVariable || referee is PsiParameter) {
            seen += referee as PsiVariable
          }
          return super.visitSimpleNameReferenceExpression(node)
        }
      }
    )
    return seen
  }

  private fun Tuple<PsiVariable, Nullness>.addAvailableKeys(
    dest: MutableMap<PsiVariable, Nullness>,
    wanted: MutableSet<PsiVariable>,
  ) {
    val iter = wanted.iterator()
    while (iter.hasNext()) {
      val storeKey = iter.next()
      this[storeKey]?.let {
        dest.putIfAbsent(storeKey, it)
        iter.remove()
      }
    }
  }
}

/** Transfer function for tracking reference [Nullness] similar to how Kotlin would. */
private class NullnessTransfer(
  /** The [CfgRoot] of what's being analyzed. */
  private val cfgRoot: CfgRoot,
  /** Produces interprocedural analysis result of the given lambda as needed by [resultNullness]. */
  private val analyzeLambda: (ULambdaExpression) -> State<Nullness>,
  /**
   * Analysis function that given a call site and current analysis state returns the callee's return
   * value [Nullness] or `null` if interprocedural analysis is not supported for this call.
   */
  private val analyzeCallee: (UCallExpression, State<Nullness>) -> Nullness?,
) : UTransferFunction<State<Nullness>> {
  private val root: UElement
    get() = cfgRoot.rootNode

  private val cfg: Cfg by lazy { Cfg.create(cfgRoot) }
  private val otherCfgs = HashMap<UElement, Cfg>()

  private fun cfgForMethod(uMethod: UMethod): Cfg =
    if (uMethod == root) {
      cfg
    } else {
      otherCfgs.getOrPut(uMethod) { Cfg.create(CfgRoot.of(uMethod)) }
    }

  private fun enclosingCfg(node: UElement): Cfg? {
    val enclosing =
      node.getParentOfType(strict = true, UMethod::class.java, ULambdaExpression::class.java)
        ?: return null
    if (enclosing == root) return cfg
    return otherCfgs.getOrPut(enclosing) {
      when (enclosing) {
        is UMethod -> Cfg.create(CfgRoot.of(enclosing))
        is ULambdaExpression -> Cfg.create(CfgRoot.of(enclosing))
        else -> error("Unexpected CFG root: $enclosing")
      }
    }
  }

  override val bottom: State<Nullness>
    get() = NullnessAnalysis.BOTTOM

  private val javaLangIterable: PsiClass by lazy {
    PsiType.getTypeByName(
        "java.lang.Iterable",
        root.sourcePsi?.project!!,
        root.sourcePsi?.resolveScope!!,
      )
      .resolve()!!
  }
  private val throwableType: PsiClassType by lazy {
    PsiType.getJavaLangThrowable(root.sourcePsi?.manager!!, root.sourcePsi?.resolveScope!!)
  }

  override fun visitLocalVariable(
    node: ULocalVariable,
    data: TransferInput<State<Nullness>>,
  ): TransferResult<State<Nullness>> {
    val state = data.value()
    val value = node.uastInitializer?.let { state.value[it] } ?: Nullness.BOTTOM
    @Suppress("DEPRECATION") // .psi gives us a PsiLocalVariable
    return TransferResult.normal(State(state.value, state.store.withMapping(node.psi, value)))
  }

  @Suppress("DEPRECATION") // .psi gives us a PsiParameter
  override fun visitParameter(
    node: UParameter,
    data: TransferInput<State<Nullness>>,
  ): TransferResult<State<Nullness>> {
    val state = data.value()
    val value =
      when (val parent = node.uastParent) {
        is UMethod,
        is ULambdaExpression -> {
          check(state.store.containsKey(node.psi)) {
            "Method & lambda parameters should be in initial state, but $node missing: $data"
          }
          return super.visitParameter(node, data)
        }
        is UForEachExpression -> {
          parent.iteratedValue.resultNullness(
            state,
            analyzeLambda,
            javaLangIterable.typeParameters[0],
          )
            ?: run {
              val iteratedType =
                when (val iterableType = parent.iteratedValue.getExpressionType()) {
                  is PsiArrayType -> iterableType.componentType
                  is PsiClassType ->
                    extractIterableTypeParameter(iterableType, /* eraseTypeParameter= */ false)
                  else -> null // Shouldn't get here but let's assume non-null otherwise
                }
              nullnessFromAnnotation(iteratedType)
            }
        }
        else -> Nullness.NONULL // most parameters are non-null, e.g., caught exceptions
      }
    return TransferResult.normal(State(state.value, state.store.withMapping(node.psi, value)))
  }

  override fun visitArrayAccessExpression(
    node: UArrayAccessExpression,
    data: TransferInput<State<Nullness>>,
  ): TransferResult<State<Nullness>> {
    val state = data.value()
    val value =
      node.resultNullness(state, analyzeLambda) ?: nullnessFromAnnotation(node.getExpressionType())
    return state.toNormalResult(node, value, dereference = node.receiver)
  }

  override fun visitBinaryExpression(
    node: UBinaryExpression,
    data: TransferInput<State<Nullness>>,
  ): TransferResult<State<Nullness>> {
    fun identityEqualityStore(): State<Nullness> {
      val state = data.value()
      val impliedNullness =
        state.valueOrBottom(node.leftOperand) equate state.valueOrBottom(node.rightOperand)
      return if (impliedNullness == Nullness.BOTTOM) {
        bottom // Contradiction, so return bottom
      } else {
        val store =
          data.value().store +
            listOfNotNull(
              node.leftOperand.resolveTrackedVariable()?.let { it to impliedNullness },
              node.rightOperand.resolveTrackedVariable()?.let { it to impliedNullness },
            )
        State(state.value.withMapping(node, Nullness.NONULL), store)
      }
    }

    fun identityInequalityStore(): State<Nullness> {
      val state = data.value()
      var leftNullness = state.valueOrBottom(node.leftOperand)
      var rightNullness = state.valueOrBottom(node.rightOperand)
      if (leftNullness == Nullness.NULL) rightNullness = rightNullness equate Nullness.NONULL
      if (rightNullness == Nullness.NULL) leftNullness = leftNullness equate Nullness.NONULL

      return if (leftNullness == Nullness.BOTTOM || rightNullness == Nullness.BOTTOM) {
        bottom // Contradiction, so return bottom
      } else {
        val store =
          data.value().store +
            listOfNotNull(
              node.leftOperand.resolveTrackedVariable()?.let { it to leftNullness },
              node.rightOperand.resolveTrackedVariable()?.let { it to rightNullness },
            )
        State(state.value.withMapping(node, Nullness.NONULL), store)
      }
    }

    return when (node.operator) {
      UastBinaryOperator.IDENTITY_EQUALS ->
        TransferResult.conditional(identityEqualityStore(), identityInequalityStore())
      UastBinaryOperator.IDENTITY_NOT_EQUALS ->
        TransferResult.conditional(identityInequalityStore(), identityEqualityStore())
      else -> visitPolyadicExpression(node, data)
    }
  }

  override fun visitParenthesizedExpression(
    node: UParenthesizedExpression,
    data: TransferInput<State<Nullness>>,
  ): TransferResult<State<Nullness>> =
    if (data.isConditional) {
      val trueState = data.value(condition = true)
      val falseState = data.value(condition = false)
      TransferResult.conditional(
        trueValue =
          State(
            trueState.value.withMapping(node, trueState.valueOrBottom(node.expression)),
            trueState.store,
          ),
        falseValue =
          State(
            falseState.value.withMapping(node, falseState.valueOrBottom(node.expression)),
            falseState.store,
          ),
        exceptionalValues = mapOf(throwableType to data.value()),
      )
    } else {
      val state = data.value()
      state.toNormalResult(node, state.valueOrBottom(node.expression))
    }

  override fun visitBinaryExpressionWithType(
    node: UBinaryExpressionWithType,
    data: TransferInput<State<Nullness>>,
  ): TransferResult<State<Nullness>> {
    return when {
      node.operationKind == UastBinaryExpressionWithTypeKind.InstanceCheck.INSTANCE ->
        // `instanceof` can only be `true` for non-null values
        TransferResult.conditional(
          trueValue =
            State(
              data.value(condition = true).value.withMapping(node, Nullness.NONULL),
              data.value(condition = true).store +
                listOfNotNull(node.operand.resolveTrackedVariable()?.let { it to Nullness.NONULL }),
            ),
          falseValue =
            State(
              data.value(condition = false).value.withMapping(node, Nullness.NONULL),
              data.value(condition = false).store,
            ),
          exceptionalValues = mapOf(throwableType to data.value()),
        )
      node.operationKind is UastBinaryExpressionWithTypeKind.TypeCast -> {
        val state = data.value()
        val value =
          if (node.type is PsiPrimitiveType) {
            Nullness.NONULL
          } else {
            val operandNullness = state.valueOrBottom(node.operand)
            val castTypeNullness = nullnessFromAnnotation(node.type)
            when {
              castTypeNullness == Nullness.NULLABLE -> Nullness.NULLABLE
              indicatesNonNull(node.type) -> Nullness.NONULL
              operandNullness != Nullness.BOTTOM -> operandNullness
              else -> castTypeNullness
            }
          }
        state.toNormalResult(
          node,
          value,
          dereference = runIf(node.type is PsiPrimitiveType) { node.operand },
        )
      }
      else -> super.visitBinaryExpressionWithType(node, data)
    }
  }

  override fun visitPolyadicExpression(
    node: UPolyadicExpression,
    data: TransferInput<State<Nullness>>,
  ): TransferResult<State<Nullness>> {
    return when {
      node.operator == UastBinaryOperator.ASSIGN -> {
        val state = data.value()
        val lastOperand =
          node.operands.lastOrNull() ?: return state.toNormalResult(node, Nullness.BOTTOM)
        val value = state.valueOrBottom(lastOperand)
        state.toNormalResult(node, value) {
          this +
            node.operands.dropLast(1).mapNotNull { operand ->
              operand.resolveTrackedVariable()?.let { it to value }
            }
        }
      }
      data.isConditional && node.getExpressionType() == PsiTypes.booleanType() -> {
        // Other operators return nonnull values, but preserve conditional results for Booleans
        TransferResult.conditional(
          trueValue =
            State(
              data.value(condition = true).value.withMapping(node, Nullness.NONULL),
              data.value(condition = true).store,
            ),
          falseValue =
            State(
              data.value(condition = false).value.withMapping(node, Nullness.NONULL),
              data.value(condition = false).store,
            ),
          exceptionalValues = mapOf(throwableType to data.value()),
        )
      }
      else -> {
        // Other operators return nonnull values
        // TODO(b/308816245): consider lhs of compound assignments non-null
        data.value().toNormalResult(node, Nullness.NONULL)
      }
    }
  }

  override fun visitUnaryExpression(node: UUnaryExpression, data: TransferInput<State<Nullness>>) =
    if (node.operator == UastPrefixOperator.LOGICAL_NOT && data.isConditional) {
      // Flip conditional stores; result is always non-null boolean
      TransferResult.conditional(
        trueValue =
          State(
            data.value(condition = false).value.withMapping(node, Nullness.NONULL),
            data.value(condition = false).store,
          ),
        falseValue =
          State(
            data.value(condition = true).value.withMapping(node, Nullness.NONULL),
            data.value(condition = true).store,
          ),
        exceptionalValues = mapOf(throwableType to data.value()),
      )
    } else {
      data.value().toNormalResult(node, Nullness.NONULL)
    }

  override fun visitIfExpression(
    node: UIfExpression,
    data: TransferInput<State<Nullness>>,
  ): TransferResult<State<Nullness>> {
    val state = data.value()
    val value =
      if (node.isTernary) {
        state
          .valueOrBottom(node.thenExpression ?: node.condition)
          .join(state.valueOrBottom(node.elseExpression ?: node.condition))
      } else {
        Nullness.BOTTOM
      }
    return data.value().toNormalResult(node, value)
  }

  override fun visitLiteralExpression(
    node: ULiteralExpression,
    data: TransferInput<State<Nullness>>,
  ) = data.value().toNormalResult(node, if (node.isNull) Nullness.NULL else Nullness.NONULL)

  override fun visitClassLiteralExpression(
    node: UClassLiteralExpression,
    data: TransferInput<State<Nullness>>,
  ) = data.value().toNormalResult(node, Nullness.NONULL)

  override fun visitLambdaExpression(
    node: ULambdaExpression,
    data: TransferInput<State<Nullness>>,
  ): TransferResult<State<Nullness>> = data.value().toNormalResult(node, Nullness.NONULL)

  override fun visitCallableReferenceExpression(
    node: UCallableReferenceExpression,
    data: TransferInput<State<Nullness>>,
  ) =
    // Callable references are non-null (like lambdas) but implicitly dereference any qualifier
    data.value().toNormalResult(node, Nullness.NONULL, dereference = node.qualifierExpression)

  override fun visitThisExpression(node: UThisExpression, data: TransferInput<State<Nullness>>) =
    // `this` references are non-null
    data.value().toNormalResult(node, Nullness.NONULL)

  override fun visitSuperExpression(node: USuperExpression, data: TransferInput<State<Nullness>>) =
    // `super` like `this` references are non-null
    data.value().toNormalResult(node, Nullness.NONULL)

  override fun visitCallExpression(
    node: UCallExpression,
    data: TransferInput<State<Nullness>>,
  ): TransferResult<State<Nullness>> {
    if (
      node.kind == UastCallKind.CONSTRUCTOR_CALL ||
        node.kind == UastCallKind.NEW_ARRAY_WITH_DIMENSIONS ||
        node.kind == UastCallKind.NEW_ARRAY_WITH_INITIALIZER ||
        node.kind == UastCallKind.NESTED_ARRAY_INITIALIZER
    ) {
      // New objects are non-null (including arrays)
      return data.value().toNormalResult(node, Nullness.NONULL)
    }

    val state = data.value()
    val value =
      analyzeCallee(node, state)
        ?: demandDrivenStrictAndroidStubNullness(node)
        ?: node.resultNullness(state, analyzeLambda)
        ?: nullnessFromAnnotation(node.getExpressionType(), node.resolve())
    // TODO(b/308816245): havoc fields once we track them
    return state.toNormalResult(node, value, dereference = node.receiver)
  }

  /**
   * Treats unannotated Android SDK lookup calls as [Nullness.NULLABLE] only when a downstream null
   * check, nullable wrapper, `@Nullable` contract, or private helper demands nullability, keeping
   * unguarded view-inflation bindings and unannotated fields non-null.
   */
  private fun demandDrivenStrictAndroidStubNullness(node: UCallExpression): Nullness? {
    val callee = node.resolve() ?: return null
    if (!isStrictAndroidStubMethod(callee)) return null
    return runIf(expressionHasDownstreamNullDemand(node)) { Nullness.NULLABLE }
  }

  private fun expressionHasDownstreamNullDemand(
    exprNode: UExpression,
    visitedVars: Set<PsiVariable> = emptySet(),
    visitedMethods: Set<PsiMethod> = emptySet(),
  ): Boolean {
    var topExpr: UExpression = exprNode
    while (true) {
      val parent = topExpr.uastParent
      topExpr =
        when {
          parent is UQualifiedReferenceExpression && parent.selector == topExpr -> parent
          parent is UParenthesizedExpression -> parent
          parent is UBinaryExpressionWithType &&
            parent.operationKind is UastBinaryExpressionWithTypeKind.TypeCast &&
            parent.type !is PsiPrimitiveType &&
            !indicatesNonNull(parent.type) -> parent
          parent is UIfExpression &&
            parent.isTernary &&
            (parent.thenExpression == topExpr || parent.elseExpression == topExpr) -> parent
          else -> break
        }
    }

    val consumer = topExpr.uastParent
    if (isDirectNullCheckParent(topExpr, consumer)) {
      return true
    }
    if (consumer is UCallExpression) {
      val callee = consumer.resolve()
      val param = consumer.getParameterForArgument(topExpr)
      if (callee != null && param != null && !param.isVarArgs && !isExplicitlyNonNull(param)) {
        val psiCall =
          (consumer.sourcePsi as? PsiCall)
            ?: ((consumer.sourcePsi?.parent as? PsiAnonymousClass)?.parent as? PsiCall)
        val substitutedParamType =
          psiCall?.resolveMethodGenerics()?.substitutor?.substitute(param.type)
        if (
          hasNullableAnno(param) ||
            indicatesNullable(param.typeElement) ||
            indicatesNullable(param.type) ||
            indicatesNullable(substitutedParamType)
        ) {
          return true
        }
        if (
          isAnalyzablePrivateMethod(callee) && callee !in visitedMethods && param !in visitedVars
        ) {
          val uCallee = callee.toUElementOfType<UMethod>()
          if (uCallee != null && uCallee.uastBody != null) {
            val calleeCfg = cfgForMethod(uCallee)
            if (
              hasReachableNullDemandFromCfgWorklist(
                targetCfg = calleeCfg,
                initialNodes = listOf(calleeCfg.entryNode),
                targetVar = param,
                visitedVars = visitedVars + param,
                visitedMethods = visitedMethods + callee,
              )
            ) {
              return true
            }
          }
        }
      }
    }
    if (consumer is ULocalVariable && consumer.uastInitializer == topExpr) {
      @Suppress("DEPRECATION") val targetVar: PsiLocalVariable = consumer.psi
      if (isExplicitlyNonNull(targetVar) || targetVar in visitedVars) {
        return false
      }
      return hasNullableAnno(targetVar) ||
        indicatesNullable(targetVar.typeElement) ||
        indicatesNullable(targetVar.type) ||
        hasReachableNullDemandInCfg(
          startNode = consumer,
          targetVar = targetVar,
          visitedVars = visitedVars + targetVar,
          visitedMethods = visitedMethods,
        )
    }
    if (consumer is UField && consumer.uastInitializer == topExpr) {
      val psiField = consumer.javaPsi as? PsiField ?: return false
      if (isExplicitlyNonNull(psiField)) {
        return false
      }
      return hasNullableAnno(psiField) ||
        indicatesNullable(psiField.typeElement) ||
        indicatesNullable(psiField.type)
    }
    if (consumer is ULambdaExpression && consumer.body == topExpr) {
      return lambdaAcceptsNullableReturn(consumer)
    }
    if (consumer is UReturnExpression && consumer.returnExpression == topExpr) {
      val callable = consumer.jumpTarget ?: enclosingCallable(exprNode) ?: return false
      if (callable is ULambdaExpression) {
        return lambdaAcceptsNullableReturn(callable)
      }
      val targetMethod = callable as? UMethod ?: return false
      val psiMethod = targetMethod.javaPsi
      if (isExplicitlyNonNullReturn(psiMethod)) {
        return false
      }
      if (
        hasNullableAnno(psiMethod) ||
          indicatesNullable(psiMethod.returnTypeElement) ||
          indicatesNullable(psiMethod.returnType) ||
          overridesNullableReturn(psiMethod)
      ) {
        return true
      }
      if (isAnalyzablePrivateMethod(psiMethod) && psiMethod !in visitedMethods) {
        val enclosingClass = topLevelUClass(exprNode) ?: return false
        var callerChecksNull = false
        enclosingClass.accept(
          object : AbstractUastVisitor() {
            override fun visitElement(node: UElement): Boolean = callerChecksNull

            override fun visitCallExpression(node: UCallExpression): Boolean {
              if (callerChecksNull) return true
              if (
                node.resolve() == psiMethod &&
                  expressionHasDownstreamNullDemand(node, visitedVars, visitedMethods + psiMethod)
              ) {
                callerChecksNull = true
                return true
              }
              return super.visitCallExpression(node)
            }
          }
        )
        return callerChecksNull
      }
      return false
    }
    if (
      consumer is UPolyadicExpression &&
        consumer.operator == UastBinaryOperator.ASSIGN &&
        consumer.operands.lastOrNull() == topExpr
    ) {
      var assignTop: UExpression = consumer
      while (assignTop.uastParent is UParenthesizedExpression) {
        assignTop = assignTop.uastParent as UParenthesizedExpression
      }
      if (isDirectNullCheckParent(assignTop, assignTop.uastParent)) {
        return true
      }
      val lhsOperands = consumer.operands.dropLast(1)
      if (
        lhsOperands
          .mapNotNull { it.resolveTrackedVariable() }
          .filterNot { isExplicitlyNonNull(it) || it in visitedVars }
          .any {
            hasNullableAnno(it) ||
              indicatesNullable(it.typeElement) ||
              indicatesNullable(it.type) ||
              hasReachableNullDemandInCfg(
                startNode = consumer,
                targetVar = it,
                visitedVars = visitedVars + it,
                visitedMethods = visitedMethods,
              )
          }
      ) {
        return true
      }
      return lhsOperands
        .mapNotNull { (it as? UReferenceExpression)?.resolve() as? PsiField }
        .filterNot { isExplicitlyNonNull(it) }
        .any { psiField ->
          hasNullableAnno(psiField) ||
            indicatesNullable(psiField.typeElement) ||
            indicatesNullable(psiField.type)
        }
    }
    return false
  }

  private fun enclosingCallable(node: UElement): UElement? {
    var curr = node.uastParent
    while (curr != null) {
      if (curr is UMethod || curr is ULambdaExpression) {
        return curr
      }
      curr = curr.uastParent
    }
    return null
  }

  private fun lambdaAcceptsNullableReturn(lambda: ULambdaExpression): Boolean {
    val psiLambda = lambda.sourcePsi as? PsiLambdaExpression ?: return false
    val sam =
      LambdaUtil.getFunctionalInterfaceMethod(psiLambda.functionalInterfaceType) ?: return false
    val substitutedReturnType = LambdaUtil.getFunctionalInterfaceReturnType(psiLambda)
    return hasNullableAnno(sam) ||
      indicatesNullable(sam.returnTypeElement) ||
      indicatesNullable(sam.returnType) ||
      indicatesNullable(substitutedReturnType)
  }

  private fun hasReachableNullDemandInCfg(
    startNode: UElement,
    targetVar: PsiVariable,
    visitedVars: Set<PsiVariable>,
    visitedMethods: Set<PsiMethod>,
  ): Boolean {
    val targetCfg = enclosingCfg(startNode) ?: return false
    return hasReachableNullDemandFromCfgWorklist(
      targetCfg = targetCfg,
      initialNodes = targetCfg.normalSuccessors(startNode),
      targetVar = targetVar,
      visitedVars = visitedVars,
      visitedMethods = visitedMethods,
    )
  }

  private fun hasReachableNullDemandFromCfgWorklist(
    targetCfg: Cfg,
    initialNodes: Collection<UElement>,
    targetVar: PsiVariable,
    visitedVars: Set<PsiVariable>,
    visitedMethods: Set<PsiMethod>,
  ): Boolean {
    val visited = HashSet<UElement>()
    val worklist = ArrayDeque<UElement>(initialNodes)
    while (worklist.isNotEmpty()) {
      val curr = worklist.removeFirst()
      if (!visited.add(curr)) continue
      if (nodeDemandsVariableNullable(curr, targetVar, visitedVars, visitedMethods)) {
        return true
      }
      val overwritesTarget =
        curr is UPolyadicExpression &&
          curr.operator == UastBinaryOperator.ASSIGN &&
          curr.operands.dropLast(1).any { it.resolveTrackedVariable() == targetVar }
      if (!overwritesTarget && !nodeDereferencesVariable(curr, targetVar)) {
        worklist.addAll(targetCfg.normalSuccessors(curr))
      }
    }
    return false
  }

  private fun nodeDemandsVariableNullable(
    node: UElement,
    targetVar: PsiVariable,
    visitedVars: Set<PsiVariable>,
    visitedMethods: Set<PsiMethod>,
  ): Boolean =
    when {
      node is UReferenceExpression && node.resolve() == targetVar ->
        expressionHasDownstreamNullDemand(node, visitedVars, visitedMethods)
      node is UBinaryExpression &&
        (node.operator == UastBinaryOperator.IDENTITY_EQUALS ||
          node.operator == UastBinaryOperator.IDENTITY_NOT_EQUALS) ->
        (node.leftOperand.isNullLiteral() &&
          node.rightOperand.unwrappedTrackedVariable() == targetVar) ||
          (node.rightOperand.isNullLiteral() &&
            node.leftOperand.unwrappedTrackedVariable() == targetVar)
      node is UCallExpression && isNullAwareHelperCall(node) ->
        node.valueArguments.singleOrNull()?.unwrappedTrackedVariable() == targetVar
      node is ULambdaExpression || node is UObjectLiteralExpression -> {
        var found = false
        node.accept(
          object : AbstractUastVisitor() {
            override fun visitElement(node: UElement): Boolean = found

            override fun visitSimpleNameReferenceExpression(
              node: USimpleNameReferenceExpression
            ): Boolean {
              if (found) return true
              if (
                node.resolve() == targetVar &&
                  expressionHasDownstreamNullDemand(node, visitedVars, visitedMethods)
              ) {
                found = true
                return true
              }
              return super.visitSimpleNameReferenceExpression(node)
            }
          }
        )
        found
      }
      else -> false
    }

  override fun visitQualifiedReferenceExpression(
    node: UQualifiedReferenceExpression,
    data: TransferInput<State<Nullness>>,
  ): TransferResult<State<Nullness>> =
    if (node.selector is UCallExpression) {
      // foo.bar() is modeled as a UQualifiedReferenceExpression containing a UCallExpression,
      // so pass through the UCallExpression's result instead of recalculating it
      val state = data.value()
      state.toNormalResult(node, state.valueOrBottom(node.selector))
    } else {
      visitReferenceExpression(node, data)
    }

  override fun visitReferenceExpression(
    node: UReferenceExpression,
    data: TransferInput<State<Nullness>>,
  ): TransferResult<State<Nullness>> {
    val state = data.value()
    val referee = node.resolve() as? PsiModifierListOwner
    // TODO(b/308816245): track fields
    val value = state.store[referee] ?: nullnessFromAnnotation(node.getExpressionType(), referee)
    return state.toNormalResult(
      node,
      value,
      dereference = (node as? UQualifiedReferenceExpression)?.receiver,
    )
  }

  override fun visitCfgForeachIteratedExpression(
    node: CfgForeachIteratedExpression,
    data: TransferInput<State<Nullness>>,
  ) = data.value().toNormalResult(node, Nullness.NONULL, dereference = node.wrappedExpression)

  override fun visitCfgSwitchBranchExpression(
    node: CfgSwitchBranchExpression,
    data: TransferInput<State<Nullness>>,
  ) = data.value().toNormalResult(node, Nullness.NONULL, dereference = node.wrappedExpression)

  /**
   * Returns a [TransferResult.normal] based on this one with the given [State.value] that
   * optionally reflects a dereference of the given node.
   *
   * If [dereference] resolves to a variable, that variable is marked as non-null in the result;
   * otherwise, the given store is included in the result as-is.
   */
  private fun State<Nullness>.toNormalResult(
    node: UExpression,
    newValue: Nullness,
    dereference: UExpression?,
  ) =
    toNormalResult(node, newValue) {
      val dereferenced = dereference?.resolveTrackedVariable()
      if (dereferenced != null) withMapping(dereferenced, Nullness.NONULL) else this
    }

  private inline fun State<Nullness>.toNormalResult(
    node: UExpression,
    newValue: Nullness,
    updateStore: Tuple<PsiVariable, Nullness>.() -> Tuple<PsiVariable, Nullness> = { store },
  ) =
    TransferResult.normal(
      State(value.withMapping(node, newValue), store.updateStore()),
      exceptionalValues = mapOf(throwableType to this),
    )

  private companion object {
    fun State<Nullness>.valueOrBottom(expression: UExpression): Nullness =
      value[expression] ?: Nullness.BOTTOM

    fun UExpression.resolveTrackedVariable(): PsiVariable? = accept(ResolveTrackedVariable, Unit)

    fun UExpression.isNullLiteral(): Boolean =
      when (this) {
        is ULiteralExpression -> isNull
        is UParenthesizedExpression -> expression.isNullLiteral()
        else -> false
      }

    fun isNullAwareHelperCall(call: UCallExpression): Boolean {
      if (call.valueArgumentCount != 1) return false
      val callee = call.resolve() ?: return false
      return isNullAwareHelperMethod(callee)
    }

    fun isDirectNullCheckParent(expr: UExpression, parent: UElement?): Boolean =
      when {
        parent is UBinaryExpression &&
          (parent.operator == UastBinaryOperator.IDENTITY_EQUALS ||
            parent.operator == UastBinaryOperator.IDENTITY_NOT_EQUALS) -> {
          val other = if (parent.leftOperand == expr) parent.rightOperand else parent.leftOperand
          other.isNullLiteral()
        }
        parent is UCallExpression && isNullAwareHelperCall(parent) ->
          parent.valueArguments.singleOrNull() == expr
        else -> false
      }

    fun UExpression.unwrappedTrackedVariable(): PsiVariable? =
      when (this) {
        is UParenthesizedExpression -> expression.unwrappedTrackedVariable()
        is UBinaryExpressionWithType ->
          if (
            operationKind is UastBinaryExpressionWithTypeKind.TypeCast &&
              type !is PsiPrimitiveType &&
              nullnessFromAnnotation(type) != Nullness.NULLABLE
          ) {
            operand.unwrappedTrackedVariable()
          } else {
            null
          }
        else -> resolveTrackedVariable()
      }

    fun nodeDereferencesVariable(node: UElement, targetVar: PsiVariable): Boolean =
      when {
        node is UArrayAccessExpression -> node.receiver.unwrappedTrackedVariable() == targetVar
        node is UCallExpression ->
          node.receiver?.unwrappedTrackedVariable() == targetVar ||
            node.valueArguments.any { arg ->
              arg.unwrappedTrackedVariable() == targetVar &&
                node.getParameterForArgument(arg)?.let(::requiresNonNullArgument) == true
            }
        node is UQualifiedReferenceExpression && node.selector !is UCallExpression ->
          node.receiver.unwrappedTrackedVariable() == targetVar
        node is UCallableReferenceExpression ->
          node.qualifierExpression?.unwrappedTrackedVariable() == targetVar
        node is UBinaryExpressionWithType &&
          node.operationKind is UastBinaryExpressionWithTypeKind.TypeCast &&
          node.type is PsiPrimitiveType -> node.operand.unwrappedTrackedVariable() == targetVar
        node is CfgForeachIteratedExpression ->
          node.wrappedExpression.unwrappedTrackedVariable() == targetVar
        node is CfgSwitchBranchExpression ->
          node.wrappedExpression.unwrappedTrackedVariable() == targetVar
        else -> false
      }

    fun requiresNonNullArgument(param: PsiParameter): Boolean =
      isExplicitlyNonNull(param) ||
        (param.isInNullMarkedScope() &&
          !hasNullableAnno(param) &&
          !indicatesNullable(param.typeElement) &&
          !indicatesNullable(param.type))

    fun topLevelUClass(node: UElement): UClass? {
      var curr = node.getParentOfType<UClass>()
      while (true) {
        val outer = curr?.getParentOfType<UClass>() ?: break
        curr = outer
      }
      return curr
    }
  }

  private object ResolveTrackedVariable : UastTypedVisitor<Unit, PsiVariable?> {
    override fun visitElement(node: UElement, data: Unit): PsiVariable? = null

    override fun visitReferenceExpression(node: UReferenceExpression, data: Unit): PsiVariable? {
      val result = node.resolve() as? PsiVariable
      return if (result is PsiField) null else result // ignore fields
    }

    override fun visitParenthesizedExpression(
      node: UParenthesizedExpression,
      data: Unit,
    ): PsiVariable? = node.expression.accept(this, data)

    override fun visitBinaryExpression(node: UBinaryExpression, data: Unit): PsiVariable? =
      runIf(node.operator is UastBinaryOperator.AssignOperator) {
        // TODO(b/308816245): extract all assigned variables, or track equal variables together
        node.leftOperand.resolveTrackedVariable()
          ?: runIf(node.operator == UastBinaryOperator.ASSIGN) {
            node.rightOperand.resolveTrackedVariable()
          }
      }
  }
}

private fun isExplicitlyNonNull(variable: PsiVariable): Boolean =
  indicatesNonNull(variable.modifierList, useTypeAnnos = variable.type !is PsiArrayType) ||
    indicatesNonNull(variable.typeElement) ||
    indicatesNonNull(variable.type)

private fun isAnalyzablePrivateMethod(psiMethod: PsiMethod): Boolean =
  psiMethod !is PsiCompiledElement &&
    !psiMethod.isConstructor &&
    psiMethod.hasModifierProperty(PsiModifier.PRIVATE) &&
    !psiMethod.hasTypeParameters() &&
    psiMethod.parameterList.parameters.none {
      (it.type as? PsiClassType)?.resolve() is PsiTypeParameter
    }

private fun PsiClass?.isInheritorOrSelf(fqn: String): Boolean =
  this != null && (qualifiedName == fqn || InheritanceUtil.isInheritor(this, fqn))

fun isStrictAndroidStubMethod(callee: PsiMethod): Boolean {
  if (
    callee.returnType is PsiPrimitiveType ||
      !callee.isJavaDeclaration() ||
      isExplicitlyNonNullReturn(callee) ||
      (callee.isInNullMarkedScope() &&
        !hasNullableAnno(callee) &&
        !indicatesNullable(callee.returnTypeElement) &&
        !indicatesNullable(callee.returnType))
  ) {
    return false
  }
  val paramCount = callee.parameterList.parametersCount
  val owner = callee.containingClass ?: return false
  return when (callee.name) {
    "findViewById" ->
      paramCount == 1 &&
        (owner.isInheritorOrSelf("android.view.View") ||
          owner.isInheritorOrSelf("android.view.Window") ||
          owner.isInheritorOrSelf("android.app.Activity") ||
          owner.isInheritorOrSelf("android.app.Dialog"))
    "findViewWithTag" -> paramCount == 1 && owner.isInheritorOrSelf("android.view.View")
    "getParent" ->
      paramCount == 0 &&
        (owner.isInheritorOrSelf("android.view.View") ||
          owner.isInheritorOrSelf("android.app.Activity"))
    "getLayoutParams" -> paramCount == 0 && owner.isInheritorOrSelf("android.view.View")
    "getTag" ->
      (paramCount in 0..1 && owner.isInheritorOrSelf("android.view.View")) ||
        (paramCount == 0 &&
          (owner.isInheritorOrSelf("android.app.Fragment") ||
            owner.isInheritorOrSelf("androidx.fragment.app.Fragment") ||
            owner.isInheritorOrSelf("android.support.v4.app.Fragment")))
    "findFragmentById",
    "findFragmentByTag" ->
      paramCount == 1 &&
        (owner.isInheritorOrSelf("android.app.FragmentManager") ||
          owner.isInheritorOrSelf("androidx.fragment.app.FragmentManager") ||
          owner.isInheritorOrSelf("android.support.v4.app.FragmentManager"))
    "getSystemService" -> paramCount == 1 && owner.isInheritorOrSelf("android.content.Context")
    "getDrawable" ->
      (paramCount in 1..2 &&
        (owner.isInheritorOrSelf("android.content.res.Resources") ||
          owner.isInheritorOrSelf("android.content.Context"))) ||
        (paramCount == 0 && owner.isInheritorOrSelf("android.widget.ImageView"))
    "getString" ->
      paramCount == 1 &&
        (owner.isInheritorOrSelf("android.database.Cursor") ||
          owner.isInheritorOrSelf("android.os.BaseBundle") ||
          owner.isInheritorOrSelf("android.os.Bundle"))
    "getStringExtra" -> paramCount == 1 && owner.isInheritorOrSelf("android.content.Intent")
    "getExtras" -> paramCount == 0 && owner.isInheritorOrSelf("android.content.Intent")
    else -> false
  }
}
