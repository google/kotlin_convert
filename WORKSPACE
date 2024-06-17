# Copyright 2024 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

workspace(name = "com_github_google_kotlin_convert")

# Repositories
load("//:repositories.bzl", "rules_jvm_external", "rules_kotlin")

rules_jvm_external()

load("@rules_jvm_external//:repositories.bzl", "rules_jvm_external_deps")

rules_jvm_external_deps()

load("@rules_jvm_external//:setup.bzl", "rules_jvm_external_setup")

rules_jvm_external_setup()

load("@rules_jvm_external//:defs.bzl", "maven_install")

# Maven
maven_install(
    artifacts = [
        "com.github.ben-manes.caffeine:caffeine:3.1.8",
        "com.jetbrains.intellij.platform:uast:241.15989.155",
        "org.jetbrains.kotlin:kotlin-compiler:1.9.23",
    ],
    repositories = [
        "https://repo.maven.apache.org/maven2/",
        "https://www.jetbrains.com/intellij-repository/releases/",
        "https://cache-redirector.jetbrains.com/intellij-dependencies/",
    ],
)

# Kotlin
rules_kotlin()

load("@rules_kotlin//kotlin:repositories.bzl", "kotlin_repositories")

kotlin_repositories()

register_toolchains("//:kotlin_toolchain")
