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

import com.intellij.psi.PsiSwitchLabelStatement
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UBreakExpression
import org.jetbrains.uast.UCatchClause
import org.jetbrains.uast.UContinueExpression
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.UDoWhileExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UExpressionList
import org.jetbrains.uast.UForExpression
import org.jetbrains.uast.UIdentifier
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.UJumpExpression
import org.jetbrains.uast.ULabeledExpression
import org.jetbrains.uast.UReturnExpression
import org.jetbrains.uast.USwitchClauseExpression
import org.jetbrains.uast.USwitchClauseExpressionWithBody
import org.jetbrains.uast.USwitchExpression
import org.jetbrains.uast.UThrowExpression
import org.jetbrains.uast.UTryExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.UWhileExpression
import org.jetbrains.uast.UYieldExpression
import org.jetbrains.uast.visitor.UastTypedVisitor
import org.jetbrains.uast.visitor.UastVisitor

/**
 * Returns whether the given expression
 * [can complete normally](https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html#jls-14.22)
 * _assuming without checking_ that it is
 * [reachable](https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html#jls-14.22).
 *
 * Not checking reachability here is done for a few reasons:
 * * it considers the given element on its own, avoiding `false` results because the given
 *   expression happens to be unreachable
 * * Unlike Java, Kotlin doesn't seem to require all expressions be reachable, so it's somewhat
 *   problematic to return `false` here "just" because the given element is unreachable.
 * * this implementation is _much_ faster (absent memoization): reachability is expensive to check
 *   because it requires traversing parents and checking completion of preceding elements;
 *   conversely, this function only considers the given element and its children.
 * * this approach is also likely easier to maintain, as there's no mutually recursive second
 *   algorithm for reachability to be kept "in sync".
 */
// TODO(kmb): handle Kotlin-style nested `throw`s etc and methods that return Nothing
fun UElement.canCompleteNormally(): Boolean = accept(CanCompleteNormally, Unit)

private object CanCompleteNormally : UastTypedVisitor<Unit, Boolean> {
  override fun visitElement(node: UElement, data: Unit): Boolean {
    if (node is UIdentifier) return true
    TODO("Not yet implemented: $node")
  }

  override fun visitDeclaration(node: UDeclaration, data: Unit): Boolean = true

  override fun visitVariable(node: UVariable, data: Unit): Boolean = true

  override fun visitExpression(node: UExpression, data: Unit): Boolean = true

  override fun visitExpressionList(node: UExpressionList, data: Unit): Boolean =
    if (node.uastParent is USwitchExpression) {
      // No default label
      // TODO(kmb): maybe exhaustive enum switch all cases aborting should also return false?
      node.expressions.none { case ->
        case is USwitchClauseExpression &&
          case.caseValues.any {
            // UAST doesn't encode default/else label very well, so look at PSI
            val psi = it.javaPsi
            psi is PsiSwitchLabelStatement && psi.isDefaultCase
          }
      } ||
        // Last block falls out
        node.expressions.lastOrNull()?.canCompleteNormally() != false
    } else {
      // Includes switch entry bodies
      node.expressions.lastOrNull()?.canCompleteNormally() != false
    }

  override fun visitBlockExpression(node: UBlockExpression, data: Unit): Boolean =
    node.expressions.lastOrNull()?.canCompleteNormally() != false

  override fun visitLabeledExpression(node: ULabeledExpression, data: Unit): Boolean =
    node.expression.canCompleteNormally() || node.expression.canBreakTo(node.expression)

  override fun visitIfExpression(node: UIfExpression, data: Unit): Boolean =
    (node.thenExpression?.canCompleteNormally() != false) ||
      (node.elseExpression?.canCompleteNormally() != false)

  override fun visitSwitchExpression(node: USwitchExpression, data: Unit): Boolean =
    // TODO(kmb): handle switch expressions, in both Java and Kotlin
    node.body.canCompleteNormally() || node.body.canBreakTo(node)

  override fun visitSwitchClauseExpression(node: USwitchClauseExpression, data: Unit): Boolean =
    node is USwitchClauseExpressionWithBody && node.body.canCompleteNormally()

  override fun visitWhileExpression(node: UWhileExpression, data: Unit): Boolean =
    node.condition.evaluate() != true || node.body.canBreakTo(node)

  override fun visitDoWhileExpression(node: UDoWhileExpression, data: Unit): Boolean =
    (node.condition.evaluate() != true &&
      (node.body.canCompleteNormally() || node.body.canContinueTo(node))) ||
      node.body.canBreakTo(node)

  override fun visitForExpression(node: UForExpression, data: Unit): Boolean =
    (node.condition != null && node.condition?.evaluate() != true) || node.body.canBreakTo(node)

  override fun visitBreakExpression(node: UBreakExpression, data: Unit): Boolean = false

  override fun visitContinueExpression(node: UContinueExpression, data: Unit): Boolean = false

  override fun visitReturnExpression(node: UReturnExpression, data: Unit): Boolean = false

  override fun visitThrowExpression(node: UThrowExpression, data: Unit): Boolean = false

  override fun visitYieldExpression(node: UYieldExpression, data: Unit): Boolean = false

  override fun visitTryExpression(node: UTryExpression, data: Unit): Boolean =
    (node.tryClause.canCompleteNormally() || node.catchClauses.any { it.canCompleteNormally() }) &&
      node.finallyClause?.canCompleteNormally() != false

  override fun visitCatchClause(node: UCatchClause, data: Unit): Boolean =
    node.body.canCompleteNormally()

  private fun UElement.canBreakTo(neededTarget: UExpression): Boolean {
    var found = false
    accept(
      object : UastVisitor {
        override fun visitElement(node: UElement): Boolean = found

        override fun visitBreakExpression(node: UBreakExpression): Boolean {
          found =
            found ||
              ((node.jumpTarget == neededTarget ||
                // jumpTarget is null if it's the node itself, so handle this case specially
                (node.jumpTarget == null &&
                  node == neededTarget &&
                  node.uastParent.let { it is ULabeledExpression && it.label == node.label })) &&
                node.canJumpToTarget())
          return found
        }
      }
    )
    return found
  }

  private fun UElement.canContinueTo(neededTarget: UDoWhileExpression): Boolean {
    var found = false
    accept(
      object : UastVisitor {
        override fun visitElement(node: UElement): Boolean = found

        override fun visitContinueExpression(node: UContinueExpression): Boolean {
          found = found || (node.jumpTarget == neededTarget && node.canJumpToTarget())
          return found
        }
      }
    )
    return found
  }

  /** Returns `false` if there's a `finally` block in the way that can't complete normally. */
  private fun UJumpExpression.canJumpToTarget(): Boolean {
    var child: UElement = this
    var parent: UElement? = uastParent
    while (parent != null && child != jumpTarget) {
      if (
        parent is UTryExpression &&
          child != parent.finallyClause &&
          parent.finallyClause?.canCompleteNormally() == false
      ) {
        return false
      }
      child = parent
      parent = child.uastParent
    }
    return true
  }
}
