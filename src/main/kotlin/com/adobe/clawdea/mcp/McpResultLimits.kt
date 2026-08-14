/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.mcp

/**
 * The per-tool result caps for the index tools, centralized so they stop being scattered magic
 * numbers (10 / 15 / 10 / 30 / 10). Paired with early-aborting `Query.forEach` processors so a hot
 * symbol's search stops at the cap instead of materializing every reference under a read action and
 * discarding 99% (Tier 6.4).
 */
internal object McpResultLimits {
    const val CALLERS = 10
    const val IMPLEMENTATIONS = 10
    const val USAGES = 15
    const val FILES = 30
    const val SYMBOLS = 10
    const val METHODS_PER_TYPE = 10
}
