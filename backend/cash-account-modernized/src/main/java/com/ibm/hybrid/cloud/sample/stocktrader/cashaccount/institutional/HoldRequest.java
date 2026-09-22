/*
       Copyright 2025 Kyndryl, All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.institutional;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Request body for placing an institutional fund hold against a cash account. */
public record HoldRequest(
        @NotBlank @Size(max = 64) String orderReference,

        // No fraction constraint: the canonical idempotency hash normalizes with
        // setScale(2, RoundingMode.DOWN), so 10, 10.0 and 10.00 must hash identically.
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,

        @NotBlank String currency,

        // Nullable: absent means the configured default TTL applies. A past instant is accepted
        // on purpose; the hold endpoint defines no bad-expiry code, so the sweep terminates it.
        OffsetDateTime expiresAt) {
}
