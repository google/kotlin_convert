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

package com.google.devtools.jvmtools.convert.psi2k

import com.android.tools.lint.detector.api.isKotlin
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.light.classes.symbol.annotations.getJvmNameFromAnnotation
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.ClassId

/** Type of method conversion. */
enum class MappingType {
  /** Direct method conversion (name change only). */
  DIRECT,
  /** Method-to-property conversion. */
  PROPERTY,
  /** Method-to-extension conversion. */
  EXTENSION,
}

/** Information for mapping a Java method to a Kotlin method (or property). */
data class MappedMethod(
  val className: String,
  val javaMethodName: String,
  val kotlinName: String,
  val type: MappingType,
) {

  companion object {
    /** Returns a [MappedMethod] that matches the given callee, or `null` if it does not exist. */
    fun forCall(call: PsiMethodCallExpression): MappedMethod? {
      val callee = call.resolveMethod() ?: return null
      val declaringClass = callee.containingClass ?: return null
      MAPPED_METHODS_BY_NAME[callee.name]?.firstOrNull { method ->
          InheritanceUtil.isInheritor(declaringClass, method.className)
        }
        ?.let {
          return it
        }

      // Map calls to Kotlin property getters, which is required in Kotlin (b/354260950).
      // TODO: b/354260950 - handle extension properties, setters
      if (!call.argumentList.isEmpty) return null
      if (!isKotlin(callee.language) && !declaringClass.hasAnnotation("kotlin.Metadata")) {
        return null // @kotlin.Metadata indicates Kotlin declaration in binary dependency
      }
      return analyze(KaModuleProvider.getInstance(call.project).getModule(call, null)) {
        // callee.callableSymbol doesn't work, so manually look for matching property (see KT-83483)
        val klass =
          findClass(
            ClassId.fromString(
              declaringClass.qualifiedName?.replace('.', '/')?.replace('$', '.')
                ?: return@analyze null
            )
          )
        val ktSymbol =
          klass?.memberScope?.callables?.filterIsInstance<KaPropertySymbol>()?.firstOrNull {
            val getterName =
              it.getter?.getJvmNameFromAnnotation() ?: JvmAbi.getterName(it.name.identifier)
            getterName == callee.name
          }
        if (ktSymbol != null) {
          MappedMethod(
            className = declaringClass.qualifiedName ?: "<anonymous>",
            javaMethodName = callee.name,
            kotlinName = ktSymbol.name.identifier,
            type = MappingType.PROPERTY,
          )
        } else {
          null
        }
      }
    }

    private val MAPPED_METHODS =
      listOf(
        // go/keep-sorted start block=yes
        MappedMethod(
          "com.google.common.base.Strings",
          "isNullOrEmpty",
          "isNullOrEmpty",
          MappingType.EXTENSION,
        ),
        MappedMethod("java.lang.CharSequence", "charAt", "get", MappingType.DIRECT),
        MappedMethod("java.lang.CharSequence", "length", "length", MappingType.PROPERTY),
        MappedMethod("java.lang.Object", "getClass", "javaClass", MappingType.PROPERTY),
        MappedMethod("java.lang.String", "getBytes", "toByteArray", MappingType.DIRECT),
        MappedMethod("java.lang.String", "valueOf", "toString", MappingType.EXTENSION),
        MappedMethod("java.lang.Throwable", "getCause", "cause", MappingType.PROPERTY),
        MappedMethod("java.lang.Throwable", "getMessage", "message", MappingType.PROPERTY),
        MappedMethod("java.util.Collection", "size", "size", MappingType.PROPERTY),
        MappedMethod("java.util.Map", "entrySet", "entries", MappingType.PROPERTY),
        MappedMethod("java.util.Map", "keySet", "keys", MappingType.PROPERTY),
        MappedMethod("java.util.Map", "size", "size", MappingType.PROPERTY),
        MappedMethod("java.util.Map", "values", "values", MappingType.PROPERTY),
        MappedMethod("java.util.Map.Entry", "getKey", "key", MappingType.PROPERTY),
        MappedMethod("java.util.Map.Entry", "getValue", "value", MappingType.PROPERTY),
        // go/keep-sorted end
      )

    private val MAPPED_METHODS_BY_NAME: Map<String, List<MappedMethod>> =
      MAPPED_METHODS.groupBy { it.javaMethodName }
  }
}
