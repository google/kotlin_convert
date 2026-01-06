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
import com.google.errorprone.annotations.ImmutableTypeParameter
import com.intellij.psi.PsiVariable
import org.jetbrains.uast.UExpression

/**
 * Tracks [V] values separately for each non-null key [K], treating missing mappings equivalent to
 * [bottom][Value.isBottom].
 */
@Immutable
data class Tuple<@ImmutableTypeParameter K : Any, V : Value<V>>(
  private val contents: ImmutableMap<K, V>
) : Value<Tuple<K, V>>, Map<K, V> by contents {
  constructor(values: Map<out K, V>) : this(ImmutableMap.copyOf(values))

  override val isBottom: Boolean
    get() = contents.values.all { it.isBottom } // trivially true if contents empty as desired

  override fun join(other: Tuple<K, V>): Tuple<K, V> {
    val result = contents.toMutableMap()
    for ((k, v) in other.contents) {
      result.compute(k) { _, existing -> existing?.join(v) ?: v }
    }
    return Tuple(result)
  }

  override fun implies(other: Tuple<K, V>): Boolean =
    contents.all { (k, v) ->
      val otherV = other[k]
      if (otherV != null) v.implies(otherV) else v.isBottom
    }

  /**
   * Returns a [Tuple] identical to this one except mapping the given [key] to the given [value].
   */
  fun withMapping(key: K, value: V): Tuple<K, V> {
    if (contents[key] == value) return this
    val newContents = contents.toMutableMap()
    newContents[key] = value
    return Tuple(newContents)
  }

  /** Returns a [Tuple] identical to this one except for the given new mappings. */
  operator fun plus(newMappings: Collection<Pair<K, V>>): Tuple<K, V> {
    if (newMappings.isEmpty()) return this
    val newContents = contents.toMutableMap()
    for ((key, value) in newMappings) newContents[key] = value
    return Tuple(newContents)
  }

  companion object {
    private val EMPTY = Tuple<Nothing, Nothing>(ImmutableMap.of())

    /** Returns an empty [Tuple], i.e., a [bottom][Tuple.isBottom] object. */
    @Suppress("UNCHECKED_CAST")
    fun <K : Any, V : Value<V>> empty(): Tuple<K, V> = EMPTY as Tuple<K, V>
  }
}

/**
 * Pairs a [store] with a [value] tuple:
 * * [value] represents what we know about each expression at a given program point
 * * [store] tracks variables in scope
 */
@Immutable
data class State<T : Value<T>>(val value: Tuple<UExpression, T>, val store: Tuple<PsiVariable, T>) :
  Value<State<T>> {
  override val isBottom: Boolean
    get() = value.isBottom && store.isBottom

  override fun join(other: State<T>) = State(value join other.value, store join other.store)

  override fun implies(other: State<T>): Boolean =
    value.implies(other.value) && store.implies(other.store)

  companion object {
    private val EMPTY = State<Nothing>(Tuple.empty(), Tuple.empty())

    /** Returns an empty [State], i.e., containing empty tuples. */
    @Suppress("UNCHECKED_CAST") fun <T : Value<T>> empty(): State<T> = EMPTY as State<T>

    /** Returns a [State] with the given [storeContents] and an empty [value] tuple. */
    fun <T : Value<T>> withStore(storeContents: Map<PsiVariable, T>): State<T> =
      State(Tuple.empty(), @Suppress("Immutable") Tuple(storeContents))
  }
}
