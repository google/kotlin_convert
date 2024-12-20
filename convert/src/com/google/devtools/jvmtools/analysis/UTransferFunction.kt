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

package com.google.devtools.jvmtools.analysis

import com.google.common.annotations.VisibleForTesting
import com.google.errorprone.annotations.Immutable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UMethod

/** Result of a dataflow [analysis][analyze]. */
interface UDataflowResult<T> : UAnalysis<T> {
  /** Final analysis result at the exit node. */
  val finalResult: T
}

/** Context for [UTransferFunction] implementations to query analysis results. */
// Identical to UAnalysis, but can add additional query functions here
interface UDataflowContext<T : Value<T>> : UAnalysis<T>

/**
 * Shorthand for functions that construct [UTransferFunction]s for given root node (e.g., a method)
 * that can query the given context.
 */
typealias TransferFactory<T> = (UDataflowContext<T>) -> UTransferFunction<T>

/**
 * Analyzes this method with the [UTransferFunction] returned by [transferFactory].
 *
 * @param initialState initial analysis state to use as the entry node's initial "before" state.
 * @param transferFactory returns [UTransferFunction] for this method, which (awkwardly) allows the
 *   transfer function to receive a reference back to the running analysis to query results.
 */
fun <T : Value<T>> UMethod.analyze(
  initialState: T,
  transferFactory: TransferFactory<T>,
): UDataflowResult<T> =
  DataflowAnalysis(Cfg.create(this), transferFactory).also { it.runAnalysis(initialState) }

/**
 * Analyzes this [CfgRoot] with the [UTransferFunction] returned by [transferFactory].
 *
 * @param initialState initial analysis state to use as the entry node's initial "before" state.
 * @param transferFactory returns [UTransferFunction] for this root, which (awkwardly) allows the
 *   transfer function to receive a reference back to the running analysis to query results.
 */
fun <T : Value<T>> CfgRoot.analyze(
  initialState: T,
  transferFactory: TransferFactory<T>,
): UDataflowResult<T> =
  DataflowAnalysis(Cfg.create(this), transferFactory).also { it.runAnalysis(initialState) }

/**
 * Base class for dataflow transfer functions. Subclasses should override [CfgTypedVisitor] methods
 * for nodes they care about and derive [TransferResult]s from the given input state as needed.
 *
 * Use [toNormalResult] or [passthroughResult] to turn an input into [TransferResult] "unseen",
 * which the default implementations in this interface do for all nodes.
 *
 * @param T abstract values being tracked
 */
interface UTransferFunction<T : Value<T>> : CfgTypedVisitor<TransferInput<T>, TransferResult<T>> {
  /**
   * Whether this is a backwards transfer function, meaning the [TransferResult]s it returns are
   * immediately _prior to_ execution of the given node, given [TransferInput]s representing state
   * immediately following the node's execution (including from exceptional successors).
   *
   * Backwards transfer functions should nearly never return anything but [passthroughResult]s or
   * [TransferResult.normal] results with no exceptional values. [TransferInput.value] will however
   * allow querying input values separately by condition when visiting control flow branches.
   *
   * Returns `false` by default and should be overridden to return `true` to define backwards
   * transfer functions.
   */
  val isBackwards: Boolean
    get() = false

  /**
   * The concrete [T]'s most precise ("bottom") value, which will be used when no other information
   * is available (e.g., in [UAnalysis.get]).
   */
  val bottom: T

  /** Overridden to pass through the given [data] by default, joining any conditional inputs. */
  override fun visitElement(node: UElement, data: TransferInput<T>): TransferResult<T> =
    TransferResult.normal(data.value())

  /** Overridden to pass through the given [data] by default, including conditionals. */
  override fun visitExpression(node: UExpression, data: TransferInput<T>): TransferResult<T> =
    data.passthroughResult(node)
}

/**
 * Result of [transferring][UTransferFunction] over a node, which can be constructed as a [normal]
 * result, or [conditional] upon the node's Boolean value.
 *
 * This class is "write-only" for transfer functions: it defines `internal` functions to be used in
 * this package only.
 *
 * @param T abstract values being tracked
 * @see TransferInput
 */
@Immutable
sealed class TransferResult<T : Value<T>>(
  @Suppress("Immutable") // PsiType can't be annotated
  private val exceptionalValues: Map<PsiType, T>
) {
  /**
   * Returns the default result to be propagated except along labeled edges with matching
   * [Conditional] or [exceptionalValues].
   */
  internal abstract fun defaultResultValue(): T

  /**
   * Transforms this result to an input, optionally filtered by the given edge label.
   *
   * @param label [Cfg] edge label to filter by, `null` meaning *regular return*, i.e., as defined
   *   by [Cfg.successorsWithConditions]
   * @param default value to use for other conditions, typically, [UTransferFunction.bottom]
   */
  internal abstract fun toInput(label: CfgEdgeLabel?, default: T): TransferInput<T>

  /**
   * Returns a value for the given exception type.
   *
   * @return value for the mapping with most precise type or [defaultResultValue] if no such type
   */
  private fun exceptionalValue(thrownType: PsiType): T {
    var result: T? = null
    var found: PsiType? = null
    // Return most precise entry in exceptionalValues (note there can't be multiple such entries
    // if thrownType and all keys in exceptionalValues are subtypes of Throwable)
    for ((exception, value) in exceptionalValues) {
      if (found != null && exception.isAssignableFrom(found)) continue
      if (exception.isAssignableFrom(thrownType)) {
        result = value
        found = exception
      }
    }
    return result ?: defaultResultValue() // use regular return if no matching exception
  }

  internal fun toExceptionalInput(
    exceptionalLabel: CfgEdgeLabel.CfgExceptionalEdge,
    default: T,
  ): TransferInput<T> =
    TransferInput.Normal(
      exceptionalLabel.thrown
        .map { if (it != null) exceptionalValue(it) else defaultResultValue() }
        .reduceOrNull(Value<T>::join) ?: default
    )

  @VisibleForTesting
  internal class Normal<T : Value<T>>(val value: T, exceptionalValues: Map<PsiType, T>) :
    TransferResult<T>(exceptionalValues) {
    override fun defaultResultValue(): T = value

    override fun toInput(label: CfgEdgeLabel?, default: T): TransferInput<T> =
      when (label) {
        null -> TransferInput.Normal(value)
        CfgEdgeLabel.CfgConditionalEdge.TRUE -> TransferInput.Conditional(value, default)
        CfgEdgeLabel.CfgConditionalEdge.FALSE -> TransferInput.Conditional(default, value)
        is CfgEdgeLabel.CfgExceptionalEdge -> toExceptionalInput(label, default)
      }
  }

  @VisibleForTesting
  internal class Conditional<T : Value<T>>(
    val trueValue: T,
    val falseValue: T,
    exceptionalValues: Map<PsiType, T>,
  ) : TransferResult<T>(exceptionalValues) {
    override fun defaultResultValue(): T = trueValue join falseValue

    override fun toInput(label: CfgEdgeLabel?, default: T): TransferInput<T> =
      when (label) {
        null -> TransferInput.Conditional(trueValue, falseValue)
        CfgEdgeLabel.CfgConditionalEdge.TRUE -> TransferInput.Conditional(trueValue, default)
        CfgEdgeLabel.CfgConditionalEdge.FALSE -> TransferInput.Conditional(default, falseValue)
        is CfgEdgeLabel.CfgExceptionalEdge -> toExceptionalInput(label, default)
      }
  }

  companion object {
    /** Makes standard result that propagates the given [value] to all successor nodes. */
    fun <T : Value<T>> normal(
      value: T,
      exceptionalValues: Map<PsiType, T> = emptyMap(),
    ): TransferResult<T> = Normal(value, exceptionalValues)

    /**
     * Makes conditional result that should only be used for Boolean expression results. Propagates
     * [trueValue] and [falseValue] separately to successor nodes and only propagates [trueValue]
     * ([falseValue], respectively) to successors only reachable if the expression evaluates to
     * `true` (`false`, respectively).
     */
    fun <T : Value<T>> conditional(
      trueValue: T,
      falseValue: T,
      exceptionalValues: Map<PsiType, T> = emptyMap(),
    ): TransferResult<T> = Conditional(trueValue, falseValue, exceptionalValues)
  }
}

/**
 * Input to [transfer functions][UTransferFunction] that exposes incoming analysis [value]s.
 *
 * @param T abstract values being tracked
 * @see TransferInput
 */
@Immutable
sealed class TransferInput<T : Value<T>> {
  /**
   * Whether this is a conditional input, i.e., [value] may return different results depending on
   * the given condition.
   */
  abstract val isConditional: Boolean

  /**
   * Returns the incoming analysis value, optionally filtered by [condition].
   *
   * @param condition optionally limits result to the given condition, `null` meaning *no filter*,
   *   i.e., [Value.join]ed over all incoming values including through exceptional control flow.
   *   This parameter has no effect unless [isConditional] is `true`.
   */
  // TODO(kmb): provide a way to query incoming exceptional values separately
  abstract fun value(condition: Boolean? = null): T

  internal abstract fun merge(other: TransferInput<T>): TransferInput<T>

  internal abstract fun implies(other: TransferInput<T>): Boolean

  internal data class Normal<T : Value<T>>(val value: T) : TransferInput<T>() {
    override val isConditional: Boolean
      get() = false

    override fun value(condition: Boolean?): T = value

    override fun merge(other: TransferInput<T>): TransferInput<T> =
      if (other is Conditional) {
        Conditional(value join other.trueValue, value join other.falseValue)
      } else {
        Normal(value join other.value())
      }

    override fun implies(other: TransferInput<T>): Boolean {
      return if (other is Normal) value.implies(other.value) else false
    }
  }

  internal data class Conditional<T : Value<T>>(val trueValue: T, val falseValue: T) :
    TransferInput<T>() {
    override val isConditional: Boolean
      get() = true

    override fun value(condition: Boolean?): T =
      when (condition) {
        null -> trueValue join falseValue
        true -> trueValue
        false -> falseValue
      }

    override fun merge(other: TransferInput<T>): TransferInput<T> =
      if (other is Conditional) {
        Conditional(trueValue join other.trueValue, falseValue join other.falseValue)
      } else {
        val joined = other.value()
        Conditional(trueValue join joined, falseValue join joined)
      }

    override fun implies(other: TransferInput<T>): Boolean {
      return trueValue.implies(other.value(condition = true)) &&
        falseValue.implies(other.value(condition = false))
    }
  }
}

/** Returns [TransferResult.normal] result for this input, merging any conditional inputs. */
fun <T : Value<T>> TransferInput<T>.toNormalResult(): TransferResult<T> =
  TransferResult.normal(value())

/**
 * Derives [TransferResult] from this input for the given node, preserving conditional inputs if the
 * node is [PsiTypes.booleanType], i.e., can have conditional exits.
 */
fun <T : Value<T>> TransferInput<T>.passthroughResult(node: UExpression): TransferResult<T> =
  if (this is TransferInput.Conditional && node.getExpressionType() == PsiTypes.booleanType()) {
    TransferResult.conditional(trueValue, falseValue)
  } else {
    toNormalResult()
  }

/**
 * Implements dataflow analysis over a given CFG and transfer function.
 *
 * @param T abstract values being tracked
 */
internal class DataflowAnalysis<T : Value<T>>(cfg: Cfg, transferFactory: TransferFactory<T>) :
  UDataflowContext<T>, UDataflowResult<T> {
  private val before = mutableMapOf<UElement, TransferInput<T>>()
  private val after = mutableMapOf<UElement, TransferResult<T>>()

  // transferFactory should store this reference for later use or ignore it; calling methods on it
  // will likely fail as it usually requires `cfg`, which in turn requires `transfer.isBackwards`.
  // A better solution would be nice, but in practice this usually works.
  private val transfer: UTransferFunction<T> = transferFactory(this)
  val cfg: Cfg = if (transfer.isBackwards) cfg.reverse() else cfg

  override val finalResult: T
    get() = get(cfg.exitNode)

  fun runAnalysis(initialState: T) {
    val pending = mutableSetOf<UElement>()
    before[cfg.entryNode] = TransferInput.Normal(initialState)
    pending += cfg.entryNode
    while (pending.isNotEmpty()) {
      // Remove and transfer a node; add nodes to be visited as a result
      // TODO(kmb): implement efficient visitation order to minimize re-computations
      val node: UElement = pending.removeFirst()
      pending += transferOver(node)
    }
  }

  private fun transferOver(node: UElement): List<UElement> {
    val before = checkNotNull(before[node]) { "don't have any state for $node" }
    val after = node.accept(transfer, before)
    return updateAfter(node, after)
  }

  /**
   * Sets and propagates [node]'s given "after" [result].
   *
   * @return nodes to be (re-) visited because their "before" input changed.
   */
  private fun updateAfter(node: UElement, result: TransferResult<T>): List<UElement> {
    after[node] = result
    val toUpdate = mutableListOf<UElement>()
    for ((successor, condition) in cfg.successorsWithConditions(node)) {
      var newInput = result.toInput(condition, transfer.bottom)
      val previous = before.putIfAbsent(successor, newInput)
      if (previous != null) {
        newInput = previous.merge(newInput)
        before[successor] = newInput
      }
      if (previous == null || !newInput.implies(previous)) toUpdate += successor
    }
    return toUpdate
  }

  override fun get(node: UElement): T {
    return after[node]?.defaultResultValue() ?: transfer.bottom
  }

  override fun get(element: PsiElement): T {
    val nodes = cfg.sourceMapping[element]
    return nodes.mapNotNull { after[it]?.defaultResultValue() }.reduceOrNull(Value<T>::join)
      ?: transfer.bottom
  }

  override fun uastNodes(element: PsiElement): Collection<UElement> = cfg.sourceMapping[element]
}

/** Removes and returns the collection's first element (as returned by its [MutableIterator]). */
internal fun <T> MutableCollection<T>.removeFirst(): T {
  val iter = iterator()
  val result = iter.next()
  iter.remove()
  return result
}
