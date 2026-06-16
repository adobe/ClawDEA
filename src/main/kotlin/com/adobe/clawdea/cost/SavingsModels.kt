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
package com.adobe.clawdea.cost

/**
 * A signed savings estimate with a confidence range. Positive = saving, negative = cost.
 * [low] <= [expected] <= [high] always holds for a well-formed band.
 */
data class SavingsBand(val low: Double, val expected: Double, val high: Double) {
    operator fun plus(o: SavingsBand) = SavingsBand(low + o.low, expected + o.expected, high + o.high)

    /** Negation swaps edges so the invariant low <= expected <= high is preserved. */
    operator fun unaryMinus() = SavingsBand(-high, -expected, -low)

    companion object {
        val ZERO = SavingsBand(0.0, 0.0, 0.0)

        /** A measured (zero-variance) value. */
        fun exact(v: Double) = SavingsBand(v, v, v)
    }
}
