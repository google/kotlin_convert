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

import com.google.devtools.jvmtools.convert.util.runIf
import com.google.devtools.jvmtools.analysis.CfgRoot
import com.google.devtools.jvmtools.analysis.State
import com.google.devtools.jvmtools.analysis.UAnalysis
import com.google.devtools.jvmtools.analysis.canCompleteNormally
import com.google.devtools.jvmtools.analysis.nullness.Nullness
import com.google.devtools.jvmtools.analysis.nullness.NullnessAnalysis.nullness
import com.google.devtools.jvmtools.analysis.nullness.hasNullableAnno
import com.google.devtools.jvmtools.analysis.nullness.hasNullableBound
import com.google.devtools.jvmtools.analysis.nullness.indicatesNonNull
import com.google.devtools.jvmtools.analysis.nullness.indicatesNullable
import com.google.devtools.jvmtools.analysis.nullness.isInNullMarkedScope
import com.google.devtools.jvmtools.analysis.nullness.isNonNullAnno
import com.google.devtools.jvmtools.analysis.nullness.isNullableAnno
import com.google.devtools.jvmtools.analysis.nullness.returnValueNullness
import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiAnnotationMethod
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiArrayInitializerExpression
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiBlockStatement
import com.intellij.psi.PsiBreakStatement
import com.intellij.psi.PsiCall
import com.intellij.psi.PsiCaseLabelElementList
import com.intellij.psi.PsiCatchSection
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassInitializer
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiContinueStatement
import com.intellij.psi.PsiDeclarationStatement
import com.intellij.psi.PsiDiamondType
import com.intellij.psi.PsiDisjunctionType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiEnumConstantInitializer
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiExpressionStatement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiForStatement
import com.intellij.psi.PsiForeachStatement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiImportStatement
import com.intellij.psi.PsiImportStaticReferenceElement
import com.intellij.psi.PsiImportStaticStatement
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaReference
import com.intellij.psi.PsiJavaToken
import com.intellij.psi.PsiKeyword
import com.intellij.psi.PsiLabeledStatement
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierList
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiParameterList
import com.intellij.psi.PsiParenthesizedExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiQualifiedExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReferenceList
import com.intellij.psi.PsiReferenceParameterList
import com.intellij.psi.PsiResourceExpression
import com.intellij.psi.PsiResourceList
import com.intellij.psi.PsiResourceVariable
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiSuperExpression
import com.intellij.psi.PsiSwitchLabelStatement
import com.intellij.psi.PsiSwitchStatement
import com.intellij.psi.PsiThisExpression
import com.intellij.psi.PsiTryStatement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeCastExpression
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiUnaryExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.util.TypeConversionUtil.calcTypeForBinaryExpression
import com.intellij.psi.util.TypeConversionUtil.isIntegralNumberType
import com.intellij.psi.util.TypeConversionUtil.isNumericType
import com.intellij.psi.util.TypeConversionUtil.isPrimitiveAndNotNull
import com.intellij.psi.util.TypeConversionUtil.isPrimitiveAndNotNullOrWrapper
import com.intellij.psi.util.TypeConversionUtil.unboxAndBalanceTypes
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfTypes2
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UFile
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.USwitchClauseExpression
import org.jetbrains.uast.USwitchExpression
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType

/** Translates the receiver file to Kotlin and returns the resulting text. */
fun UFile.translateToKotlin(): String {
  val nullnessResults = nullness()
  return sourcePsi.translateToKotlin { nullnessResults[it] }
}

private fun PsiFile.translateToKotlin(nullness: (CfgRoot) -> UAnalysis<State<Nullness>>): String {
  require(
    language == JavaLanguage.INSTANCE && name != "package-info.java" && name != "module-info.java"
  ) {
    "Can only translate Java classes but got: ${virtualFile.path} ($language)"
  }
  val result = StringBuilder()
  val visitedNodes = mutableMapOf<PsiElement, String>()
  accept(Psi2kTranslator(PsiTranslationOutput(result), visitedNodes, nullness))

  // Make sure we translated all nodes, and no additional nodes
  val allNodes = mutableSetOf<PsiElement>()
  accept(
    object : JavaRecursiveElementVisitor() {
      override fun visitElement(element: PsiElement) {
        super.visitElement(element)
        allNodes += element
      }
    }
  )

  val unexpected = visitedNodes.keys - allNodes
  val missed = allNodes - visitedNodes.keys
  check(unexpected.isEmpty()) {
    val first = unexpected.first()
    "${unexpected.size} unexpected nodes incl. $first ${first.textRange} ${first.text}: $result"
  }
  check(missed.isEmpty()) {
    val details =
      missed
        .filter { it !is PsiWhiteSpace }
        .joinToString { "${it.text} ${it.textRange}" }
        .ifEmpty { missed.joinToString { "${it.text} ${it.textRange}" } }
    "${missed.size} missed nodes incl. $details: $result"
  }

  return result.toString()
}

/**
 * Translation output buffer.
 *
 * @property dest output goes here
 * @property companion buffer for companion object content, which is writable through [companion]
 */
private class PsiTranslationOutput(
  private val dest: StringBuilder,
  private val companion: StringBuilder? = null,
) {
  /** Returns a [PsiTranslationOutput] to write to [companion]. */
  fun companion() = PsiTranslationOutput(checkNotNull(companion) { "No companion object" })

  /** Returns a [PsiTranslationOutput] with the same [dest] and given [companion]. */
  fun withCompanion(companion: StringBuilder) = PsiTranslationOutput(dest, companion)

  /** Writes [s] to [dest]. */
  fun append(s: CharSequence) {
    dest.append(s)
  }

  /**
   * Writes [s] to [dest] if not `null`, and does nothing when given `null`.
   *
   * This is unlike [StringBuilder.append], which would write "null" when given `null`.
   */
  fun appendNonNull(s: String?) {
    if (s != null) append(s)
  }

  /** [dest]'s current length, for later use with [substring]. */
  fun length(): Int = dest.length

  /** Returns [dest]'s contents starting at the given position. */
  fun substring(start: Int): String = dest.substring(start)
}

/**
 * Java PSI visitor that recursively translates the visited PSI node.
 *
 * @property data visitor data, currently [PsiTranslationOutput]
 * @property seen visited nodes for completeness checks
 */
private open class Psi2kTranslator(
  private val data: PsiTranslationOutput,
  private val seen: MutableMap<PsiElement, String>,
  private val nullness: (CfgRoot) -> UAnalysis<State<Nullness>>,
) : JavaElementVisitor() {
  /**
   * Helper for [skipRecursive] that recursively marks nodes as [seen] and copies comments and
   * whitespace to [data].
   */
  protected val skipper =
    object : JavaElementVisitor() {
      override fun visitElement(element: PsiElement) {
        seen(element)
        super.visitElement(element)
        element.acceptChildren(this)
      }

      override fun visitComment(comment: PsiComment) {
        super.visitComment(comment)
        data.append(comment.text)
      }

      override fun visitWhiteSpace(space: PsiWhiteSpace) {
        super.visitWhiteSpace(space)
        data.append(space.text)
      }

      override fun visitReferenceExpression(expression: PsiReferenceExpression) {
        visitReferenceElement(expression)
      }
    }

  /** Marks the given [element] as [seen], which should be called exactly once per node. */
  protected fun seen(element: PsiElement?) {
    if (element == null) return
    seen(element, translation = "")
  }

  /** Records the given [element]'s translation. */
  protected fun seen(element: PsiElement, translation: String) {
    check(seen.putIfAbsent(element, translation) == null || element is PsiWhiteSpace) {
      "$element ${element.textRange} visited twice: ```${element.text}```"
    }
  }

  /** Skips over [node] and its transitive children, preserving comments and whitespace. */
  protected fun skipRecursive(node: PsiElement) {
    node.accept(skipper)
  }

  /** Shorthand to invoke [PsiElement.accept] with this visitor. */
  protected fun doAccept(node: PsiElement?) {
    node?.accept(this)
  }

  /** Visits the receiver node if [shouldAccept] and [skipRecursive][skips] it otherwise. */
  protected fun PsiElement.acceptIf(shouldAccept: Boolean) {
    if (shouldAccept) doAccept(this) else skipRecursive(this)
  }

  /** [skipRecursive][Skips] the receiver node if [shouldSkip] and visits it otherwise. */
  protected fun PsiElement.acceptIfNot(shouldSkip: Boolean) {
    acceptIf(!shouldSkip)
  }

  /** [skipRecursive][Skips] the receiver node and appends [replacement] in its stead. */
  protected fun PsiElement.replaceWith(replacement: String) {
    check(this !is PsiComment && this !is PsiWhiteSpace) {
      "Not replacing comment and whitespace atoms, got $this ${textRange}: ```${text}```"
    }
    if (firstChild != null) skipRecursive(this) else seen(this)
    data.append(replacement)
  }

  protected fun PsiElement.replaceWith(replacement: PsiElement) {
    check(this !is PsiComment && this !is PsiWhiteSpace) {
      "Not replacing comment and whitespace atoms, got $this ${textRange}: ```${text}```"
    }
    skipRecursive(this)
    doAccept(replacement)
  }

  /**
   * [replaceWith][Replaces] the receiver node if [replacement] is non-`null` and visits the node if
   * [replacement] is `null`
   */
  protected fun PsiElement.replaceNotNull(replacement: String?) {
    if (replacement != null) replaceWith(replacement) else doAccept(this)
  }

  /** Evaluates the given condition for each node and uses it to call [acceptIf]. */
  protected inline fun <T : PsiElement> Sequence<T>.acceptEach(
    shouldAccept: (T) -> Boolean = { _ -> true }
  ) {
    forEach { it.acceptIf(shouldAccept(it)) }
  }

  /**
   * Calls [replaceNotNull] with the result of the given closure for each element in the sequence.
   */
  protected inline fun <T : PsiElement> Sequence<T>.replaceNotNull(
    replacement: (PsiElement) -> String?
  ) {
    forEach { it.replaceNotNull(replacement(it)) }
  }

  /** Intercept part of the translation to allow make modifications to it before recording */
  private fun intercepting(): InterceptingTranslator {
    val buffer = StringBuilder()
    val companion = StringBuilder()
    val seen = mutableMapOf<PsiElement, String>()
    return object : InterceptingTranslator(buffer, companion, seen, nullness) {
      override fun acceptResult(mapping: InterceptingTranslator.() -> String) {
        this@Psi2kTranslator.data.append(this.mapping())
        for ((k, v) in seen) {
          this@Psi2kTranslator.seen(k, v)
        }
      }
    }
  }

  override fun visitElement(node: PsiElement) {
    super.visitElement(node)
    seen(node)

    // Most expressions work out of the box. Statements, too, once the formatter removes trailing ;
    if (node.firstChild != null) {
      node.acceptChildren(this)
    } else {
      data.append(node.text)
    }
  }

  override fun visitAnnotation(annotation: PsiAnnotation) {
    seen(annotation)

    // Qualify field annotations with @field: for clarity/safety. Fields are turned into properties,
    // and Kotlin will associate annotations with the property instead of the field in most cases.
    val qualifier =
      if ((annotation.owner as? PsiModifierList)?.parent is PsiField) "@field:" else "@"
    annotation.children().replaceNotNull { runIf(it.tokenOrNull() == "@") { qualifier } }
  }

  override fun visitAnnotationArrayInitializer(initializer: PsiArrayInitializerMemberValue) {
    // @Foo(attr = { ... }) ==> @Foo(attr = [...])
    // @Foo({ ... }) ==> @Foo(...)
    seen(initializer)

    val isNamedAttribute = (initializer.parent as? PsiNameValuePair)?.nameIdentifier != null
    initializer.children().replaceNotNull {
      when (it.tokenOrNull()) {
        "{" -> if (isNamedAttribute) "[" else ""
        "}" -> if (isNamedAttribute) "]" else ""
        else -> null
      }
    }
  }

  override fun visitAnnotationMethod(method: PsiAnnotationMethod) {
    // <type> foo() [default <literal>]; ==> val foo: <type> [= <literal>],
    seen(method)
    method.children().forEach { child ->
      when (child) {
        method.returnTypeElement -> data.append("val")
        method.parameterList -> {
          skipRecursive(child)
          visitTypeDeclaration(method.returnTypeElement)
        }
        is PsiJavaToken ->
          when (child.text) {
            PsiKeyword.DEFAULT -> child.replaceWith("=")
            ";" -> child.replaceWith(",")
            else -> doAccept(child)
          }
        else -> doAccept(child)
      }
    }
  }

  override fun visitAnonymousClass(aClass: PsiAnonymousClass) {
    seen(aClass)
    // new ClassType(...) {...} ==> object : ClassType(...) {...}
    // --but-- new InterfaceType() {...} ==> object : InterfaceType {...}
    if (aClass !is PsiEnumConstantInitializer) data.append("object : ")
    aClass.children().acceptEach { child ->
      child != aClass.argumentList || aClass.baseClassType.resolve()?.isInterface != true
    }
  }

  override fun visitArrayInitializerExpression(expression: PsiArrayInitializerExpression) {
    seen(expression)

    val openBraceReplacement =
      if (expression.parent is PsiNewExpression) {
        ""
      } else {
        val componentType = (expression.type as? PsiArrayType)?.componentType
        if (componentType is PsiPrimitiveType) "${componentType.name}ArrayOf(" else "arrayOf("
      }

    expression.children().replaceNotNull {
      when (it.tokenOrNull()) {
        "{" -> openBraceReplacement
        "}" -> if (expression.parent is PsiNewExpression) "" else ")"
        else -> null
      }
    }
  }

  override fun visitAssignmentExpression(expression: PsiAssignmentExpression) {
    val sign = expression.operationSign.text.dropLast(1) // empty if =, else, e.g., + or <<
    val rhsConversion =
      if (sign.isEmpty()) {
        assignmentConversion(expression.lExpression.type, expression.rExpression)
      } else {
        binaryOperandConversion(
          expression.lExpression.type,
          sign,
          expression.rExpression?.type,
          forRhs = true,
        )
      }
    val isNested =
      expression.parent !is PsiExpressionStatement && expression.parent !is PsiExpressionList
    val op = MAPPED_OPERATORS[sign] ?: runIf(isNested && sign.isNotEmpty()) { sign }

    when {
      op != null -> {
        // For ops that are infix functions in Kotlin:
        // x op= y ==> x = x map(op) y[.toXxx()]
        // Also if nested, then for any compound assignment (whether operator is mapped or not):
        // x op= y ==> (x map(op) y[.toXxx()]).also { x = it }
        seen(expression)
        // parent is probably a parenthesized expression already but it's harder to use that here
        if (isNested) data.append("(")
        lateinit var lhs: String
        for (child in expression.children) {
          when (child) {
            expression.lExpression -> {
              val start = data.length()
              doAccept(child)
              lhs = data.substring(start)
            }
            expression.operationSign -> child.replaceWith(if (isNested) op else "= $lhs $op")
            expression.rExpression ->
              if (rhsConversion != null) {
                child.encloseInParensIfNeeded(rhsConversion)
              } else {
                doAccept(child)
              }
            else -> doAccept(child)
          }
        }
        if (isNested) data.append(").also { $lhs = it }")
      }
      isNested -> {
        check(sign.isEmpty()) { "expected regular assignment: ${expression.text}" }
        // Assignments aren't expressions in Kotlin:
        // Nested x = y ==> y[.toXxx()].also { x = it }
        // We handle this separately so we can visit everything but value after .also, so any
        // comments end up there.
        seen(expression)
        expression.rExpression?.encloseInParensIfNeeded(rhsConversion ?: "")
        data.append(".also { ")
        expression.children().filter { it != expression.rExpression }.acceptEach()
        data.append(" it }")
      }
      rhsConversion != null -> {
        // If no other rewriting is needed, just insert rhsConversion, e.g., x += y.toLong()
        seen(expression)
        for (child in expression.children) {
          if (child == expression.rExpression) {
            child.encloseInParensIfNeeded(rhsConversion)
          } else {
            doAccept(child)
          }
        }
      }
      else -> super.visitAssignmentExpression(expression)
    }
  }

  /**
   * Returns any conversion to apply to [rhs] in order to assign it to a variable of [lhsType] per
   * https://docs.oracle.com/javase/specs/jls/se11/html/jls-5.html#jls-5.2.
   */
  private fun assignmentConversion(lhsType: PsiType?, rhs: PsiExpression?): String? {
    val unboxedLhsType = PsiPrimitiveType.getOptionallyUnboxedType(lhsType)
    val unboxedRhsType = PsiPrimitiveType.getOptionallyUnboxedType(rhs?.type)
    return runIf(
      unboxedLhsType != null &&
        unboxedRhsType != null &&
        unboxedLhsType != unboxedRhsType &&
        (unboxedLhsType == PsiTypes.charType() ||
          !isIntegralNumberType(unboxedLhsType) ||
          rhs !is PsiLiteralExpression)
    ) {
      // Implicit primitive conversions must be made explicit, except when assigning a literal to
      // an integral type, see https://kotlinlang.org/docs/numbers.html#explicit-number-conversions.
      // (Unlike in Java, even non-literal compile-time constant expressions must be converted.)
      PRIMITIVE_TYPES.getOrNull(unboxedLhsType)?.let { ".to$it()" }
    }
  }

  override fun visitBinaryExpression(expression: PsiBinaryExpression) {
    visitBinaryChain(
      expression,
      operands = listOfNotNull(expression.lOperand, expression.rOperand),
      operationSigns = listOf(expression.operationSign),
    )
  }

  override fun visitBreakStatement(statement: PsiBreakStatement) {
    val label = statement.labelIdentifier
    if (label != null) {
      visitLabeledJump(statement, keyword = "break", label)
    } else {
      super.visitBreakStatement(statement)
    }
  }

  private fun visitLabeledJump(node: PsiStatement, keyword: String, label: PsiIdentifier) {
    require(node is PsiBreakStatement || node is PsiContinueStatement) {
      "expected jump, got: $node"
    }
    // break|continue label ==> break|continue@label (there can be no spaces)
    // Note labels may need to be quoted (e.g., @`in`, which visiting takes care of
    seen(node)
    data.append(keyword)
    data.append("@")
    doAccept(label)
    // This makes it so whitespace, comments, and trailing ; follow the jump
    node.children().filter { it != label }.acceptEach { it.tokenOrNull() == ";" }
  }

  override fun visitCatchSection(section: PsiCatchSection) {
    val param = section.parameter
    val typeElement = param?.typeElement
    if (param == null || typeElement == null || section.catchType !is PsiDisjunctionType) {
      super.visitCatchSection(section)
      return
    }

    // Since Kotlin doesn't have multi-catch blocks, replicate the catch block for each disjunct
    seen(section)
    var preamble: String? = null
    var block: String? = null
    seen(param.typeElement)
    typeElement.children().forEach { typeChild ->
      when {
        typeChild.tokenOrNull() == "|" -> seen(typeChild)
        typeChild is PsiTypeElement -> { // one of the disjuncts
          if (preamble != null) {
            data.append(preamble!!)
          } else {
            val start = data.length()
            section.children().takeWhile { it != param }.acceptEach()
            seen(param)
            param.children().filter { it != typeElement }.acceptEach()
            data.append(": ")
            preamble = data.substring(start)
          }
          doAccept(typeChild)
          if (block != null) {
            data.append(block!!)
          } else {
            val start = data.length()
            param.nextSibling?.withSuccessors()?.acceptEach()
            block = data.substring(start)
          }
        }
        else -> doAccept(typeChild)
      }
    }
  }

  override fun visitClass(node: PsiClass) {
    seen(node)

    val companionOut = StringBuilder()
    with(Psi2kTranslator(data.withCompanion(companionOut), seen, nullness)) {
      node
        .children()
        .takeWhile { it != node.rBrace }
        .replaceNotNull { child ->
          when (child.tokenOrNull()) {
            PsiKeyword.ENUM -> "enum class"
            "@" -> "annotation "
            PsiKeyword.INTERFACE -> if (node.isAnnotationType) "class" else "interface"
            "{" -> if (node.isAnnotationType) "(" else "{"
            "," ->
              if (
                child.prevSibling?.withPredecessors()?.firstOrNull {
                  it !is PsiWhiteSpace && it !is PsiComment
                } is PsiEnumConstant // subtype of PsiField, so can't test for PsiField
              ) {
                ","
              } else {
                "\n" // int x, y ==> x: Int\ny: Int
              }
            else -> null
          }
        }
    }

    if (node.isAnnotationType) data.append(") {\n")

    // TODO(b/353274410): disambiguate methods inherited from superclass and interfaces

    if (companionOut.isNotEmpty()) {
      data.append("companion object {\n")
      data.append(companionOut)
      data.append("}\n")
    }

    node.rBrace?.withSuccessors()?.acceptEach()
  }

  override fun visitClassInitializer(node: PsiClassInitializer) {
    seen(node)

    // Initializer blocks become `init {...}` blocks. Be sure to redirect `static {...}` to
    // companion object, but note initializer blocks can also occur without `static`, e.g., (rarely)
    // in anonymous classes.

    companionIfStatic(node) {
      data.append("init ")
      node.children().acceptEach() { it != node.modifierList }
    }
  }

  private inline fun companionIfStatic(
    node: PsiModifierListOwner,
    block: Psi2kTranslator.() -> Unit,
  ) {
    with(if (node.isStatic) Psi2kTranslator(data.companion(), seen, nullness) else this) {
      block()
      // Add trailing line feed if we're writing to the companion, since trailing whitespace won't
      // TODO(b/273549101): consider appending the actual whitespace that follows the given node
      if (node.isStatic) data.append("\n")
    }
  }

  override fun visitClassObjectAccessExpression(expression: PsiClassObjectAccessExpression) {
    // Foo.class ==> Foo::class.java
    // Except if Foo.class is within an annotation instantiation parameter list,
    // then the .java is omitted, e.g., Bar(Foo.class) ==> Bar(Foo::class)
    seen(expression)
    var suffix: String? = null
    expression.children().replaceNotNull {
      runIf(it.tokenOrNull() == ".") {
        if (expression.parent !is PsiNameValuePair) suffix = ".java"
        "::"
      }
    }
    data.appendNonNull(suffix)
  }

  override fun visitConditionalExpression(expression: PsiConditionalExpression) {
    // cond ? ... : ... ==> if (cond) ... else ...
    seen(expression)
    data.append("if (")
    expression.children().replaceNotNull {
      when (it.tokenOrNull()) {
        "?" -> ") "
        ":" -> " else "
        else -> null
      }
    }
  }

  override fun visitContinueStatement(statement: PsiContinueStatement) {
    val label = statement.labelIdentifier
    if (label != null) {
      visitLabeledJump(statement, keyword = "continue", label)
    } else {
      super.visitContinueStatement(statement)
    }
  }

  override fun visitDeclarationStatement(statement: PsiDeclarationStatement) {
    // int foo, bar ==> foo: Int\nbar: Int
    seen(statement)
    statement.children().replaceNotNull { runIf(it.tokenOrNull() == ",") { "\n" } }
  }

  override fun visitEnumConstant(node: PsiEnumConstant) {
    // super would delegate to visitField, which would try to use companion object
    visitElement(node)
  }

  override fun visitExpressionList(list: PsiExpressionList) {
    // For expression lists that are call arguments, see if we need to insert spread operator (`*`).
    // Note PsiMethodCall, PsiConstructorCall, and PsiEnumConstant have args, so it's convenient
    // to do this in visitExpressionList, but this element also appears in other contexts.
    val callee = (list.parent as? PsiCall)?.resolveMethod()
    // TODO(b/331194042): widening primitive conversions during invocations (JSL ch 5.3)
    if (callee?.isVarArgs == true && callee.parameterList.parametersCount == list.expressionCount) {
      // Since callee has vararg param and there are as many args as params, last arg must exist
      val lastArg = list.expressions[list.expressionCount - 1]
      // Insert spread operator (`*`) if lastArg is an array, which we test here by comparing the
      // (type parameter substituted) parameter type with lastArg's type. This works because the
      // vararg parameter's type is the encoded array type.
      val paramType =
        (list.parent as? PsiCall)?.resolveMethodGenerics()?.substitutor?.let {
          callee.getSignature(it).parameterTypes.lastOrNull()
        } ?: callee.parameterList.getParameter(callee.parameterList.parametersCount - 1)?.type
      if (lastArg.type?.let { argType -> paramType?.isAssignableFrom(argType) } == true) {
        seen(list)
        list.children().forEach { child ->
          if (child == lastArg) data.append("*")
          doAccept(child)
        }
        return
      }
    }
    super.visitExpressionList(list)
  }

  override fun visitExpressionStatement(statement: PsiExpressionStatement) {
    // can get double-visits for this(...)/super(...) expressions
    if (statement in seen) {
      check(
        (statement.parent?.parent as? PsiMethod)?.isConstructor == true &&
          (statement.parent as? PsiCodeBlock)?.let {
            !it.isEmpty && it.statements[0] == statement
          } == true
      ) {
        "$statement ${statement.textRange} visited twice: ```${statement.text}```"
      }
    } else {
      super.visitExpressionStatement(statement)
    }
  }

  override fun visitField(node: PsiField) {
    seen(node)

    companionIfStatic(node) { translateVariable(node, if (node.isFinal) "val " else "var ") }
  }

  override fun visitForStatement(statement: PsiForStatement) {
    // for (<init>; <condition>; <update>) {...} ==> <init>; while (<condition>) { ...; <update> }
    seen(statement)

    statement.initialization?.let { init ->
      // Surround with `run {...}` so variable declarations don't escape
      data.append("run {\n")
      doAccept(init)
      data.append("\n")
    }
    statement
      .children()
      .filter { it != statement.initialization && it != statement.update }
      .forEach { child ->
        val token = child.tokenOrNull()
        when {
          token == PsiKeyword.FOR -> {
            seen(child)
            data.append("while")
          }
          token == ";" -> seen(child) // skip
          child == statement.body && statement.update != null -> {
            if (child is PsiBlockStatement) {
              // Body is a block -> add updates to end of it
              seen(child)
              child.children().forEach { blockChild ->
                if (blockChild is PsiCodeBlock) {
                  seen(blockChild)
                  blockChild.children().forEach {
                    if (it == blockChild.rBrace) doAccept(statement.update)
                    doAccept(it)
                  }
                } else {
                  doAccept(blockChild)
                }
              }
            } else {
              // Body is a statement -> turn into a block with updates
              data.append(" { ")
              doAccept(child)
              doAccept(statement.update)
              data.append(" }")
            }
          }
          else -> doAccept(child)
        }
      }
    if (statement.initialization != null) data.append("\n}")
  }

  override fun visitForeachStatement(statement: PsiForeachStatement) {
    // for (Foo x : foos) <block> ==> for (x: Foo in foos) <block>
    seen(statement)
    statement.children().replaceNotNull { runIf(it.tokenOrNull() == ":") { "in" } }
  }

  override fun visitIdentifier(identifier: PsiIdentifier) {
    val token = quoteIfNeeded(identifier.text)
    seen[identifier] = token
    data.append(token)
  }

  /** Quote any identifiers that are Kotlin keywords, e.g. `when`. */
  private fun quoteIfNeeded(identifier: String): String =
    if (identifier in KOTLIN_KEYWORDS || '$' in identifier) "`$identifier`" else identifier

  override fun visitImportStatement(statement: PsiImportStatement) {
    val import = statement.qualifiedName
    if (MAPPED_TYPES.containsKey(import)) {
      skipRecursive(statement)
    } else {
      super.visitImportStatement(statement)
    }
  }

  override fun visitImportStaticStatement(statement: PsiImportStaticStatement) {
    // Drop `static`, quite simply (auto-formatter will resort imports)
    seen(statement)
    statement.children().acceptEach { it.text != PsiKeyword.STATIC }
  }

  override fun visitImportStaticReferenceElement(reference: PsiImportStaticReferenceElement) {
    seen(reference)
    reference.children().forEach { child ->
      doAccept(child)
      if (child == reference.classReference) {
        val targetClass = reference.classReference.resolve()
        if (isTranslated(targetClass)) {
          // If the referenced class is also being translated, rewrite
          // import my.pkg.Foo.bar ==> import my.pkg.Foo.Companion.bar
          // since we move static declarations into the companion object (see visitMethod etc.)
          data.append(".Companion")
        }
      }
    }
  }

  private fun isTranslated(element: PsiElement?): Boolean {
    // For now, consider elements defined in non-binary Java files translated, as that should mean
    // they're in the same module, but includes generated files which we may not want.
    return element?.language == JavaLanguage.INSTANCE && !element.containingFile.fileType.isBinary
  }

  override fun visitJavaToken(token: PsiJavaToken) {
    when (token.text) {
      PsiKeyword.NEW -> seen(token) // `new` doesn't translate to Kotlin, so just skip it
      PsiKeyword.INSTANCEOF -> token.replaceWith("is") // x instanceof Foo ==> x is Foo
      PsiKeyword.SWITCH -> token.replaceWith("when")
      else -> super.visitJavaToken(token)
    }
  }

  override fun visitLabeledStatement(statement: PsiLabeledStatement) {
    seen(statement)
    statement.children().replaceNotNull {
      // label: ==> label@
      if (it.tokenOrNull() == ":") "@" else null
    }
  }

  override fun visitLambdaExpression(expression: PsiLambdaExpression) {
    seen(expression)
    // (x, y) -> ... ==> { x, y -> ... }
    // TODO(b/339097382): insert SAM conversion type when needed (e.g., outside method calls)
    // https://kotlinlang.org/docs/java-interop.html#sam-conversions
    // Note that the type would need to be translated to Kotlin also, so this is too simplistic:
    // expression.functionalInterfaceType?.let {
    //   data.append(it.canonicalText)
    // }
    data.append(" { ")
    expression.children().acceptEach {
      if (it == expression.body && it is PsiCodeBlock) {
        // Surround statement body with run {...} for simplicity
        // TODO(b/273549101): also use run {...} for standalone blocks {...}
        data.append(" run ")
        true
      } else {
        // Omit arrow if there are no parameters
        it.tokenOrNull() != "->" || !expression.parameterList.isEmpty
      }
    }
    data.append("} ")
  }

  override fun visitLiteralExpression(expression: PsiLiteralExpression) {
    // https://kotlinlang.org/docs/numbers.html#literal-constants-for-numbers
    var literalText = expression.text
    when {
      expression.type == PsiTypes.doubleType() && literalText.endsWith("d", ignoreCase = true) -> {
        // There's no 0d suffix in Kotlin (b/282171141), so use .0 instead. We could alternatively
        // do this in visitJavaToken, somehow still making sure we're looking at a double literal.
        literalText = literalText.dropLast(1)
        if ('.' !in literalText) literalText += ".0"
        expression.replaceWith(literalText)
      }
      // Interpreting Java text blocks is complex, and preserving them can be wrong (b/379714480),
      // so convert to their effective value for clarity and simplicity for now.
      expression.isTextBlock -> expression.replaceWith("\"\"\"${expression.value}\"\"\"")
      expression.type?.equalsToText("java.lang.String") == true -> {
        // `$` need to be escaped if followed by a legal Kotlin identifier
        // https://kotlinlang.org/docs/reference/grammar.html#Identifier
        val escapeDollar = Regex("\\\$(?=[a-z_]|`[^\\r\\n`]+`)")
        expression.replaceWith(literalText.replace(escapeDollar, Regex.escapeReplacement("\\\$")))
      }
      else -> super.visitLiteralExpression(expression)
    }
  }

  override fun visitLocalVariable(node: PsiLocalVariable) {
    seen(node)
    translateVariable(node, if (node.isEffectivelyFinal()) "val " else "var ")
  }

  private fun translateVariable(node: PsiVariable, namePrefix: String?) {
    // If we previously visited this variable's modifier list, it can't be a child of this node, so
    // just print it. This happens with variable declaration lists `private int x, y`.
    node.modifierList?.let { modifierList -> seen[modifierList]?.let { data.append("$it ") } }

    val initializerConversion = assignmentConversion(node.type, node.initializer)
    node
      .children()
      .filter { it != node.typeElement }
      .forEach { child ->
        if (child == node.nameIdentifier) {
          data.appendNonNull(namePrefix)
        }
        if (
          node is PsiField && !node.hasInitializer() && !node.isFinal && child.tokenOrNull() == ";"
        ) {
          // TODO(b/288326136): only insert default initializer if not assigned in all constructors
          val primitiveInitializer = PRIMITIVE_INITIALIZER[node.type]
          if (primitiveInitializer != null) {
            data.append(" = $primitiveInitializer")
          } else if (seen.getOrNull(node.typeElement)?.endsWith("?") == true) {
            data.append(" = null") // initialize non-final nullable fields with null
          }
        }
        if (initializerConversion != null && child == node.initializer) {
          child.encloseInParensIfNeeded(initializerConversion)
        } else {
          doAccept(child)
        }
        if (child == node.nameIdentifier) {
          visitTypeDeclaration(node.typeElement) { !it.isInferredType }
        }
      }
  }

  private inline fun visitTypeDeclaration(
    typeElement: PsiTypeElement?,
    shouldVisit: (PsiTypeElement) -> Boolean = { _ -> true },
  ) {
    if (typeElement == null) return
    if (shouldVisit(typeElement)) {
      data.append(": ")
      // See if we already have text for the type element, and if not, update seen map to contain it
      // to limit the tracking of PsiTypeElement strings to those used in declarations for now.
      val canned = seen[typeElement]
      if (canned != null) {
        data.append(canned)
      } else {
        val start = data.length()
        doAccept(typeElement)
        seen[typeElement] = data.substring(start)
      }
    } else {
      skipRecursive(typeElement)
    }
  }

  override fun visitMethod(node: PsiMethod) {
    seen(node)

    // Omit generated enum methods values(), valueOf("...")
    if (
      node.isStatic &&
        (node.parent as? PsiClass)?.isEnum == true &&
        ((node.name == "values" && !node.hasParameters()) ||
          (node.name == "valueOf" &&
            node.parameterList.parametersCount == 1 &&
            node.parameterList.getParameter(0)!!.type.equalsToText("java.lang.String")))
    ) {
      return
    }

    companionIfStatic(node) {
      val insertFunBefore: PsiElement = // never null
        node.typeParameterList ?: node.nameIdentifier ?: node.parameterList
      node
        .children()
        .filter { it != node.throwsList && it != node.returnTypeElement }
        .forEach { child ->
          when (child) {
            node.modifierList -> doAccept(node.throwsList)
            // TODO(b/273549101): insert `operator` keyword as needed to not break callers
            insertFunBefore -> data.append(if (node.isConstructor) " constructor" else " fun ")
          }

          child.acceptIfNot(node.isConstructor && child == node.nameIdentifier)

          if (child == node.parameterList) {
            // Minor beautification: don't print "Unit" return types
            visitTypeDeclaration(node.returnTypeElement) { it.type != PsiTypes.voidType() }

            if (node.isConstructor) {
              // See if we have a constructor delegation to translate. If so translate it here and
              // mark it as seen, and later ignore it when we visit the method body.
              // The ignoring happens in visitExpressionStatement.
              val firstStatement = node.body?.statements?.getOrNull(0)
              if (
                firstStatement != null &&
                  (firstStatement.firstChild as? PsiMethodCallExpression)?.methodExpression?.text in
                    DELEGATION_KEYWORDS
              ) {
                data.append(" : ")
                seen(firstStatement)
                firstStatement.children().acceptEach {
                  // preserve everything but the semicolon, which would confuse the parser in this
                  // case
                  it.tokenOrNull() != ";"
                }
              }
            }
          }
        }
    }
  }

  override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
    val mappedMethod =
      expression.methodExpression.referenceName?.let { methodRef ->
        MappedMethod.get(methodRef, expression.resolveMethod()?.containingClass)
      }
    if (mappedMethod == null) {
      super.visitMethodCallExpression(expression)
      return
    }

    val extension =
      if (mappedMethod.type == MappingType.EXTENSION) {
        expression.argumentList.children().firstOrNull { it is PsiExpression }
      } else {
        null
      }

    if (extension != null && expression.methodExpression.qualifierExpression == null) {
      extension.encloseInParensIfNeeded()
      data.append(".")
    }
    seen(expression)
    expression.children().forEach { child ->
      if (child == expression.methodExpression) {
        seen(child)
        for (methodElement in child.children()) {
          when {
            methodElement == expression.methodExpression.referenceNameElement ->
              methodElement.replaceWith(mappedMethod.kotlinName)
            extension != null && methodElement == expression.methodExpression.qualifierExpression ->
              extension.encloseInParensIfNeeded { methodElement.replaceWith(extension) }
            else -> doAccept(methodElement)
          }
        }
      } else if (mappedMethod.type == MappingType.PROPERTY && child == expression.argumentList) {
        skipRecursive(child)
      } else if (mappedMethod.type == MappingType.EXTENSION && child == expression.argumentList) {
        seen(child)
        // See b/317058801: to map vararg methods such as String.format, would need to insert *s as
        // needed below as in visitExpressionList
        check(expression.resolveMethod()?.isVarArgs != true) {
          "TODO: can't map vararg method call ${expression.text} ${expression.textRange}"
        }
        for (listElement in child.children()) {
          // First argument is "skipped" but was accepted earlier as part of the replacement.
          // Skip the comma (if present) immediately after it.
          if (listElement.isToken(",") && listElement.prevSibling == extension) {
            skipRecursive(listElement)
          } else if (listElement != extension) {
            doAccept(listElement)
          }
        }
      } else {
        doAccept(child)
      }
    }
  }

  /**
   * Emits `(` and `)` before and after the given [block], which by default [accepts][doAccept] this
   * element, unless the element doesn't need parentheses to be a method call receiver. Could be
   * smarter but initially emits parens except for some expression types that shouldn't need parens.
   */
  private inline fun PsiElement.encloseInParensIfNeeded(
    suffixToAppend: String = "",
    block: (PsiElement) -> Unit = { doAccept(it) },
  ) {
    val addParens =
      this !is PsiLiteralExpression &&
        this !is PsiMethodCallExpression &&
        this !is PsiParenthesizedExpression &&
        this !is PsiReferenceExpression
    if (addParens) data.append("(")
    block(this)
    if (addParens) data.append(")")
    data.append(suffixToAppend)
  }

  override fun visitMethodReferenceExpression(expression: PsiMethodReferenceExpression) {
    if (expression.isConstructor) {
      val constructor = expression.resolve() as? PsiMethod
      val typeRef = expression.qualifierType?.innermostComponentReferenceElement
      // TODO(rmwang,kmb): use typeRef.typeParameterCount once it's available
      if (typeRef != null && typeRef.typeParameters.isNotEmpty() && constructor != null) {
        // Foo<T>::new ==> {a,... -> Foo(a,...)}
        // TODO(b/273549101) If we know kotlin can deduce the type parameter we can just omit it.
        val params = constructor.parameterList.parameters.map { it.name }
        val paramsList = params.joinToString(",")
        val lambda =
          "{$paramsList ${if(paramsList.isNotEmpty()) "->" else ""} ${typeRef.referenceName}($paramsList)}"
        expression.replaceWith(lambda)
      } else {
        // Foo::new ==> ::Foo (https://kotlinlang.org/docs/reflection.html#constructor-references)
        // by visiting children in reverse and relying on visitJavaToken dropping `new` keywords
        val genericPattern = Regex("<.*>")
        with(intercepting()) {
          seen(expression)
          expression.lastChild?.withPredecessors()?.acceptEach()
          // Foo[]::new ==> ::Array as Kotlin should be able to infer the type
          acceptResult { buffer.toString().replace(genericPattern, "") }
        }
      }
    } else {
      // TODO(b/273549101): method references to methods in generic classes need explicit type
      // arguments
      super.visitMethodReferenceExpression(expression)
    }
  }

  override fun visitModifierList(node: PsiModifierList) {
    val start = data.length()
    val parent = node.parent

    if (node.hasModifierProperty(PsiModifier.SYNCHRONIZED)) data.append("@Synchronized ")
    if (node.hasModifierProperty(PsiModifier.TRANSIENT)) data.append("@Transient ")
    if (node.hasModifierProperty(PsiModifier.VOLATILE)) data.append("@Volatile ")
    if (parent is PsiMethod && node.isStatic) data.append("@JvmStatic ")
    if (parent is PsiField && parent !is PsiEnumConstant && !node.isPrivate) {
      data.append("@JvmField ")
    }

    node.children().acceptEach {
      when (it) {
        is PsiKeyword ->
          when (it.text) {
            // static, volatile, transient handled above and with companion; default not needed
            PsiKeyword.DEFAULT,
            PsiKeyword.STATIC,
            PsiKeyword.SYNCHRONIZED,
            PsiKeyword.TRANSIENT,
            PsiKeyword.VOLATILE -> false
            // method parameters are always final; other variables are declared as val/var
            PsiKeyword.FINAL -> parent !is PsiVariable
            // Suppress `private` in private classes, which are accessible in Java but not Kotlin
            // TODO(kmb): consider only doing this if member is used from outside containing class
            PsiKeyword.PRIVATE -> parent !is PsiMember || parent.containingClass?.isPrivate == false
            else -> true
          }
        is PsiAnnotation -> {
          // Drop @Override, @Nullable, @NonNull: become override modifier, nullable/nonnull type
          it.qualifiedName != JAVA_LANG_OVERRIDE && !it.isNullableAnno() && !it.isNonNullAnno()
        }
        else -> true
      }
    }

    // Use modifier order from https://kotlinlang.org/docs/coding-conventions.html#modifiers-order
    if (
      parent !is PsiParameter &&
        parent !is PsiLocalVariable &&
        node.hasModifierProperty(PsiModifier.PACKAGE_LOCAL)
    ) {
      data.append(" internal ")
    }
    if (
      parent !is PsiVariable &&
        (if (parent is PsiClass) !parent.isEnum else !node.isPrivate && !node.isStatic) &&
        (parent as? PsiMethod)?.isConstructor != true &&
        !node.hasModifierProperty(PsiModifier.FINAL) &&
        !node.hasModifierProperty(PsiModifier.ABSTRACT) &&
        (parent?.parent as? PsiClass)?.isFinal != true
    ) {
      data.append(" open ")
    }
    if (node.hasAnnotation(JAVA_LANG_OVERRIDE)) data.append(" override ")
    if (parent is PsiClass && parent.parent !is PsiFile && !node.isStatic) {
      data.append(" inner ")
    }

    seen(node, data.substring(start))
  }

  override fun visitNameValuePair(pair: PsiNameValuePair) {
    // @Foo(attr = <value>) ==> @Foo(attr = [<value>]) if attr is array-typed
    if (
      pair.nameIdentifier != null &&
        ((pair.reference?.resolve() as? PsiAnnotationMethod)?.returnType?.arrayDimensions ?: 0) > 0
    ) {
      seen(pair)
      pair.children().forEach {
        val addBrackets = it is PsiAnnotationMemberValue && it !is PsiArrayInitializerMemberValue
        if (addBrackets) data.append("[")
        doAccept(it)
        if (addBrackets) data.append("]")
      }
    } else {
      super.visitNameValuePair(pair)
    }
  }

  override fun visitNewExpression(expression: PsiNewExpression) {
    if (expression.isArrayCreation) {
      seen(expression)
      val componentType = (expression.type as? PsiArrayType)?.componentType
      val dims = expression.arrayDimensions
      if (dims.isNotEmpty()) {
        // new prim[x] ==> PrimArray(x)
        // new Foo[0] ==> emptyArray<Foo>()
        // new Foo[x] ==> arrayOfNulls<Foo>(x)
        // new <type>[x][y] ==> Array(x) { <translation of `new <type>[y]`> }
        // new <type>[x][] ==> arrayOfNulls<NestedArrayType>(x)
        val lastDim = dims.last()
        var nextComponent = componentType
        expression
          .children()
          .filter { it != expression.classReference }
          .forEach { child ->
            when (child) {
              lastDim -> {
                val isEmptyArray = lastDim.constantValue() == 0
                data.append(
                  when {
                    nextComponent is PsiPrimitiveType ->
                      "${checkNotNull(PRIMITIVE_TYPES[nextComponent])}Array"
                    isEmptyArray -> "emptyArray<"
                    else -> "arrayOfNulls<"
                  }
                )
                if (nextComponent !is PsiPrimitiveType) {
                  // Print nested array dimensions as Array<...>
                  val deepComponentType = nextComponent?.deepComponentType
                  var nestedDimensionCount = (nextComponent?.arrayDimensions ?: 0) - dims.size
                  if (deepComponentType !is PsiPrimitiveType) ++nestedDimensionCount
                  for (dim in 0..<nestedDimensionCount) data.append("Array<")
                  if (deepComponentType is PsiPrimitiveType) {
                    data.append("${checkNotNull(PRIMITIVE_TYPES[deepComponentType])}Array")
                  } else {
                    doAccept(expression.classReference)
                  }
                  for (dim in 0..<nestedDimensionCount) data.append(">")
                  data.append(">")
                }
                data.append("(")
                child.acceptIf(!isEmptyArray || nextComponent is PsiPrimitiveType)
                data.append(")")
              }
              in dims -> {
                data.append("Array(")
                doAccept(child)
                data.append(") {")
                nextComponent = (nextComponent as? PsiArrayType)?.componentType
              }
              else -> skipRecursive(child)
            }
          }
        for (dim in 1..<dims.size) data.append(" }")
      } else {
        // new prim[] {...} ==> primArrayOf(...)
        // new Foo[] {...} ==> arrayOf<Foo>(...)
        data.append(
          if (componentType is PsiPrimitiveType) "${componentType.name}ArrayOf" else "arrayOf"
        )
        expression.children().forEach { child ->
          when (child) {
            expression.classReference -> {
              // Won't get here for primitive arrays (type appears as a PsiKeyword child instead)
              data.append("<")
              // Print nested array dimensions as Array<...> and skip []
              val nestedDimensionCount = componentType?.arrayDimensions ?: 0
              for (dim in 0..<nestedDimensionCount) data.append("Array<")
              doAccept(child)
              for (dim in 0..<nestedDimensionCount) data.append(">")
              data.append(">")
            }
            expression.arrayInitializer -> {
              data.append("(")
              doAccept(child)
              data.append(")")
            }
            else -> skipRecursive(child)
          }
        }
      }
    } else {
      super.visitNewExpression(expression)
      // Note: this leaves raw types unchanged, may require manual fix
    }
  }

  override fun visitParameter(node: PsiParameter) {
    seen(node)

    translateVariable(node, runIf(node.isVarArgs) { "vararg " })

    // TODO(b/347273856): handle parameters that aren't effectively final
  }

  override fun visitParameterList(list: PsiParameterList) {
    seen(list)
    val omitParens =
      list.parent is PsiLambdaExpression ||
        (list.parent is PsiAnnotationMethod && list.parametersCount == 0)
    // Omit parens in lambda parameter lists. Also omit them for annotation methods (which should
    // always be empty), since we turn those into vals, but do visit so any comments are preserved.
    list.children().acceptEach { !omitParens || (it as? PsiJavaToken)?.text !in setOf("(", ")") }
  }

  override fun visitPolyadicExpression(expression: PsiPolyadicExpression) {
    visitBinaryChain(
      expression,
      expression.operands.toList(),
      operationSigns = expression.operands.mapNotNull { expression.getTokenBeforeOperand(it) },
    )
  }

  /**
   * Rewrites [PsiBinaryExpression] and [PsiPolyadicExpression] (chained binary) expressions.
   *
   * @param operands [PsiPolyadicExpression.getOperands] or [PsiBinaryExpression]'s lhs and rhs
   * @param operationSigns operators between [operands] in the same order (1 less than [operands])
   */
  private fun visitBinaryChain(
    expression: PsiPolyadicExpression,
    operands: List<PsiExpression>,
    operationSigns: List<PsiJavaToken>,
  ) {
    val operator = operationSigns.firstOrNull()?.text ?: ""
    if (operands.size >= 3) {
      val (first, second) = operands
      var prefixType =
        calcTypeForBinaryExpression(
          first.type,
          second.type,
          expression.operationTokenType,
          /*accessLType=*/ true,
        )
      for (rhs in operands.subList(2, operands.size)) {
        if (!isNumericType(prefixType)) break
        if (binaryOperandConversion(prefixType, operator, rhs.type) != null) {
          data.append("(") // will apply conversion to parenthesized expression below
        }
        prefixType =
          calcTypeForBinaryExpression(
            prefixType,
            rhs.type,
            expression.operationTokenType,
            /*accessLType=*/ true,
          )
      }
    }

    seen(expression)
    var nextOperand = 0
    var prefixType: PsiType? = operands.getOrNull(0)?.type
    val operators = operationSigns.toSet()
    expression
      .children()
      .filter { it !in operators }
      .forEach { child ->
        val lhs = operands.getOrNull(nextOperand)
        if (child != lhs) {
          doAccept(child)
          return@forEach
        }

        val rhs = operands.getOrNull(nextOperand + 1)
        val operandConversion: String? =
          if (nextOperand == 0) {
            // First operand: compute its conversion as lhs
            binaryOperandConversion(lhs.type, operator, rhs?.type)
          } else {
            // Subsequent operand: compute conversion as rhs
            binaryOperandConversion(prefixType, operator, lhs.type, forRhs = true)
          }
        if (operandConversion != null) {
          child.encloseInParensIfNeeded(suffixToAppend = operandConversion)
        } else {
          doAccept(child)
        }

        if (nextOperand != 0 && rhs != null) {
          // Subsequent operand: see if the prefix so far needs a conversion, e.g.,
          // (5 + 7).toLong() + 42L. Should match any opening `(` we added above.
          prefixType =
            calcTypeForBinaryExpression(
              prefixType,
              lhs.type,
              expression.operationTokenType,
              /*accessLType=*/ true,
            )
          binaryOperandConversion(prefixType, operator, rhs.type)?.let { data.append(")$it") }
        }

        // Move operator before any comments and whitespace preceding it. Line feeds before
        // binary operators can confuse Kotlin's parser into thinking what follows is a unary
        // operator. Kotlin's style guide requires line feeds after operators anyway.
        val op = operationSigns.getOrNull(nextOperand) ?: /* last operator */ return@forEach
        data.append(" ")
        op.replaceNotNull(mapBinaryOperator(lhs, op, rhs))
        ++nextOperand
      }
  }

  /**
   * Returns the operator to use for the given binary operation if different from [op], e.g., will
   * return `shl` if [op] is `<<` (see [MAPPED_OPERATORS]) or `===` for reference equality.
   */
  private fun mapBinaryOperator(
    lhs: PsiExpression,
    op: PsiJavaToken,
    rhs: PsiExpression?,
  ): String? =
    MAPPED_OPERATORS[op.text]
      ?: runIf(
        (op.text == "==" || op.text == "!=") &&
          lhs.type !is PsiPrimitiveType &&
          rhs?.type !is PsiPrimitiveType
      ) {
        // Preserve reference equality comparing non-primitive values. Note null is a
        // PsiPrimitiveType, so this won't kick in on x == null etc.
        "${op.text}="
      }

  /**
   * Returns any implicit conversion to apply to one of the operands of the given binary operation,
   * according to [JLS ch. 5](https://docs.oracle.com/javase/specs/jls/se11/html/jls-5.html).
   *
   * @param lhsType type of the left-hand side expression or `null` if unknown
   * @param operator the binary operator, e.g., "+"
   * @param rhsType type of the right-hand side expression or `null` if unknown
   * @param forRhs if `true`, returned conversion is for [rhsType], otherwise for [lhsType]
   * @return conversion to apply or `null` if there's no conversion needed
   */
  private fun binaryOperandConversion(
    lhsType: PsiType?,
    operator: String,
    rhsType: PsiType?,
    forRhs: Boolean = false,
  ): String? =
    when {
      // String-convert lhs if this is a string concatenation per JLS 5.4. Kotlin overloads
      // String.plus, so we don't need to explicitly convert rhs if lhs is a string, only vice versa
      !forRhs &&
        operator == "+" &&
        rhsType?.equalsToText("java.lang.String") == true &&
        lhsType?.equalsToText("java.lang.String") == false -> ".toString()"
      operator in BINARY_PROMOTION_OPS -> {
        val unboxedLhsType = PsiPrimitiveType.getOptionallyUnboxedType(lhsType)
        val unboxedRhsType = PsiPrimitiveType.getOptionallyUnboxedType(rhsType)
        // Binary promotion of numeric operands per JLS 5.6.2
        runIf(isNumericType(unboxedLhsType) && isNumericType(unboxedRhsType)) {
          if (
            ((operator == "==" || operator == "!=") && unboxedLhsType == unboxedRhsType) ||
              (operator in ARITHMETIC_OPS &&
                unboxedLhsType != PsiTypes.charType() &&
                unboxedRhsType != PsiTypes.charType())
          ) {
            // Explicit conversion not needed for == / != on matching types. +, -, *, /, %, and
            // comparison (<, <=, >, >=, but *not* ==/!=) operators are defined for all primitive
            // Number subtypes (but *not* Char), so don't need explicit conversions either, see,
            // e.g., https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte/plus.html.
            return null
          }
          val targetType = unboxAndBalanceTypes(lhsType, rhsType)
          val toConvert = if (forRhs) unboxedRhsType else unboxedLhsType
          runIf(targetType != toConvert) { ".to${PRIMITIVE_TYPES[targetType]}()" }
        }
      }
      // Unary promotion of integral operands per JLS 5.6.1
      operator in UNARY_PROMOTION_BINOPS -> unaryPromotion(if (forRhs) rhsType else lhsType)
      else -> null
    }

  override fun visitReferenceElement(reference: PsiJavaCodeReferenceElement) {
    val replace = MAPPED_TYPES[reference.qualifiedName]
    if (replace != null) {
      seen(reference)
      reference.children().replaceNotNull {
        runIf(it == reference.referenceNameElement) { replace }
      }
    } else {
      super.visitReferenceElement(reference)
    }

    // References can include (type) annotations as children, so see if we have a nullable type.
    // Consistently with elsewhere we don't drop @Nullable type annotations above, just add ?.
    if (reference.children().any { it is PsiAnnotation && it.isNullableAnno() }) {
      data.append("?")
    }
  }

  override fun visitReferenceExpression(expression: PsiReferenceExpression) {
    // This in particular includes variable and method references
    seen(expression)
    expression
      .children()
      .filter { it != expression.parameterList }
      .replaceNotNull {
        // <array>.length ==> <array>.size
        runIf(
          it == expression.referenceNameElement &&
            expression.referenceName == "length" &&
            ((expression.qualifier as? PsiExpression)?.type?.arrayDimensions ?: 0) > 0
        ) {
          "size"
        }
      }
    // type parameters (skipped above) last if any: Foo.<Bar>baz(...) ==> Foo.baz<Bar>(...)
    doAccept(expression.parameterList)
  }

  override fun visitReferenceList(list: PsiReferenceList) {
    seen(list)
    var suffix: String? = null
    list.children().forEach { child ->
      if (child is PsiKeyword) {
        val token =
          when (child.text) {
            PsiKeyword.EXTENDS -> ":"
            PsiKeyword.IMPLEMENTS -> {
              if ((list.parent as? PsiClass)?.extendsList?.referenceElements.isNullOrEmpty()) {
                ":"
              } else {
                ","
              }
            }
            PsiKeyword.THROWS -> {
              suffix = ") "
              "@Throws("
            }
            else -> TODO("Unexpected reference list: ${list.text} ${list.textRange}")
          }
        child.replaceWith(token)
      } else {
        doAccept(child)
        if (child is PsiJavaReference) {
          when (list.role) {
            PsiReferenceList.Role.THROWS_LIST -> data.append("::class")
            PsiReferenceList.Role.EXTENDS_LIST -> {
              val classDecl = list.parent as? PsiClass
              if (classDecl?.isInterface == false && classDecl.constructors.isEmpty()) {
                // If this is a class with only an implicit default constructor, call its super
                // constructor here (b/282171141) to avoid needing explicit no-arg constructor.
                data.append("()")
              }
            }
            else -> {}
          }
        }
      }
    }
    data.appendNonNull(suffix)
  }

  override fun visitReferenceParameterList(list: PsiReferenceParameterList) {
    val elements = list.typeParameterElements
    if (elements.isNotEmpty() && elements.all { it.type is PsiDiamondType }) {
      // Omit <> (diamond type parameter list) entirely
      skipRecursive(list)
    } else {
      super.visitReferenceParameterList(list)
    }
  }

  override fun visitResourceExpression(expression: PsiResourceExpression) {
    // With visitTry, try (expr) {...} ==> expr.use { _ -> ... }
    super.visitResourceExpression(expression)
    data.append(".use { _ -> ")
  }

  override fun visitResourceList(resourceList: PsiResourceList) {
    seen(resourceList)
    resourceList.children().acceptEach { it.tokenOrNull() !in setOf(",", "(", ")") }
  }

  override fun visitResourceVariable(variable: PsiResourceVariable) {
    seen(variable)
    doAccept(variable.initializer)
    data.append(".use { ")
    variable
      .children()
      .filter { it != variable.initializer && it != variable.typeElement }
      .acceptEach { it.tokenOrNull() != "=" }
    visitTypeDeclaration(variable.typeElement) { !it.isInferredType }
    data.append(" -> ")
  }

  override fun visitReturnStatement(statement: PsiReturnStatement) {
    if (
      statement.getParentOfType<PsiLambdaExpression>(strict = true, PsiMethod::class.java) != null
    ) {
      seen(statement)
      statement.children().replaceNotNull {
        // Return to run {...} block inserted by visitLambdaExpression
        runIf(it.tokenOrNull() == PsiKeyword.RETURN) { "return@run" }
      }
    } else {
      super.visitReturnStatement(statement)
    }
  }

  override fun visitSwitchStatement(statement: PsiSwitchStatement) {
    // Roughly: [switch (<expr>) { case A: case B: <stmts> default: <more> }]
    // ==> when ([expr]) { A, B -> { [stmts] } else -> [more] }
    // where we also want to drop break statements as they're meaningless. Since there is no
    // fallthrough in Kotlin `when` expressions, we duplicate code blocks as needed.
    seen(statement)
    statement.children().forEach { child ->
      if (child is PsiCodeBlock) {
        seen(child)
        // Need UAST representation to use canFallthroughTo below
        val uast = statement.toUElement(USwitchExpression::class.java)
        var firstCase = true
        var needBlockClose = false
        child.children().forEach { blockElem ->
          if (blockElem is PsiSwitchLabelStatement) {
            seen(blockElem)
            if (needBlockClose) {
              if (uast?.canFallthroughTo(blockElem) != false) {
                // If fallthrough from previous code block is possible, translate subsequent code
                // using separate translator so the visited nodes don't count as "seen" for the main
                // translation. Note we can fall through to subsequent code blocks as well, so just
                // keep going as long as fallthrough is possible, until we reach the end of the
                // switch block.
                with(Psi2kTranslator(data, seen = mutableMapOf(), nullness)) {
                  var lookahead: PsiElement? = blockElem.nextSibling
                  var inFallthroughBlock = false // wait until first non-PsiSwithLabelStmt
                  while (lookahead != null && lookahead.tokenOrNull() != "}") {
                    // Shortcut: definitely done, and we don't want to translate this, so
                    // just stop
                    if (lookahead is PsiBreakStatement && lookahead.labelIdentifier == null) break
                    if (lookahead is PsiSwitchLabelStatement) {
                      // First switch label after code block: stop if we can't fall through
                      if (inFallthroughBlock && uast?.canFallthroughTo(lookahead) == false) break
                      inFallthroughBlock = false
                      // We don't want to translate PsiSwithLabelStatements here in any case
                    } else {
                      if (lookahead !is PsiComment && lookahead !is PsiWhiteSpace) {
                        inFallthroughBlock = true // in code block
                      }
                      doAccept(lookahead)
                    }
                    lookahead = lookahead.nextSibling
                  }
                }
              }
              data.append("}\n")
              needBlockClose = false
            }
            if (!firstCase) data.append(", ") else firstCase = false
            for (blockChild in blockElem.children()) {
              // Qualify enum constants with class name
              if (blockChild is PsiCaseLabelElementList) {
                seen(blockChild)
                blockChild.children().replaceNotNull { token ->
                  if (token is PsiReferenceExpression) {
                    val enumClass =
                      token.resolve()?.parent as? PsiClass ?: return@replaceNotNull null
                    if (!enumClass.isEnum) return@replaceNotNull null
                    val enumClassName = enumClass.getNameOfPossiblyInnerClass()
                    val enumClassQName = enumClass.qualifiedName ?: return@replaceNotNull null
                    val packageName = enumClassQName.removeSuffix(".$enumClassName")
                    return@replaceNotNull if (
                      token.containingFile.isPackageDefaultImported(packageName)
                    ) {
                      "${enumClassName}.${token.text}"
                    } else {
                      "${enumClassQName}.${token.text}"
                    }
                  } else {
                    null
                  }
                }
              } else {
                when (blockChild.tokenOrNull()) {
                  PsiKeyword.CASE,
                  ":" -> blockChild.replaceWith("")
                  PsiKeyword.DEFAULT -> blockChild.replaceWith("else")
                  else -> doAccept(blockChild)
                }
              }
            }
          } else {
            if (!firstCase && blockElem !is PsiComment && blockElem !is PsiWhiteSpace) {
              firstCase = true
              data.append(" -> {")
              needBlockClose = true
            }
            if (
              needBlockClose && blockElem is PsiBreakStatement && blockElem.labelIdentifier == null
            ) {
              blockElem.replaceWith("}")
              needBlockClose = false
            } else {
              if (needBlockClose && blockElem.tokenOrNull() == "}") {
                data.append("}\n")
                needBlockClose = false
              }
              doAccept(blockElem)
            }
          }
        }
      } else {
        doAccept(child)
      }
    }
  }

  /**
   * Returns whether this switch statement can fall through from the block preceding the given
   * [switchLabel], which must be the first label if there are multiple in a row.
   *
   * @see [canCompleteNormally]
   */
  private fun USwitchExpression.canFallthroughTo(switchLabel: PsiSwitchLabelStatement): Boolean {
    var pred: USwitchClauseExpression? = null
    for (branch in body.expressions) {
      if (branch is USwitchClauseExpression) {
        if (branch.sourcePsi == switchLabel) {
          // We found the switch label, so return pred's completion if any
          return pred?.canCompleteNormally() == true
        }
        pred = branch
      }
    }
    // shouldn't get here if switchLabel was the first label preceding one of the blocks
    throw IllegalArgumentException("$switchLabel not found in ${asRenderString()}")
  }

  override fun visitSuperExpression(expression: PsiSuperExpression) {
    visitQualifiedExpression(expression) // missing delegate in superclass so we do our own
  }

  override fun visitThisExpression(expression: PsiThisExpression) {
    visitQualifiedExpression(expression) // missing delegate in superclass so we do our own
  }

  private fun visitQualifiedExpression(expression: PsiQualifiedExpression) {
    // Foo.this|super ==> this|super@Foo
    seen(expression)
    expression.children().forEach { child ->
      when {
        child is PsiKeyword -> { // "this" or "super"
          doAccept(child)
          expression.qualifier?.let {
            data.append("@")
            doAccept(it)
          }
        }
        child != expression.qualifier -> skipRecursive(child)
      }
    }
  }

  override fun visitTryStatement(statement: PsiTryStatement) {
    seen(statement)
    // Drop surrounding try {...} for try-with-resources statement without catch or finally blocks
    val needTry =
      statement.resourceList == null ||
        statement.finallyBlock != null ||
        statement.catchSections.isNotEmpty()
    statement
      .children()
      .filter { it != statement.resourceList }
      .forEach { child ->
        when {
          !needTry && child.tokenOrNull() == PsiKeyword.TRY -> seen(child)
          child == statement.tryBlock -> {
            seen(child)
            child.children().forEach {
              when (it) {
                (child as PsiCodeBlock).lBrace -> {
                  it.acceptIf(needTry)
                  // Rely on visitResourceXxx to insert `xxx.use {`s for each resource
                  doAccept(statement.resourceList)
                }
                child.rBrace -> {
                  // Add closing brace for each .use { block created above
                  for (unused in statement.resourceList ?: emptyList()) {
                    data.append(" }")
                  }
                  it.acceptIf(needTry)
                }
                else -> doAccept(it)
              }
            }
          }
          else -> doAccept(child)
        }
      }
  }

  override fun visitTypeCastExpression(expression: PsiTypeCastExpression) {
    // (Foo) expr ==> expr as Foo (or expr.toFoo() for primitive casts)
    // by traversing in reverse order and replacing parens with `as` (or the type with `toType()`)
    seen(expression)
    val primitiveConversion =
      runIf(
        isPrimitiveAndNotNull(expression.castType?.type) &&
          isPrimitiveAndNotNullOrWrapper(expression.operand?.type)
      ) {
        PRIMITIVE_TYPES[expression.castType?.type]
      }
    expression.lastChild.withPredecessors().replaceNotNull {
      when (it.tokenOrNull()) {
        ")" -> if (primitiveConversion != null) "" else " as "
        "(" -> ""
        else ->
          if (primitiveConversion != null && it == expression.castType) {
            ".to$primitiveConversion()"
          } else {
            null
          }
      }
    }
  }

  override fun visitTypeElement(node: PsiTypeElement) {
    val type = node.type
    val prim = PRIMITIVE_TYPES[type]
    when {
      prim != null -> node.replaceWith(prim)
      type.arrayDimensions > 0 -> {
        seen(node)
        val primArrayDim =
          runIf(type.deepComponentType in PRIMITIVE_TYPES) {
            node.lastChild.withPredecessors().firstOrNull { it.tokenOrNull() == "[" }
          }
        // T @AnnoOnOuter [] @AnnoOnInner [] ==> @AnnoOnOuter Array<@AnnoOnInner Array<T>>
        // primType @Outer [] @Inner [] ==> @Outer Array<@Inner PrimTypeArray>
        // with ? appended to array dims that carry @Nullable type anno
        val suffixes = ArrayList<String>(type.arrayDimensions)
        var dimNullable = ""
        for (child in node.children().filter { it !is PsiTypeElement }) {
          when (child.tokenOrNull()) {
            "[" -> {
              suffixes +=
                (if (child == primArrayDim) {
                  seen(child)
                  "Array"
                } else {
                  child.replaceNotNull("Array<")
                  ">"
                } + dimNullable)
              dimNullable = ""
            }
            "]",
            "..." -> seen(child) // skip varargs token, which is a modifier in Kotlin
            else -> {
              if (suffixes.isNotEmpty() && child is PsiAnnotation && child.isNullableAnno()) {
                // Mark dimension nullable, except outermost array handled by default logic below
                dimNullable = "?"
              }
              doAccept(child)
            }
          }
        }
        // Print component type, then suffixes in reverse order
        suffixes.reverse()
        node.children().filter { it is PsiTypeElement }.acceptEach()
        suffixes.forEach(data::append)
      }
      type is PsiWildcardType -> {
        if (!type.isBounded) {
          // ? ==> *
          node.replaceWith("*")
        } else {
          // ? extends|super Foo ==> out|in Foo
          seen(node)
          data.append(if (type.isSuper) "in" else "out")
          // Visit the wildcard-ed type
          node.children().acceptEach { it is PsiTypeElement }
        }
      }
      else -> {
        super.visitTypeElement(node)
        // Use star projections instead of raw types, except in Foo.class, where Foo<*>::class is
        // illegal. This doesn't cover `new RawType()`, but star projection is invalid in that case
        // anyway. Raw types may still require manual intervention, but this seems at least
        // convenient for `xs instanceof List` (b/282171141).
        if (type is PsiClassType && type.isRaw && node.parent !is PsiClassObjectAccessExpression) {
          data.appendNonNull(
            type.resolve()?.typeParameters?.joinToString(
              prefix = "<",
              separator = ", ",
              postfix = ">",
            ) {
              "*"
            }
          )
        }
      }
    }

    if (
      indicatesNullable(node) ||
        hasNullableAnno(node.parent as? PsiModifierListOwner) ||
        indicatesNullable(
          // If this is an array component type (e.g., in `>String< [] []`), type sadly doesn't
          // carry type annotations, but the parent's type's component type does.
          ((node.parent as? PsiTypeElement)?.type as? PsiArrayType)?.deepComponentType
        ) ||
        inferredNullable(node.parent as? PsiVariable) ||
        inferredNullableReturn(node.parent as? PsiMethod)
    ) {
      data.append("?")
    } else if (
      type is PsiClassType &&
        type.resolve() is PsiTypeParameter &&
        (indicatesNonNull(node) ||
          indicatesNonNull(
            (node.parent as? PsiModifierListOwner)?.modifierList,
            useTypeAnnos = false,
          ))
    ) {
      data.append(" & Any") // @NonNull T ==> T & Any (if T is a type variable)
    }
  }

  private fun inferredNullable(variable: PsiVariable?): Boolean {
    if (variable !is PsiLocalVariable && variable !is PsiParameter) return false

    val root = variable.getParentOfTypes2<PsiMethod, PsiLambdaExpression>() ?: return false
    val nullness =
      when (val rootNode = root.toUElement()) {
        is UMethod -> nullness(CfgRoot.of(rootNode))
        is ULambdaExpression -> nullness(CfgRoot.of(rootNode))
        else -> return false
      }

    val noNullableBoundDeclared by lazy { !variable.type.hasNullableBound() }

    if (variable is PsiParameter) {
      // For parameters, use stored value at declaration site. If the parameter is re-assigned,
      // we need a local variable anyway (b/347273856) and can make that nullable.
      val value = nullness[variable].store[variable] ?: Nullness.BOTTOM
      return Nullness.NULL.implies(value) ||
        (value == Nullness.PARAMETRIC && noNullableBoundDeclared)
    }

    fun PsiExpression.requiresNullable(): Boolean {
      val value =
        nullness
          .uastNodes(this)
          .filterIsInstance<UExpression>()
          .mapNotNull { nullness[it].value[it] }
          .reduceOrNull(Nullness::join) ?: Nullness.BOTTOM
      return Nullness.NULL.implies(value) ||
        (value == Nullness.PARAMETRIC && noNullableBoundDeclared)
    }

    if (variable.initializer?.requiresNullable() == true) return true
    var result = false
    root.accept(
      object : JavaRecursiveElementWalkingVisitor() {
        override fun visitAssignmentExpression(expression: PsiAssignmentExpression) {
          if (
            expression.operationSign.text == "=" && // compound assignments can't make lhs nullable
              (expression.lExpression as? PsiReferenceExpression)?.resolve() == variable &&
              expression.rExpression?.requiresNullable() == true
          ) {
            result = true
            stopWalking()
          }
        }
      }
    )
    return result
  }

  private fun inferredNullableReturn(method: PsiMethod?): Boolean {
    // Nullness analysis goes by annotations only for non-private methods, so for consistency don't
    // change non-private method return types based on analysis results.
    // TODO(b/384955376): use inferred return nullness for non-private methods
    if (method?.isPrivate != true) return false
    val uMethod = method.toUElementOfType<UMethod>() ?: return false
    val nullness = nullness(CfgRoot.of(uMethod))
    val value =
      uMethod.returnValueNullness(nullness[uMethod as UElement]) { nullness(CfgRoot.of(it))[it] }
        ?: Nullness.BOTTOM
    return Nullness.NULL.implies(value) ||
      (value == Nullness.PARAMETRIC && method.returnType?.hasNullableBound() != true)
  }

  override fun visitTypeParameter(classParameter: PsiTypeParameter) {
    visitElement(classParameter) // visit children normally
    if (classParameter.extendsListTypes.isEmpty() && classParameter.isInNullMarkedScope()) {
      // If in https://jspecify.dev/docs/api/org/jspecify/annotations/NullMarked.html scope and
      // there is no `extends` clause, add `: Any`
      data.append(" : Any")
    }
  }

  override fun visitUnaryExpression(expression: PsiUnaryExpression) {
    if (expression.operationSign.text == "~") {
      // ~x ==> x.inv()
      seen(expression)
      expression.children().forEach {
        if (it == expression.operationSign) {
          seen(it)
        } else {
          doAccept(it)
          if (it == expression.operand) {
            data.append(".inv()")
          }
        }
      }
    } else {
      super.visitUnaryExpression(expression)
    }
  }

  /**
   * Returns conversion needed for unary promotion of the given [operand] type, if any, per
   * [JLS ch. 5.6.1](https://docs.oracle.com/javase/specs/jls/se11/html/jls-5.html#jls-5.6.1).
   */
  private fun unaryPromotion(operand: PsiType?): String? {
    val operandType = PsiPrimitiveType.getOptionallyUnboxedType(operand)
    return runIf(
      operandType == PsiTypes.byteType() ||
        operandType == PsiTypes.shortType() ||
        operandType == PsiTypes.charType()
    ) {
      ".toInt()"
    }
  }

  private companion object {
    val JAVA_LANG_OVERRIDE = Override::class.qualifiedName!!

    val DELEGATION_KEYWORDS = setOf(PsiKeyword.THIS, PsiKeyword.SUPER)
    val PRIMITIVE_TYPES: Map<PsiType, String> =
      mapOf(
        PsiTypes.booleanType() to "Boolean",
        PsiTypes.byteType() to "Byte",
        PsiTypes.charType() to "Char",
        PsiTypes.doubleType() to "Double",
        PsiTypes.floatType() to "Float",
        PsiTypes.intType() to "Int",
        PsiTypes.longType() to "Long",
        PsiTypes.shortType() to "Short",
        PsiTypes.voidType() to "Unit",
        PsiTypes.nullType() to "Nothing?", // Java's "null type" is nullable bottom
      )
    val PRIMITIVE_INITIALIZER: Map<PsiType, String> =
      mapOf(
        PsiTypes.booleanType() to "false",
        PsiTypes.byteType() to "0",
        PsiTypes.charType() to "'\\0'",
        PsiTypes.doubleType() to "0.0",
        PsiTypes.floatType() to "0.0f",
        PsiTypes.intType() to "0",
        PsiTypes.longType() to "0L",
        PsiTypes.shortType() to "0",
        PsiTypes.voidType() to "Unit",
        PsiTypes.nullType() to "null",
      )

    val MAPPED_TYPES: Map<String, String> =
      mapOf(
        "java.lang.Boolean" to "Boolean",
        "java.lang.Byte" to "Byte",
        "java.lang.Character" to "Char",
        "java.lang.Deprecated" to "java.lang.Deprecated", // Kotlin's @Deprecated requires message
        "java.lang.Double" to "Double",
        "java.lang.Float" to "Float",
        "java.lang.Integer" to "Int",
        "java.lang.Iterable" to "Iterable",
        "java.lang.Long" to "Long",
        "java.lang.Object" to "Any",
        "java.lang.Short" to "Short",
        "java.util.Iterator" to "Iterator",
        "java.util.Collection" to "MutableCollection",
        "java.util.List" to "MutableList",
        "java.util.Map" to "MutableMap",
        // TODO(b/273549101): map "java.util.Map.Entry" to "MutableMap.MutableEntry",
        "java.util.Set" to "MutableSet",
      )

    val MAPPED_OPERATORS: Map<String, String> =
      mapOf("<<" to "shl", ">>" to "shr", ">>>" to "ushr", "&" to "and", "|" to "or", "^" to "xor")

    /**
     * Binary operators subject to
     * [unary promotion](https://docs.oracle.com/javase/specs/jls/se11/html/jls-5.html#jls-5.6.1).
     */
    val UNARY_PROMOTION_BINOPS: Set<String> = setOf("<<", ">>", ">>>")

    /**
     * Binary operators subject to
     * [binary promotion](https://docs.oracle.com/javase/specs/jls/se11/html/jls-5.html#jls-5.6.2).
     */
    val BINARY_PROMOTION_OPS: Set<String> =
      setOf("*", "/", "%", "+", "-", "<", "<=", ">", ">=", "==", "!=", "&", "^", "|")

    /** Binary operators defined between all pairs of primitive [Number] subtypes. */
    val ARITHMETIC_OPS: Set<String> = setOf("*", "/", "%", "+", "-", "<", "<=", ">", ">=")

    val KOTLIN_KEYWORDS: Set<String> =
      KtTokens.KEYWORDS.types.map { (it as KtKeywordToken).value }.toSet()

    // https://kotlinlang.org/docs/packages.html#default-imports
    val DEFAULT_IMPORTED_PACKAGES: Set<String> =
      setOf(
        "kotlin",
        "kotlin.annotation",
        "kotlin.collections",
        "kotlin.comparisons",
        "kotlin.io",
        "kotlin.ranges",
        "kotlin.sequences",
        "kotlin.text",
        "java.lang",
        "kotlin.jvm",
      )

    fun PsiElement.withSuccessors(): Sequence<PsiElement> =
      generateSequence(this) { it.nextSibling }

    fun PsiElement.withPredecessors(): Sequence<PsiElement> =
      generateSequence(this) { it.prevSibling }

    fun PsiElement.children(): Sequence<PsiElement> =
      generateSequence(firstChild) { it.nextSibling }

    fun <K : Any, V> Map<out K, V>.getOrNull(key: K?): V? = if (key != null) get(key) else null

    private fun PsiFile.isPackageDefaultImported(packageName: String): Boolean {
      return packageName in DEFAULT_IMPORTED_PACKAGES ||
        packageName == toUElementOfType<UFile>()?.packageName
    }
  }
}

// TODO(rmwang): this makes things really error-prone, refactor to make this no longer extends
// Psi2kTranslator
private abstract class InterceptingTranslator(
  val buffer: StringBuilder,
  companion: StringBuilder,
  seen: MutableMap<PsiElement, String>,
  nullness: (CfgRoot) -> UAnalysis<State<Nullness>>,
) : Psi2kTranslator(PsiTranslationOutput(buffer, companion), seen, nullness) {
  abstract fun acceptResult(mapping: InterceptingTranslator.() -> String)
}
