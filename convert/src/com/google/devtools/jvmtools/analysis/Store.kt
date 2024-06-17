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

import com.google.common.collect.ImmutableMap
import com.google.errorprone.annotations.Immutable
import com.intellij.psi.PsiVariable

/**
 * Tracks [T] values separately for each [PsiVariable], treating missing mappings equivalent to
 * [bottom][Value.isBottom].
 */
@Immutable
data class Store<T : Value<T>>(
  @Suppress("Immutable") // PsiVariable can't be annotated
  private val contents: ImmutableMap<PsiVariable, T> = ImmutableMap.of()
) : Value<Store<T>>, Map<PsiVariable, T> by contents {
  constructor(values: Map<PsiVariable, T>) : this(ImmutableMap.copyOf(values))

  override val isBottom: Boolean
    get() = contents.values.all { it.isBottom } // trivially true if contents empty as desired

  override fun join(other: Store<T>): Store<T> {
    val result = contents.toMutableMap()
    for ((k, v) in other.contents) {
      result.compute(k) { _, existing -> existing?.join(v) ?: v }
    }
    return Store(result)
  }

  override fun implies(other: Store<T>): Boolean =
    contents.all { (k, v) ->
      val otherV = other[k]
      if (otherV != null) v.implies(otherV) else v.isBottom
    }

  /**
   * Returns a [Store] identical to this one except mapping the given [key] to the given [value].
   */
  fun withMapping(key: PsiVariable, value: T): Store<T> {
    if (contents[key] == value) return this
    val newContents = contents.toMutableMap()
    newContents[key] = value
    return Store(newContents)
  }

  /** Returns a [Store] identical to this one except for the given new mappings. */
  operator fun plus(newMappings: Collection<Pair<PsiVariable, T>>): Store<T> {
    if (newMappings.isEmpty()) return this
    val newContents = contents.toMutableMap()
    for ((key, value) in newMappings) newContents[key] = value
    return Store(newContents)
  }
}

/**
 * Pairs a [Store] with a [value]:
 * * [value] typically represents a node's own value (e.g., its nullness)
 * * [store] tracks variables in scope
 */
@Immutable
data class State<T : Value<T>>(val value: T, val store: Store<T>) : Value<State<T>> {
  override val isBottom: Boolean
    get() = value.isBottom && store.isBottom

  override fun join(other: State<T>) = State(value join other.value, store join other.store)

  override fun implies(other: State<T>): Boolean =
    value.implies(other.value) && store.implies(other.store)
}
