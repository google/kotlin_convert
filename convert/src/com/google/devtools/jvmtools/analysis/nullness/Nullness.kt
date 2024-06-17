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

import com.google.devtools.jvmtools.analysis.Value
import com.google.errorprone.annotations.Immutable

/**
 * Analysis value to represent a reference's nullness, or [BOTTOM] if it hasn't been initialized.
 *
 * [implies] and [join] are consistent with the "obvious" partial order: [BOTTOM] < (([NONULL] <
 * [PARAMETRIC]) xor [NULL]) < [NULLABLE].
 */
@Immutable
enum class Nullness : Value<Nullness> {
  BOTTOM {
    override fun join(other: Nullness): Nullness = other

    override fun implies(other: Nullness): Boolean = true

    override fun equate(other: Nullness): Nullness = BOTTOM
  },
  NONULL {
    override fun join(other: Nullness): Nullness =
      when (other) {
        BOTTOM,
        NONULL -> NONULL
        PARAMETRIC -> PARAMETRIC
        NULL,
        NULLABLE -> NULLABLE
      }

    override fun implies(other: Nullness): Boolean =
      other == NONULL || other == PARAMETRIC || other == NULLABLE

    override fun equate(other: Nullness): Nullness =
      when (other) {
        BOTTOM,
        NULL -> BOTTOM
        NONULL,
        PARAMETRIC,
        NULLABLE -> NONULL
      }
  },

  /**
   * Meant for type variables with nullable upper bound, where this helps distinguish `T` from `T?`
   * ([NULLABLE]) and `T!!` ([NONULL], written `T & Any` in Kotlin).
   *
   * Type variables with nullable upper bound _may_ represent a nullable type (but may not) and are
   * therefore less precise than [NONULL] but don't need to be qualified as [NULLABLE], because they
   * are neither definitely inclusive, nor definitely exclusive, of `null`. For this reason we also
   * don't consider [NULL] to [imply][implies] [PARAMETRIC]--since [NULL] isn't _strictly_ more
   * precise--and thus joining [PARAMETRIC] and [NULL] yields [NULLABLE].
   *
   * Note: for type variables with non-nullable upper bound, use [NONULL] for `T` (since it's
   * exclusive of `null` and thus the same as `T!!`) and [NULLABLE] for `T?`.
   */
  PARAMETRIC {
    override fun join(other: Nullness): Nullness =
      when (other) {
        BOTTOM,
        NONULL,
        PARAMETRIC -> PARAMETRIC
        NULL,
        NULLABLE -> NULLABLE
      }

    override fun implies(other: Nullness): Boolean = other == PARAMETRIC || other == NULLABLE

    override fun equate(other: Nullness): Nullness =
      when (other) {
        BOTTOM -> BOTTOM
        NULL -> NULL
        NONULL -> NONULL
        PARAMETRIC,
        NULLABLE -> PARAMETRIC
      }
  },
  NULL {
    override fun join(other: Nullness): Nullness =
      when (other) {
        BOTTOM,
        NULL -> NULL
        NONULL,
        PARAMETRIC,
        NULLABLE -> NULLABLE
      }

    override fun implies(other: Nullness): Boolean = other == NULL || other == NULLABLE

    override fun equate(other: Nullness): Nullness =
      when (other) {
        BOTTOM,
        NONULL -> BOTTOM
        PARAMETRIC,
        NULL,
        NULLABLE -> NULL
      }
  },
  NULLABLE {
    override fun join(other: Nullness): Nullness = NULLABLE

    override fun implies(other: Nullness): Boolean = other == NULLABLE

    override fun equate(other: Nullness): Nullness = other
  };

  override val isBottom: Boolean
    get() = this == BOTTOM

  /**
   * Returns the result of equating this and [other], which in particular will be [BOTTOM] when
   * giving contradicting inputs (i.e., [NULL] and [NONULL]) and otherwise the more precise of the
   * given values.
   *
   * Note this function is usually the dual of [join] (i.e., the "meet", or logical AND), except
   * that it returns [NULL] when given [NULL] and [PARAMETRIC].
   */
  abstract infix fun equate(other: Nullness): Nullness
}
