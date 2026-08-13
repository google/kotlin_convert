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

import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiTypes
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
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
  /** Method-to-top-level function conversion (strips qualifier). */
  TOP_LEVEL,
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
          if (!InheritanceUtil.isInheritor(declaringClass, method.className))
            return@firstOrNull false
          // Character extensions only valid on Char receiver, not int codepoints
          if (method.className == "java.lang.Character" && method.type == MappingType.EXTENSION) {
            val firstArg = call.argumentList.expressions.firstOrNull()
            if (
              firstArg == null ||
                (firstArg.type != PsiTypes.charType() &&
                  firstArg.type?.equalsToText("java.lang.Character") != true)
            ) {
              return@firstOrNull false
            }
          }
          // TOP_LEVEL arity and parameter validation
          if (method.type == MappingType.TOP_LEVEL) {
            when (method.className) {
              "com.google.common.base.Preconditions",
              "java.util.Objects" -> {
                if (call.argumentList.expressionCount != 1) return@firstOrNull false
              }
              "java.lang.Math" -> {
                if (call.argumentList.expressionCount != 2) return@firstOrNull false
              }
              "java.util.Collections" -> {
                when (method.javaMethodName) {
                  "emptyList",
                  "emptySet",
                  "emptyMap" -> {
                    if (call.argumentList.expressionCount != 0) return@firstOrNull false
                  }
                  "singletonList",
                  "singleton" -> {
                    if (call.argumentList.expressionCount != 1) return@firstOrNull false
                  }
                }
              }
            }
          }
          true
        }
        ?.let {
          return it
        }

      // Map calls to Kotlin property getters, which is required in Kotlin (b/354260950).
      // TODO: b/354260950 - handle extension properties, setters
      if (!call.argumentList.isEmpty) return null
      return analyze(KaModuleProvider.getInstance(call.project).getModule(call, null)) {
        // callee.callableSymbol doesn't work, so manually look for matching property (see KT-83483)
        val klass =
          findClass(
            ClassId.fromString(
              declaringClass.qualifiedName?.replace('.', '/')?.replace('$', '.')
                ?: return@analyze null
            )
          )
        klass
          ?.memberScope
          ?.callables
          ?.filterIsInstance<KaKotlinPropertySymbol>()
          ?.firstOrNull {
            val getterName =
              it.getter?.getJvmNameFromAnnotation() ?: JvmAbi.getterName(it.name.identifier)
            getterName == callee.name
          }
          ?.let { ktSymbol ->
            MappedMethod(
              className = declaringClass.qualifiedName ?: "<anonymous>",
              javaMethodName = callee.name,
              kotlinName = ktSymbol.name.identifier,
              type = MappingType.PROPERTY,
            )
          }
      }
    }

    private val MAPPED_METHODS =
      listOf(
        // go/keep-sorted start block=yes
        MappedMethod(
          "com.google.common.base.Preconditions",
          "checkArgument",
          "require",
          MappingType.TOP_LEVEL,
        ),
        MappedMethod(
          "com.google.common.base.Preconditions",
          "checkNotNull",
          "checkNotNull",
          MappingType.TOP_LEVEL,
        ),
        MappedMethod(
          "com.google.common.base.Preconditions",
          "checkState",
          "check",
          MappingType.TOP_LEVEL,
        ),
        MappedMethod(
          "com.google.common.base.Strings",
          "isNullOrEmpty",
          "isNullOrEmpty",
          MappingType.EXTENSION,
        ),
        MappedMethod(
          "com.google.common.base.Strings",
          "nullToEmpty",
          "orEmpty",
          MappingType.EXTENSION,
        ),
        MappedMethod("java.io.File", "getAbsolutePath", "absolutePath", MappingType.PROPERTY),
        MappedMethod("java.io.File", "getName", "name", MappingType.PROPERTY),
        MappedMethod("java.io.File", "getParent", "parent", MappingType.PROPERTY),
        MappedMethod("java.io.File", "getParentFile", "parentFile", MappingType.PROPERTY),
        MappedMethod("java.io.File", "getPath", "path", MappingType.PROPERTY),
        MappedMethod("java.io.File", "isDirectory", "isDirectory", MappingType.PROPERTY),
        MappedMethod("java.io.File", "isFile", "isFile", MappingType.PROPERTY),
        MappedMethod("java.lang.Boolean", "parseBoolean", "toBoolean", MappingType.EXTENSION),
        MappedMethod("java.lang.CharSequence", "charAt", "get", MappingType.DIRECT),
        MappedMethod("java.lang.CharSequence", "length", "length", MappingType.PROPERTY),
        MappedMethod("java.lang.Character", "isDigit", "isDigit", MappingType.EXTENSION),
        MappedMethod("java.lang.Character", "isISOControl", "isISOControl", MappingType.EXTENSION),
        MappedMethod("java.lang.Character", "isLetter", "isLetter", MappingType.EXTENSION),
        MappedMethod("java.lang.Character", "isLowerCase", "isLowerCase", MappingType.EXTENSION),
        MappedMethod("java.lang.Character", "isUpperCase", "isUpperCase", MappingType.EXTENSION),
        MappedMethod("java.lang.Character", "isWhitespace", "isWhitespace", MappingType.EXTENSION),
        MappedMethod(
          "java.lang.Character",
          "isLetterOrDigit",
          "isLetterOrDigit",
          MappingType.EXTENSION,
        ),
        MappedMethod("java.lang.Class", "getCanonicalName", "canonicalName", MappingType.PROPERTY),
        MappedMethod("java.lang.Class", "getName", "name", MappingType.PROPERTY),
        MappedMethod("java.lang.Class", "getSimpleName", "simpleName", MappingType.PROPERTY),
        MappedMethod("java.lang.Class", "getSuperclass", "superclass", MappingType.PROPERTY),
        MappedMethod("java.lang.Double", "parseDouble", "toDouble", MappingType.EXTENSION),
        MappedMethod("java.lang.Float", "parseFloat", "toFloat", MappingType.EXTENSION),
        MappedMethod("java.lang.Integer", "parseInt", "toInt", MappingType.EXTENSION),
        MappedMethod("java.lang.Long", "parseLong", "toLong", MappingType.EXTENSION),
        MappedMethod("java.lang.Math", "max", "maxOf", MappingType.TOP_LEVEL),
        MappedMethod("java.lang.Math", "min", "minOf", MappingType.TOP_LEVEL),
        MappedMethod("java.lang.Object", "getClass", "javaClass", MappingType.PROPERTY),
        MappedMethod("java.lang.String", "getBytes", "toByteArray", MappingType.DIRECT),
        MappedMethod("java.lang.String", "toLowerCase", "lowercase", MappingType.DIRECT),
        MappedMethod("java.lang.String", "toUpperCase", "uppercase", MappingType.DIRECT),
        MappedMethod("java.lang.String", "valueOf", "toString", MappingType.EXTENSION),
        MappedMethod("java.lang.Thread", "getId", "id", MappingType.PROPERTY),
        MappedMethod("java.lang.Thread", "getName", "name", MappingType.PROPERTY),
        MappedMethod("java.lang.Throwable", "getCause", "cause", MappingType.PROPERTY),
        MappedMethod("java.lang.Throwable", "getMessage", "message", MappingType.PROPERTY),
        MappedMethod("java.lang.Throwable", "getStackTrace", "stackTrace", MappingType.PROPERTY),
        MappedMethod(
          "java.lang.Throwable",
          "getLocalizedMessage",
          "localizedMessage",
          MappingType.PROPERTY,
        ),
        MappedMethod("java.util.Arrays", "asList", "mutableListOf", MappingType.TOP_LEVEL),
        MappedMethod("java.util.Collection", "size", "size", MappingType.PROPERTY),
        MappedMethod("java.util.Collections", "emptyList", "mutableListOf", MappingType.TOP_LEVEL),
        MappedMethod("java.util.Collections", "emptyMap", "mutableMapOf", MappingType.TOP_LEVEL),
        MappedMethod("java.util.Collections", "emptySet", "mutableSetOf", MappingType.TOP_LEVEL),
        MappedMethod("java.util.Collections", "fill", "fill", MappingType.EXTENSION),
        MappedMethod("java.util.Collections", "reverse", "reverse", MappingType.EXTENSION),
        MappedMethod("java.util.Collections", "shuffle", "shuffle", MappingType.EXTENSION),
        MappedMethod("java.util.Collections", "singleton", "mutableSetOf", MappingType.TOP_LEVEL),
        MappedMethod("java.util.Collections", "sort", "sort", MappingType.EXTENSION),
        MappedMethod(
          "java.util.Collections",
          "binarySearch",
          "binarySearch",
          MappingType.EXTENSION,
        ),
        MappedMethod(
          "java.util.Collections",
          "singletonList",
          "mutableListOf",
          MappingType.TOP_LEVEL,
        ),
        MappedMethod("java.util.Map", "entrySet", "entries", MappingType.PROPERTY),
        MappedMethod("java.util.Map", "keySet", "keys", MappingType.PROPERTY),
        MappedMethod("java.util.Map", "size", "size", MappingType.PROPERTY),
        MappedMethod("java.util.Map", "values", "values", MappingType.PROPERTY),
        MappedMethod("java.util.Map.Entry", "getKey", "key", MappingType.PROPERTY),
        MappedMethod("java.util.Map.Entry", "getValue", "value", MappingType.PROPERTY),
        MappedMethod("java.util.Objects", "requireNonNull", "checkNotNull", MappingType.TOP_LEVEL),
        // go/keep-sorted end
      )

    private val MAPPED_METHODS_BY_NAME: Map<String, List<MappedMethod>> = MAPPED_METHODS.groupBy {
      it.javaMethodName
    }
  }
}
