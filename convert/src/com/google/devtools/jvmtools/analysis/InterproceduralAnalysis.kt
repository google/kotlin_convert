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

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.common.collect.HashMultimap
import com.google.common.collect.ImmutableMultimap
import com.google.common.collect.Maps
import com.google.common.collect.Multimap
import com.google.errorprone.annotations.Immutable
import com.intellij.psi.PsiElement
import org.jetbrains.uast.UElement

/**
 * Context object to request analysis results from while analyzing [analysisKey], see
 * [InterproceduralAnalysisBuilder].
 */
abstract class UInterproceduralAnalysisContext<K : CfgRootKey, V : Value<V>>(
  /** Key for the current intraprocedural analysis. */
  val analysisKey: K
) {
  /**
   * Returns intraprocedural analysis results for the given [calleeKey] using [initialState].
   *
   * Calling this method with the same [calleeKey] but different [initialState] will return results
   * computed using the join of all [initialState] values presented with the same [calleeKey].
   */
  abstract fun dataflowResult(calleeKey: K, initialState: V): UDataflowResult<V>

  override fun toString(): String = "UInterproceduralAnalysisContext[$analysisKey]"
}

/**
 * Query dataflow analysis results created using [InterproceduralAnalysisBuilder].
 *
 * @param K keys analysis results, specifying a root and optional context
 * @param V analysis result type, see [UAnalysis]
 */
class InterproceduralResult<K : CfgRootKey, V>
internal constructor(
  private val results: Map<K, UDataflowResult<V>>,
  private val defaultResult: UDataflowResult<V>,
) {
  /** Groups known analysis keys by their roots to allow querying all results for a given root. */
  val analysisKeys: ImmutableMultimap<CfgRoot, K> by lazy {
    ImmutableMultimap.copyOf(results.keys.map { Maps.immutableEntry(it.callee, it) })
  }

  /** Returns analysis results for the given key or a dummy result if the key is unknown. */
  operator fun get(analysisKey: K): UDataflowResult<V> =
    results.getOrDefault(analysisKey, defaultResult)
}

/**
 * Build up interprocedural analysis results by using [addRoot] to register all entry points to be
 * analyzed together, followed by [build] to access results as [InterproceduralResult].
 *
 * @param K keys analysis results, specifying a root and optional context
 * @param V abstract values being tracked by the analysis, see [UTransferFunction]
 */
// Based on https://cmu-program-analysis.github.io/2022/resources/program-analysis.pdf ch. 8
class InterproceduralAnalysisBuilder<K : CfgRootKey, V : Value<V>>(
  /** [Bottom][Value.isBottom] value to use absent other information. */
  val bottom: V,
  /** Creates transfer functions that can use given context to request interprocedural results. */
  private val transferFactory: (UInterproceduralAnalysisContext<K, V>) -> UTransferFunction<V>,
) {
  private val dummyResult = BottomAnalysis(bottom)
  private val worklist: MutableSet<K> = mutableSetOf()
  private val analyzing: MutableSet<K> = mutableSetOf()
  private val results: MutableMap<K, DataflowSummary<V>> = mutableMapOf()
  private val callers: Multimap<K, K> = HashMultimap.create()

  /** Simple cache to avoid re-analyzing the same method over and over. */
  private val cfgs = Caffeine.newBuilder().maximumSize(100L).build<CfgRoot, Cfg>()

  /**
   * Adds an [analysisKey] be analyzed with [initialState].
   *
   * @param initialState closure to compute initial state for analyzing [analysisKey] that can
   *   request and use analysis results for other keys.
   */
  fun addRoot(analysisKey: K, initialState: (UInterproceduralAnalysisContext<K, V>) -> V) {
    val unused = getOrCompute(analysisKey, initialState(ContextImpl(analysisKey)))
    processWorklist()
  }

  /**
   * Returns analysis results derived from the given [roots][addRoot], which can include results for
   * more keys than were given if analyzing the given roots involved results for other keys.
   */
  fun build(): InterproceduralResult<K, V> =
    InterproceduralResult(
      results.mapValues { (_, summary) -> summary.intraproceduralResults },
      dummyResult,
    )

  private fun processWorklist() {
    while (worklist.isNotEmpty()) {
      val next = worklist.removeFirst()
      analyze(next, checkNotNull(results[next]) { "don't have summary for $next" })
    }
  }

  private fun getOrCompute(analysisKey: K, initialState: V): UDataflowResult<V> {
    var summary = results[analysisKey]
    if (summary != null) {
      if (initialState.implies(summary.before)) {
        return summary.intraproceduralResults
      } else {
        summary.before = summary.before join initialState
      }
    } else {
      summary = DataflowSummary(before = initialState, after = bottom, dummyResult)
      results[analysisKey] = summary
    }

    return analyze(analysisKey, summary)
  }

  private fun analyze(analysisKey: K, summary: DataflowSummary<V>): UDataflowResult<V> {
    if (!analyzing.add(analysisKey)) return dummyResult

    val analysis =
      DataflowAnalysis<V>(cfgs.get(analysisKey.callee) { Cfg.create(it) }) {
        transferFactory(ContextImpl(analysisKey))
      }
    analysis.runAnalysis(summary.before)
    analyzing -= analysisKey

    summary.intraproceduralResults = analysis
    val newResult = analysis.finalResult
    if (!newResult.implies(summary.after)) {
      summary.after = summary.after join newResult
      worklist += callers[analysisKey]
    }
    return analysis
  }

  private inner class ContextImpl(analysisKey: K) :
    UInterproceduralAnalysisContext<K, V>(analysisKey) {
    override fun dataflowResult(calleeKey: K, initialState: V): UDataflowResult<V> {
      val result = getOrCompute(calleeKey, initialState)
      // Register context as a "caller" of calleeContext
      callers.put(calleeKey, analysisKey)
      return result
    }
  }
}

private data class DataflowSummary<V>(
  var before: V,
  var after: V,
  var intraproceduralResults: UDataflowResult<V>,
)

/** Dummy [UAnalysis] that always returns [bottom]. */
@Immutable
private class BottomAnalysis<V : Value<V>>(val bottom: V) : UDataflowResult<V> {
  override val finalResult: V
    get() = bottom

  override fun get(node: UElement): V = bottom

  override fun get(element: PsiElement): V = bottom

  override fun uastNodes(element: PsiElement): Collection<UElement> = emptyList()
}
