# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

This is a Java-to-Kotlin source translator that preserves the original API as much as possible. It uses IntelliJ's PSI (Program Structure Interface) and UAST (Universal Abstract Syntax Tree) framework to analyze Java code and translate it to Kotlin, leveraging static analysis to improve translation quality.

The translator is designed to be used in a multi-step pipeline:
1. Prepare code (e.g., add nullness annotations)
2. Apply the translator
3. Run an auto-formatter
4. Apply improvements (e.g., use `data` classes)

## Build System

This project uses **Bazel** with Kotlin rules.

**Building:**
```bash
bazel build //convert
```

**Running tests:**
```bash
bazel test //convert
```

**Target JVM version:** Java 11

## Architecture

The codebase consists of two main subsystems that work together:

### 1. Static Analysis Framework (`analysis/`)

A generic interprocedural dataflow analysis framework built on UAST:

- **`UAnalysis.kt`**: Core analysis interfaces. Defines `Value<T>` lattice framework for abstract interpretation with `isBottom`, `implies`, and `join` operations.

- **`Cfg.kt`**: Control Flow Graph (CFG) construction from UAST elements. Represents program control flow with edges labeled by conditions (e.g., true/false branches).

- **`InterproceduralAnalysis.kt`**: Framework for analyzing method calls across procedure boundaries. Uses `InterproceduralAnalysisBuilder` to build context-sensitive analysis results with worklist algorithm.

- **`UTransferFunction.kt`**: Transfer function interface for dataflow analyses that process CFG nodes and update abstract states.

**Specific Analyses:**

- **`nullness/NullnessAnalysis.kt`**: Dataflow analysis that mimics Kotlin's nullness type system. Tracks nullable/non-null states to guide translation decisions. Supports interprocedural analysis of private methods.

- **`mutation/CollectionMutationAnalysis.kt`**: Tracks whether collections are unused, read-only, or modified. Used to decide between mutable and immutable Kotlin collection types.

### 2. Translation Layer (`convert/psi2k/`)

Converts Java PSI to Kotlin code:

- **`Psi2kTranslator.kt`** (2192 lines): Main translator that walks Java PSI tree and generates Kotlin source. Uses `JavaElementVisitor` to traverse PSI nodes and translates each construct to Kotlin equivalent. Integrates analysis results to make informed translation decisions (e.g., nullable types, collection mutability).

- **`JavaPsiUtils.kt`**: Utility functions for working with PSI elements (checking modifiers, effective finality, etc.).

- **`MappedMethod.kt`**: Maps Java standard library methods to their Kotlin equivalents.

### Key Dependencies

- **IntelliJ Platform UAST** (`com.jetbrains.intellij.platform:uast`): Universal AST framework
- **Kotlin Compiler** (`org.jetbrains.kotlin:kotlin-compiler`): Used for Kotlin code generation
- **Caffeine** (`com.github.ben-manes.caffeine:caffeine`): Caching for analysis results

## How Static Analysis Informs Translation

The translator uses analysis results to make better translation decisions:

1. **Nullness Analysis**: Determines which Java types should become nullable (`T?`) vs non-null (`T`) in Kotlin
2. **Collection Mutation Analysis**: Decides between mutable (`MutableList`) and read-only (`List`) collection types
3. **Control Flow Analysis**: Understands reachability and exception handling to generate appropriate Kotlin control flow

## Code Organization

```
convert/src/com/google/devtools/jvmtools/
├── analysis/               # Static analysis framework
│   ├── Cfg.kt             # Control flow graph
│   ├── UAnalysis.kt       # Analysis interface
│   ├── InterproceduralAnalysis.kt  # Interprocedural framework
│   ├── UTransferFunction.kt        # Transfer functions
│   ├── nullness/          # Nullness analysis
│   └── mutation/          # Collection mutation analysis
└── convert/
    ├── psi2k/             # PSI to Kotlin translator
    └── util/              # Utilities
```

## Working with Analysis Code

When modifying or adding analyses:

- Analyses implement `Value<T>` lattice with proper `join` operation
- Use `InterproceduralAnalysisBuilder` for context-sensitive analysis
- Transfer functions process CFG nodes via `UTransferFunction`
- Analysis results are cached via Caffeine for performance

## Working with Translation Code

When modifying the translator:

- `Psi2kTranslator` uses visitor pattern on PSI elements
- Access analysis results through `UAnalysis` interface
- Preserve Java API structure when possible
- Consider the multi-step pipeline when making translation decisions
