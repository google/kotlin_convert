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

import com.google.common.collect.HashBasedTable
import com.google.common.collect.HashMultimap
import com.google.common.collect.ImmutableMultimap
import com.google.errorprone.annotations.CanIgnoreReturnValue
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UBreakExpression
import org.jetbrains.uast.UContinueExpression
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.UDeclarationsExpression
import org.jetbrains.uast.UDoWhileExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UForEachExpression
import org.jetbrains.uast.UForExpression
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.UImportStatement
import org.jetbrains.uast.UJumpExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.ULocalVariable
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.UReturnExpression
import org.jetbrains.uast.USwitchClauseExpression
import org.jetbrains.uast.USwitchClauseExpressionWithBody
import org.jetbrains.uast.USwitchExpression
import org.jetbrains.uast.UThrowExpression
import org.jetbrains.uast.UTryExpression
import org.jetbrains.uast.UWhileExpression
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.visitor.UastTypedVisitor
import org.jetbrains.uast.visitor.UastVisitor

/**
 * Control flow graph built from UAST [UElement]s.
 *
 * Edges in the graph connect nodes in (possible) execution order, with nested UAST nodes generally
 * appearing before their parents. Nodes can have multiple [successors] at control flow branches
 * (e.g., after an `if` condition), and multiple predessors at control flow merges (e.g., a
 * [UIfExpression] can have incoming edges from the ends of each branch).
 *
 * Edges can optionally (best effort) be labeled with conditions, which in particular represent
 * control flow branches after Boolean conditions. While edges of this graph can't be directly
 * queried, [successorsWithConditions] returns successors with any labels attached to the connecting
 * edges.
 *
 * UAST doesn't make all operations explicit. In those cases, we insert special node objects that
 * don't exist in the original UAST. The additional node classes can be seen and visited in
 * [CfgTypedVisitor].
 */
data class Cfg
private constructor(
  /** The unique first ("entry") node in the [Cfg]. */
  val entryNode: UElement,
  private val exitNode: UElement,
  private val forwardEdges: ImmutableMultimap<UElement, UElement>,
  private val edgeConditions: (UElement, UElement) -> CfgEdgeLabel?,
  /** Allows finding [PsiElement]s in a [Cfg]. */
  val sourceMapping: ImmutableMultimap<PsiElement, UElement>,
  private val reversed: Boolean = false,
) {
  /** Returns the set of nodes that follow the given [node] in this [Cfg]. */
  fun successors(node: UElement): Collection<UElement> = forwardEdges[node]

  /**
   * Returns a map from [successors] of the given [node] to the labels attached to the connecting
   * edges, if any.
   *
   * If two nodes conceptually are connected with both a `true` and a `false` edge, the two are
   * collapsed into a single, unlabeled edge.
   */
  // Internal for now b/c the CfgEdgeLabel API feels a bit wonky
  // TODO(kmb): Consider using `Set<Union<Boolean?, PsiType>>` as edge labels
  internal fun successorsWithConditions(node: UElement): Map<UElement, CfgEdgeLabel?> =
    forwardEdges[node].associateWith { edgeConditions(node, it) }

  fun reverse(): Cfg {
    require(!reversed) { "Don't reverse a reversed CFG, just use the original." }
    return Cfg(
      entryNode = exitNode,
      exitNode = entryNode,
      forwardEdges.inverse(),
      edgeConditions = { source: UElement, dest: UElement -> edgeConditions(dest, source) },
      sourceMapping,
      reversed = true,
    )
  }

  companion object {
    /** Derives a [Cfg] for the given method. */
    fun create(root: UMethod): Cfg = createCfg(root)

    /** Derives a [Cfg] for the given [CfgRoot]. */
    fun create(root: CfgRoot): Cfg = createCfg(root.rootNode)

    private fun createCfg(root: UElement): Cfg =
      CfgBuilder(root).run {
        root.accept(this)
        // TODO(kmb): consider eagerly dropping unreachable edges
        Cfg(
          checkNotNull(entryNode) { "No entry node found in $root" },
          exitNode = root,
          ImmutableMultimap.copyOf(forwardEdges),
          edgeConditions::get,
          sourceMapping.build(),
        )
      }
  }
}

/** Type safe wrapper for root node to build CFG from. */
sealed class CfgRoot {
  /** The root node, whose type is refined in the sealed subtypes. */
  abstract val rootNode: UElement

  data class Method(override val rootNode: UMethod) : CfgRoot()

  data class Lambda(override val rootNode: ULambdaExpression) : CfgRoot()

  companion object {
    fun of(rootNode: UMethod) = Method(rootNode)

    fun of(rootNode: ULambdaExpression) = Lambda(rootNode)
  }
}

/** [Cfg] node visitor that allows visiting synthetic nodes. */
interface CfgTypedVisitor<D, R> : UastTypedVisitor<D, R> {
  fun visitCfgSyntheticExpression(node: CfgSyntheticExpression, data: D): R =
    visitExpression(node, data)

  fun visitCfgSwitchBranchExpression(node: CfgSwitchBranchExpression, data: D): R =
    visitCfgSyntheticExpression(node, data)

  fun visitCfgForeachIteratedExpression(node: CfgForeachIteratedExpression, data: D): R =
    visitCfgSyntheticExpression(node, data)
}

/**
 * Base class for synthetic [UExpression] nodes that make implicit computation explicit in the CFG.
 */
abstract class CfgSyntheticExpression(val wrappedExpression: UExpression) : UExpression {
  @Deprecated("ambiguous psi element, use `sourcePsi` or `javaPsi`")
  override val psi: PsiElement?
    get() = null

  override val uAnnotations: List<UAnnotation>
    get() = emptyList()

  override val uastParent: UElement?
    get() = wrappedExpression.uastParent

  override fun asLogString(): String = javaClass.simpleName

  override fun toString() = asRenderString()
}

/** Makes multiplexing table switch on [USwitchExpression] value explicit. */
class CfgSwitchBranchExpression(expression: UExpression) : CfgSyntheticExpression(expression) {
  override fun <D, R> accept(visitor: UastTypedVisitor<D, R>, data: D): R {
    return if (visitor is CfgTypedVisitor) {
      visitor.visitCfgSwitchBranchExpression(this, data)
    } else {
      visitor.visitExpression(this, data)
    }
  }

  override fun asRenderString(): String = "branch(${wrappedExpression.asRenderString()})"
}

/** Makes dereference of iterated value in [UForEachExpression]s explicit. */
class CfgForeachIteratedExpression(expression: UExpression) : CfgSyntheticExpression(expression) {
  override fun <D, R> accept(visitor: UastTypedVisitor<D, R>, data: D): R {
    return if (visitor is CfgTypedVisitor) {
      visitor.visitCfgForeachIteratedExpression(this, data)
    } else {
      visitor.visitExpression(this, data)
    }
  }

  override fun asRenderString(): String = "iterate(${wrappedExpression.asRenderString()})"
}

/**
 * "Tags" for edges that don't simply represent regular, unconditional control flow.
 *
 * Note that for simplicity, a given directed edge (i.e., source-destination node pair) can only
 * carry at most one [CfgEdgeLabel] that represents all the ways in which that edge can be taken,
 * rather than associating a set of labels with each edge. We also use "no" (i.e., `null`) label for
 * an edge that is only taken unconditionally during regular (i.e., non-exceptional) execution,
 * rather than defining an object for this case (see [Cfg.successorsWithConditions]).
 */
internal sealed interface CfgEdgeLabel {
  /**
   * Edge only taken if the source node was a binary expression that evaluated to the edge's
   * [condition] (and never as a result of an exception).
   */
  enum class CfgConditionalEdge(val condition: Boolean) : CfgEdgeLabel {
    FALSE(condition = false),
    TRUE(condition = true),
  }

  /**
   * Edge taken during exceptional control flow (that may or may not also be taken during regular
   * control flow).
   *
   * Possible exception types are collected in [thrown].
   */
  data class CfgExceptionalEdge internal constructor(internal val thrown_: MutableSet<PsiType?>) :
    CfgEdgeLabel {
    /**
     * Exceptions flowing along this edge. A `null` element indicates that this is also a regular
     * control flow edge with the same source and destination, which typically only happens for
     * edges connecting an inner `finally` block to a surrounding one.
     *
     * An edge labeled with a `thrown` set only containing `null` would be equivalent to an edge
     * with no label at all, but [Cfg]s should just use edges with no labels in that case.
     */
    // We include marker for regular control flow here because (1) this helps not forget about the
    // extra case in TransferResult.toInput and (2) it's rare and thus saves space.
    val thrown: Set<PsiType?>
      get() = thrown_
  }

  companion object {
    /** Returns the [CfgConditionalEdge] corresponding to the given [condition]. */
    internal fun conditionalEdge(condition: Boolean) =
      if (condition) CfgConditionalEdge.TRUE else CfgConditionalEdge.FALSE

    /** Convenience factory for [CfgExceptionalEdge]s. */
    internal fun exceptionalEdge(firstThrown: PsiType) =
      CfgExceptionalEdge(mutableSetOf(firstThrown))
  }
}

private class CfgBuilder(private val root: UElement) : UastVisitor {
  /**
   * Helper class for [CfgBuilder] that in particular ensures that [last] is only set using
   * [setLast] (i.e., together with [condition]).
   */
  private class CfgBuilderState {
    fun setLast(last: UElement?, condition: Boolean? = null) {
      this.condition = condition
      this.last = requireNotNull(last) { "never set last back to null, was ${this.last}" }
      for (callback in markCallbacks) callback(last)
      markCallbacks.clear()
    }

    fun setLastWithoutCallbacks(last: UElement?, condition: Boolean? = null) {
      this.condition = condition
      this.last = last
    }

    fun popCallbacks(): List<(UElement) -> Unit> {
      val result = markCallbacks.toList()
      markCallbacks.clear()
      return result
    }

    var first: UElement? = null
      private set

    var last: UElement? = null
      private set

    var condition: Boolean? = null

    /** Lambdas invoked the next time [last] is set. List is cleared after each set. */
    val markCallbacks = mutableListOf<(UElement) -> Unit>({ first = it })
  }

  private val state = CfgBuilderState()

  // This property allows CfgBuilderState to be private for now
  val entryNode: UElement?
    get() = state.first

  val forwardEdges = HashMultimap.create<UElement, UElement>()
  val edgeConditions = HashBasedTable.create<UElement, UElement, CfgEdgeLabel>()
  val sourceMapping = ImmutableMultimap.builder<PsiElement, UElement>()

  /** Maps potential jump targets such as loops to their entry nodes. */
  private val continuationEntries = mutableMapOf<UElement, UElement>()

  private val throwableType: PsiType by lazy {
    PsiType.getJavaLangThrowable(root.sourcePsi?.manager!!, root.sourcePsi?.resolveScope!!)
  }

  private val npeType: PsiType by lazy {
    PsiType.getTypeByName(
      "java.lang.NullPointerException",
      root.sourcePsi?.project!!,
      root.sourcePsi?.resolveScope!!,
    )
  }

  /** Visits the receiver node and returns its entry node. */
  private fun UElement.doAccept(): UElement {
    lateinit var entry: UElement
    state.markCallbacks += { entry = it }
    accept(this@CfgBuilder)
    return entry
  }

  /**
   * Adds a source mapping for [node] if one exists and [skip] is `false`.
   *
   * @return [skip]'s value
   */
  @CanIgnoreReturnValue
  private fun addSourceMapping(node: UElement, skip: Boolean = false): Boolean {
    if (!skip) node.sourcePsi?.let { sourceMapping.put(it, node) }
    return skip
  }

  private fun addEdge(source: UElement, dest: UElement, condition: Boolean?) {
    forwardEdges.put(source, dest)
    when (val existingLabel = edgeConditions[source, dest]) {
      is CfgEdgeLabel.CfgExceptionalEdge -> existingLabel.thrown_ += null // add regular edge
      is CfgEdgeLabel.CfgConditionalEdge ->
        if (condition == null || existingLabel.condition != condition) {
          edgeConditions.remove(source, dest) // revert back to unconditional edge
        } // else we already have this conditional edge
      null ->
        if (condition != null) {
          edgeConditions.put(source, dest, CfgEdgeLabel.conditionalEdge(condition))
        }
    }
  }

  private fun addExceptionEdge(source: UElement, dest: UElement, thrownType: PsiType) {
    check(throwableType.isAssignableFrom(thrownType)) { "Not a Throwable: $thrownType" }
    val isExistingEdge = !forwardEdges.put(source, dest)
    when (val existingLabel = edgeConditions[source, dest]) {
      is CfgEdgeLabel.CfgExceptionalEdge -> existingLabel.thrown_ += thrownType
      else ->
        edgeConditions.put(
          source,
          dest,
          CfgEdgeLabel.exceptionalEdge(thrownType).also {
            if (isExistingEdge) it.thrown_ += null // record pre-existing regular edge
          },
        )
    }
  }

  private fun addEdgeFromLastIfPossible(node: UElement, condition: Boolean? = state.condition) {
    val prevNode = state.last
    if (prevNode?.canCompleteNormally() == true) {
      addEdge(prevNode, node, condition)
    }
  }

  override fun visitElement(node: UElement): Boolean {
    return addSourceMapping(node)
  }

  override fun visitAnnotation(node: UAnnotation): Boolean =
    addSourceMapping(node, skip = node != root)

  override fun visitImportStatement(node: UImportStatement) = true

  // Don't recurse into declarations other than those we override separately
  override fun visitDeclaration(node: UDeclaration): Boolean =
    addSourceMapping(node, skip = node != root)

  override fun visitLocalVariable(node: ULocalVariable): Boolean = visitElement(node)

  override fun visitParameter(node: UParameter): Boolean = visitElement(node)

  override fun visitBinaryExpression(node: UBinaryExpression): Boolean {
    return visitPolyadicExpression(node)
  }

  override fun visitIfExpression(node: UIfExpression): Boolean {
    addSourceMapping(node)
    node.condition.accept(this)
    val afterCondition = state.last

    state.condition = true
    node.thenExpression?.accept(this)
    addEdgeFromLastIfPossible(node)

    state.setLast(afterCondition, condition = false)
    node.elseExpression?.accept(this)
    addEdgeFromLastIfPossible(node) // adds condition -> exit edge if no else branch

    state.setLast(node)
    return true // already visited above
  }

  override fun visitLambdaExpression(node: ULambdaExpression): Boolean {
    addSourceMapping(node)
    return if (node == root) {
      false
    } else {
      // Treat lambda as value: make a node for it, but don't recurse, since it doesn't execute
      afterVisitElement(node)
      true
    }
  }

  override fun visitPolyadicExpression(node: UPolyadicExpression): Boolean {
    addSourceMapping(node)
    if (node.operator is UastBinaryOperator.LogicalOperator) {
      // Handle short-circuiting operators
      // || short-circuits on "true"; && short-circuits on "false"
      val shortCircuitCondition = node.operator == UastBinaryOperator.LOGICAL_OR
      for (operand in node.operands.dropLast(1)) {
        operand.accept(this)
        addEdgeFromLastIfPossible(node, shortCircuitCondition)
        state.condition = !shortCircuitCondition
      }
      node.operands.lastOrNull()?.accept(this)
      addEdgeFromLastIfPossible(node)
      state.setLast(node)
      return true // already visited above
    }
    return false // handle as normal nested expression that executes in sequence
  }

  override fun visitSwitchExpression(node: USwitchExpression): Boolean {
    addSourceMapping(node)
    addSourceMapping(node.body)

    // For Java-style switches, insert a synthetic node that marks the table-driven branching
    // characteristic of a switch. Note that among other things, the switched-on expression is
    // implicitly checked to be non-null (and unboxed if necessary), so this node helps identify
    // that. Additionally, it should allow generating conditional edges.
    // We could use node itself as this marker node, but by employing a synthetic node, node can
    // follow its children in the CFG for consistency with how we handle other AST nodes.
    // Another alternative might be to generate nodes that approximate what happens under the cover,
    // e.g., a node for <expr>.ordinal() in the case of an enum switch. But that's tedious and/or
    // imprecise, esp. for string switches, and arguably obfuscates what's going on.
    val branch =
      node.expression?.let {
        it.accept(this)
        CfgSwitchBranchExpression(it)
      }
    // TODO(kmb): generate if-else edges if there's no condition or conditions are matched in order
    if (branch != null) {
      addEdgeFromLastIfPossible(branch, condition = null)
      // No condition matched TODO(kmb) only generate this edge if switch isn't exhaustive
      addEdge(branch, node, condition = null)
      state.setLast(branch)
    }

    val valuesSeen = mutableSetOf<Any>()
    var lastExit: UElement? = null
    for (case in node.body.expressions) {
      // if-else if somehow allows smartcasts on lastExit where `when` doesn't
      if (case is USwitchClauseExpressionWithBody) {
        addSourceMapping(case)

        val bodyEntry = case.body.doAccept()

        // Entry edge TODO(kmb): generate conditional edges or find other way to propagate case by
        // case results
        if (branch != null) addEdge(branch, bodyEntry, condition = null)
        if (lastExit?.canCompleteNormally() == true) {
          // Fallthrough edge
          addEdge(lastExit, bodyEntry, condition = null)
        }
        lastExit = state.last

        // TODO(kmb): for switch expressions, add edges representing the "result" of this case
        // and omit fallthrough/fallout edges instead

        // Don't visit values: they're constants and aren't evaluated at runtime
        // TODO(kmb): generate (conditional) edges if conditions are matched in order as in Kotlin
        valuesSeen.addAll(case.caseValues.mapNotNull { it.evaluate() })
      } else if (case is USwitchClauseExpression) {
        valuesSeen.addAll(case.caseValues.mapNotNull { it.evaluate() })
      } else {
        // Shouldn't get here, but we'll simply visit for now.
        case.accept(this)
      }
    }
    if (lastExit?.canCompleteNormally() == true) {
      // Fallout edge
      addEdge(lastExit, node, condition = null)
    }

    addEdge(node.body, node, condition = null)
    state.setLast(node)
    return true // already visited above
  }

  override fun visitSwitchClauseExpression(node: USwitchClauseExpression): Boolean {
    throw IllegalStateException("shouldn't get here: $node")
  }

  override fun visitDoWhileExpression(node: UDoWhileExpression): Boolean {
    addSourceMapping(node)
    val loopEntry = node.body.doAccept()
    continuationEntries[node] = loopEntry
    node.condition.accept(this)
    addEdgeFromLastIfPossible(node, condition = false) // edge out of the loop
    addEdgeFromLastIfPossible(loopEntry, condition = true) // back edge to loop entry

    state.setLast(node)
    return true // already visited above
  }

  override fun visitForExpression(node: UForExpression): Boolean {
    addSourceMapping(node)
    node.declaration?.accept(this)

    // For the purposes of continue statements for this loop, we want to consider "update" the
    // loop entry to jump to (or "condition" if there is no update), but "update" isn't visited
    // upon first loop entry. So we pretend here that we've already visited "body" to visit
    // "update" and "condition" so loopEntry is set correctly for any continue statements when
    // we visit "body" below".
    val predecessor = state.last
    val incomingCondition = state.condition
    // Since declaration may be null, there may be pending callbacks: stash while we visit update
    val pendingCallbacks = state.popCallbacks()
    lateinit var continueEntry: UElement
    state.markCallbacks += {
      continueEntry = it
      continuationEntries[node] = it
    }
    state.setLastWithoutCallbacks(node.body)
    node.update?.accept(this)

    state.markCallbacks += pendingCallbacks
    lateinit var firstEntry: UElement
    state.markCallbacks += { firstEntry = it }
    node.condition?.let {
      it.accept(this)
      addEdgeFromLastIfPossible(node, condition = false) // edge out of the loop
      state.condition = true
    } // else this is an infinite loop

    node.body.accept(this)
    addEdgeFromLastIfPossible(continueEntry, condition = null) // back edge to continue the loop
    if (predecessor != null) addEdge(predecessor, firstEntry, incomingCondition)
    // else firstEntry is the cfg entry

    state.setLast(node)
    return true // already visited above
  }

  override fun visitForEachExpression(node: UForEachExpression): Boolean {
    addSourceMapping(node)
    node.iteratedValue.accept(this)
    val iteration = CfgForeachIteratedExpression(node.iteratedValue)
    addEdgeFromLastIfPossible(iteration, condition = null)
    state.setLast(iteration)
    addEdgeFromLastIfPossible(node, condition = null) // edge to skip the loop (nothing to iterate)

    val loopEntry = node.variable.doAccept()
    continuationEntries[node] = loopEntry
    node.body.accept(this)
    addEdgeFromLastIfPossible(loopEntry, condition = null) // back edge to loop entry
    addEdgeFromLastIfPossible(node, condition = null) // edge out of the loop

    state.setLast(node)
    return true // already visited above
  }

  override fun visitWhileExpression(node: UWhileExpression): Boolean {
    addSourceMapping(node)
    val loopEntry = node.condition.doAccept()
    continuationEntries[node] = loopEntry
    addEdgeFromLastIfPossible(node, condition = false) // edge out of the loop

    state.condition = true
    node.body.accept(this)
    addEdgeFromLastIfPossible(loopEntry, condition = null) // back edge to loop entry

    state.setLast(node)
    return true // already visited above
  }

  override fun visitTryExpression(node: UTryExpression): Boolean {
    addSourceMapping(node)

    val predecessor = state.last
    val incomingCondition = state.condition
    val pendingCallbacks = state.popCallbacks()

    val finallyEntry: UElement? =
      node.finallyClause?.let { finally ->
        state.setLastWithoutCallbacks(node.tryClause)
        val finallyEntry = finally.doAccept()
        continuationEntries[finally] = finallyEntry
        // Only create regular exit if try block or any catch block can exit regularly.
        // TODO(kmb): more reliably detect finally blocks whose normal entry isn't reachable
        if (
          node.tryClause.canCompleteNormally() ||
            node.catchClauses.any { it.body.canCompleteNormally() }
        ) {
          addEdgeFromLastIfPossible(node, condition = null)
        }
        finallyEntry
      }

    for (catch in node.catchClauses) {
      state.setLastWithoutCallbacks(null) // only exceptional edges can lead into catch clauses
      val catchEntry = catch.doAccept()
      continuationEntries[catch] = catchEntry
      addEdgeFromLastIfPossible(finallyEntry ?: node, condition = null)
    }

    // Restore original predecessor, condition, and any pending callbacks
    state.setLastWithoutCallbacks(predecessor, incomingCondition)
    state.markCallbacks += pendingCallbacks

    node.resourceVariables.forEach { it.accept(this) }
    node.tryClause.accept(this)
    addEdgeFromLastIfPossible(finallyEntry ?: node, condition = null)

    state.setLast(node)
    return true // already visited above
  }

  override fun afterVisitElement(node: UElement) {
    addEdgeFromLastIfPossible(node)
    state.setLast(node)
  }

  override fun afterVisitExpression(node: UExpression) {
    afterVisitElement(node)
    // We don't get here for some expressions that represent control flow, such as jumps, `throw`,
    // and IfExpression, but that's ok since they can't fail exceptionally.
    // To account for unexpected errors, we assume any Throwable can be thrown
    if (node.mayFailWithThrowable()) addExceptionEdges(node, throwableType)
  }

  override fun afterVisitBreakExpression(node: UBreakExpression) {
    afterVisitJumpExpression(node)
  }

  override fun afterVisitContinueExpression(node: UContinueExpression) {
    addEdgeFromLastIfPossible(node)
    state.setLast(node) // setLast before creating outgoing edge in case node is loop's entry node
    addJumpEdges(
      node,
      jumpTarget = continuationEntries[node.jumpTarget] ?: node.jumpTarget ?: root,
      jumpRoot = node.jumpTarget ?: root,
    )
  }

  override fun afterVisitReturnExpression(node: UReturnExpression) {
    afterVisitJumpExpression(node)
  }

  private fun afterVisitJumpExpression(node: UJumpExpression) {
    addEdgeFromLastIfPossible(node)
    state.setLast(node)
    addJumpEdges(node, node.jumpTarget ?: root)
  }

  private fun addJumpEdges(node: UElement, jumpTarget: UElement, jumpRoot: UElement = jumpTarget) {
    var origin = node
    var child = node
    var parent: UElement? = node.uastParent
    while (parent != null && parent != root && parent != jumpRoot) {
      // Jumps go through any finally blocks along the way
      if (parent is UTryExpression && child != parent.finallyClause) {
        parent.finallyClause?.let { finally ->
          addEdge(origin, continuationEntries.getOrDefault(finally, finally), condition = null)
          if (!finally.canCompleteNormally()) return
          origin = finally
        }
      }
      child = parent
      parent = child.uastParent
    }
    addEdge(origin, jumpTarget, condition = null)
  }

  override fun afterVisitThrowExpression(node: UThrowExpression) {
    addEdgeFromLastIfPossible(node)
    var thrown = node.thrownExpression.getExpressionType() ?: throwableType
    when {
      // `throw null` throws NullPointerException
      thrown == PsiTypes.nullType() -> thrown = npeType
      // This shouldn't happen, but simply correct the type to be any Throwable
      !throwableType.isAssignableFrom(thrown) -> thrown = throwableType
      // If NPE isn't already included in thrown, add edges for it in case that thrownException is
      // null, see https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html#jls-14.18.
      // Note we could conceivably omit these edges when thrownException expressions is known to be
      // non-null, at least for simple cases that don't require deep analysis, such as `new`
      // expressions or when a caught exception is rethrown, but that may be surprising in some use
      // cases so may be better done as an optional feature.
      !thrown.isAssignableFrom(npeType) -> addExceptionEdges(node, npeType)
    }
    addExceptionEdges(node, thrown)
    state.setLast(node)
  }

  private fun addExceptionEdges(node: UElement, thrown: PsiType) {
    val alreadyCaught = mutableSetOf<PsiType>()
    var origin = node
    var child = node
    var parent: UElement? = node.uastParent
    while (parent != null && parent != root) {
      if (parent is UTryExpression && child != parent.finallyClause) {
        for (catch in parent.catchClauses) {
          for (caught in catch.types) {
            if (alreadyCaught.any { it.isAssignableFrom(caught) }) continue
            val isDefiniteCatch = caught.isAssignableFrom(thrown)
            if (isDefiniteCatch || thrown.isAssignableFrom(caught)) {
              // Use the narrowest of thrown and caught as the edge's label
              addExceptionEdge(
                origin,
                continuationEntries.getOrDefault(catch, catch),
                if (isDefiniteCatch) thrown else caught,
              )
              alreadyCaught += caught
            }
            // If the given exception was definitely caught, stop walking try statements
            if (isDefiniteCatch) return
          }
        }

        // Uncaught exceptions flow throw finally block and on from there
        parent.finallyClause?.let { finally ->
          addExceptionEdge(origin, continuationEntries.getOrDefault(finally, finally), thrown)
          if (!finally.canCompleteNormally()) return
          origin = finally
        }
      }
      child = parent
      parent = child.uastParent
    }
    addExceptionEdge(origin, root, thrown)
  }

  private fun UExpression.mayFailWithThrowable(): Boolean =
    this !is UBlockExpression && this !is UDeclarationsExpression && canCompleteNormally()
}
