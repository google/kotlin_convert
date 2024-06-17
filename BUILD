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

load("@rules_kotlin//kotlin:core.bzl", "define_kt_toolchain")

alias(
    name = "com_github_google_kotlin_convert",
    actual = "//convert",
)

define_kt_toolchain(
    name = "kotlin_toolchain",
    jvm_target = "11",
)
