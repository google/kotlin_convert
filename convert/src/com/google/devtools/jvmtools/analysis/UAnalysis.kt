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

import com.google.errorprone.annotations.Immutable
import com.intellij.psi.PsiElement
import org.jetbrains.uast.UElement

/** Analysis results accessed by [PsiElement]s. */
interface PsiAnalysis<T> {
  /** Returns result for the given [element] assuming its successful execution. */
  operator fun get(element: PsiElement): T
}

/** Analysis results accessed by UAST [UElement]s. */
interface UAnalysis<T> : PsiAnalysis<T> {
  /** Returns result for the given [node] assuming its successful execution. */
  operator fun get(node: UElement): T
}

/**
 * Abstract values tracked by a [dataflow][UTransferFunction] analysis.
 *
 * Concrete implementations should implement a lattice of finite height, to guarantee termination of
 * iterative dataflow analyses.
 *
 * @param T the concrete class of values so [join] can return the receiver type
 */
@Immutable
interface Value<T : Value<T>> {
  /**
   * `true` if this is the most precise value that implies all others, `false` otherwise.
   *
   * In logical terms, "bottom" corresponds to "false": it [implies] all other values, and is the
   * unit element for [join] (aka logical OR).
   */
  val isBottom: Boolean

  /**
   * Returns `true` if this value is at least as precise as [other], and `false` otherwise.
   *
   * This function imposes a partial order on a given class of [Value]s, with the receiver being
   * less than or equal to [other] if `true`.
   */
  fun implies(other: T): Boolean

  /**
   * Returns a value that abstracts this and the given value similar to logical OR, i.e., it must be
   * true that `this` and [other] both [imply][implies] the returned value.
   *
   * To guarantee that an iterative analysis terminates, this function should eventually return a
   * "least precise" ("top") value that, when joined with any other value, returns itself.
   */
  infix fun join(other: T): T
}

/** Lifts [Value.join] to nullable arguments, treating `null` as [Value.isBottom]. */
infix fun <T : Value<T>> T?.join(other: T?): T? =
  when {
    other == null -> this
    this == null -> other
    else -> join(other)
  }
