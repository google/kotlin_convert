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

/** Context for [UForwardTransfer] implementations to query analysis results. */
// Identical to UAnalysis, but can add additional query functions here
interface UDataflowContext<T : Value<T>> : UAnalysis<T>

/**
 * Shorthand for functions that construct [UForwardTransfer]s for given root node (e.g., a method)
 * that can query the given context.
 */
typealias ForwardTransferFactory<T> = (UDataflowContext<T>) -> UForwardTransfer<T>

/**
 * Analyzes this method with the [UForwardTransfer] returned by [transferFactory].
 *
 * @param transferFactory returns [UForwardTransfer] for this method, which (awkwardly) allows the
 *   transfer function to receive a reference back to the running analysis to query results.
 */
fun <T : Value<T>> UMethod.analyze(transferFactory: ForwardTransferFactory<T>): UAnalysis<T> =
  DataflowAnalysis(Cfg.create(this), transferFactory).also { it.runAnalysis() }

/**
 * Analyzes this [CfgRoot] with the [UForwardTransfer] returned by [transferFactory].
 *
 * @param transferFactory returns [UForwardTransfer] for this root, which (awkwardly) allows the
 *   transfer function to receive a reference back to the running analysis to query results.
 */
fun <T : Value<T>> CfgRoot.analyze(transferFactory: ForwardTransferFactory<T>): UAnalysis<T> =
  DataflowAnalysis(Cfg.create(this), transferFactory).also { it.runAnalysis() }

/**
 * Base class for dataflow transfer functions. Subclasses should override [CfgTypedVisitor] methods
 * for nodes they care about and derive [TransferInput]s from the given input state as needed.
 *
 * Use [toNormalResult] or [passthroughResult] to turn an input into [TransferResult] "unseen",
 * which the default implementations in this interface do for all nodes.
 *
 * @param T abstract values being tracked
 */
interface UForwardTransfer<T : Value<T>> : CfgTypedVisitor<TransferInput<T>, TransferResult<T>> {
  /**
   * The concrete [T]'s most precise ("bottom") value, which will be used when no other information
   * is available (e.g., in [UAnalysis.get]).
   */
  val bottom: T

  /** Initial analysis state to use as the entry node's initial "before" state. */
  // TODO(kmb): consider passing CFG's root node to allow reuse
  val initialState: T

  /** Overridden to pass through the given [data] by default, joining any conditional inputs. */
  override fun visitElement(node: UElement, data: TransferInput<T>): TransferResult<T> =
    TransferResult.normal(data.value())

  /** Overridden to pass through the given [data] by default, including conditionals. */
  override fun visitExpression(node: UExpression, data: TransferInput<T>): TransferResult<T> =
    data.passthroughResult(node)
}

/**
 * Result of [transferring][UForwardTransfer] over a node, which can be constructed as a [normal]
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
  /** Returns the represented value *assuming non-exceptional return*. */
  internal abstract fun regularReturnValue(): T

  /**
   * Transforms this result to an input, optionally filtered by the given edge label.
   *
   * @param label [Cfg] edge label to filter by, `null` meaning *regular return*, i.e., as defined
   *   by [Cfg.successorsWithConditions]
   * @param default value to use for other conditions, typically, [UForwardTransfer.bottom]
   */
  internal abstract fun toInput(label: CfgEdgeLabel?, default: T): TransferInput<T>

  /**
   * Returns a value for the given exception type.
   *
   * @return value for the mapping with most precise type or [regularReturnValue] if no such type
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
    return result ?: regularReturnValue() // use regular return if no matching exception
  }

  internal fun toExceptionalInput(
    exceptionalLabel: CfgEdgeLabel.CfgExceptionalEdge,
    default: T,
  ): TransferInput<T> =
    TransferInput.Normal(
      exceptionalLabel.thrown
        .map { if (it != null) exceptionalValue(it) else regularReturnValue() }
        .reduceOrNull(Value<T>::join) ?: default
    )

  @VisibleForTesting
  internal class Normal<T : Value<T>>(val value: T, exceptionalValues: Map<PsiType, T>) :
    TransferResult<T>(exceptionalValues) {
    override fun regularReturnValue(): T = value

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
    override fun regularReturnValue(): T = trueValue join falseValue

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
 * Input to [transfer functions][UForwardTransfer] that exposes incoming analysis [value]s.
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
private class DataflowAnalysis<T : Value<T>>(
  private val cfg: Cfg,
  transferFactory: ForwardTransferFactory<T>,
) : UDataflowContext<T> {
  private val before = mutableMapOf<UElement, TransferInput<T>>()
  private val after = mutableMapOf<UElement, TransferResult<T>>()

  // `transfer` should be the last property to be initialized so transferFactory can't see
  // uninitialized properties (besides `transfer` itself).
  private val transfer: UForwardTransfer<T> = transferFactory(this)

  fun runAnalysis() {
    val pending = mutableSetOf<UElement>()
    before[cfg.entryNode] = TransferInput.Normal(transfer.initialState)
    pending += cfg.entryNode
    while (pending.isNotEmpty()) {
      // Remove and transfer a node; add nodes to be visited as a result
      // TODO(kmb): implement efficient visitation order to minimize re-computations
      val node: UElement = pending.removeFirst()
      pending += transferOver(node)
    }
  }

  fun transferOver(node: UElement): List<UElement> {
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
    return after[node]?.regularReturnValue() ?: transfer.bottom
  }

  override fun get(element: PsiElement): T {
    val nodes = cfg.sourceMapping[element]
    return nodes.mapNotNull { after[it]?.regularReturnValue() }.reduceOrNull(Value<T>::join)
      ?: transfer.bottom
  }

  /** Removes and returns the collection's first element (as returned by its [MutableIterator]). */
  private fun <T> MutableCollection<T>.removeFirst(): T {
    val iter = iterator()
    val result = iter.next()
    iter.remove()
    return result
  }
}
