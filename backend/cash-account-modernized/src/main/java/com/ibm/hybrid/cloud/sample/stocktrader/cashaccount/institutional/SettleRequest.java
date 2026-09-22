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

import java.math.BigDecimal;

/*
 * Deliberately carries no Bean Validation constraint, unlike HoldRequest. An absent request body and a
 * present body with a null amount both mean "settle the full held amount", so @NotNull would reject the
 * documented default; 0 is a legal settlement that emits a SETTLEMENT of 0 plus a RELEASE of the whole
 * hold, so @DecimalMin("0.01")/@Positive would reject a supported case; and an amount above the held
 * amount is rejected with 400 INVALID_AMOUNT by domain/ReservationStateMachine, the single authority on
 * transitions, which is why no upper bound is asserted here either.
 */
/** Optional request body for settling an institutional hold: {@code {amount?}}. */
public record SettleRequest(BigDecimal amount) {
}
