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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.retail;

import java.math.BigDecimal;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/*
 * WHY /cash-account IS A MAPPING PREFIX HERE AND NOT A SERVLET CONTEXT PATH. The chart publishes this seam to
 * broker as http://{{ .Release.Name }}-cash-account-service:8080/cash-account
 * [infra/stocktrader-operator/helm-charts/stocktrader/values.yaml:L146] while probing /actuator/startup,
 * /actuator/health/readiness and /actuator/health/liveness at the ROOT of that same port 8080
 * [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L203-L222]. A
 * server.servlet.context-path moves every servlet mapping together, so buying the published URL that way would
 * relocate the three probe paths and every pod would fail its startup probe. The prefix therefore belongs to this
 * controller - the only placement that satisfies the published address and the probed addresses at once - and the
 * template is not ours to edit (AAP 0.3.4). Broker's local-dev default .../account
 * [backend/broker/src/main/liberty/config/jvm.options:L3] is overridden by the chart in every deployment and
 * deliberately gets no alias mapping.
 *
 * WHY NO MAPPING RESTRICTS THE REQUEST MEDIA TYPE. The caller declares @Consumes(MediaType.APPLICATION_JSON) on
 * every method, including the two that send no entity at all
 * [backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/client/CashAccountClient.java:L52,
 * L73], so a body-less GET or DELETE may reach this service with or without a Content-Type header depending on the
 * client runtime. A request-media-type restriction on these mappings would answer 415 to precisely that request,
 * and broker wraps every cash call in catch Throwable and logs it
 * [backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/BrokerService.java:L357-L365], so
 * the rejection would surface as a silently lost cash call rather than as a reported failure. The response side is
 * constrained, because those same annotations pair @Produces(MediaType.APPLICATION_JSON) with them and the client
 * sends Accept: application/json.
 *
 * WHY THERE IS NO CATCH-ALL MAPPING. The legacy dispatcher recognized the six single-character request codes A, Q,
 * U, X, C and D - one per endpoint below - and carried no WHEN OTHER
 * [backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L102]: an unknown code executed no SQL, yet still had the
 * SQLCA's leftover status copied into the return field and the COMMAREA echoed back verbatim [CASH00.cbl:L104-L108],
 * so the caller read a success-looking status together with its own submitted amount as the "balance". In the
 * replacement the verb and path ARE the request code, and Spring MVC's routing is the WHEN OTHER that was missing:
 * an unmapped path raises a no-handler exception and an unmapped verb raises
 * HttpRequestMethodNotSupportedException, both rendered by error/ApiExceptionHandler as 404 UNSUPPORTED_PATH and
 * 405 UNSUPPORTED_METHOD in the single ApiError shape (AAP 0.4.3). A local catch-all would intercept those and have
 * to re-derive a decision that is already made in one place.
 *
 * WHY NO METHOD-LEVEL SECURITY ANNOTATION. The entire role split - GET readable by StockViewer or StockTrader, the
 * writes and the debit/credit paths by StockTrader only - is expressed as matcher order in config/SecurityConfig
 * (AAP 0.7.5), reproducing broker's own web.xml split [backend/broker/src/main/webapp/WEB-INF/web.xml:L19-L51] and
 * closing everything outside this path space with denyAll(). Annotations here would be a second authority over the
 * same decision, and two places to get it wrong; the filter chain also has to reject an anonymous caller with 401
 * before any handler is chosen, which a handler annotation cannot do.
 */
/** The retail seam: the legacy CASH00 request codes A/Q/U/X/C/D re-expressed as HTTP verbs under /cash-account. */
@RestController
@RequestMapping(path = "/cash-account", produces = MediaType.APPLICATION_JSON_VALUE)
public class RetailCashAccountController {

    private final RetailCashAccountService service;

    // The service is the only collaborator, deliberately: owner normalization, amount and currency validation, the
    // exchange-rate lookup and every ledger write live behind it, so migration/shadow/ShadowComparator replays the
    // identical behaviour with no HTTP layer in front of it and parity is judged against one implementation rather
    // than two. Nothing from domain, persistence, audit or fx is reached from here (AAP 0.8.2).
    public RetailCashAccountController(RetailCashAccountService service) {
        this.service = service;
    }

    @GetMapping("/{owner}")
    public CashAccountResponse getCashAccount(@PathVariable("owner") String owner) {
        return service.read(owner);
    }

    // The path variable is authoritative and the body's owner component is ignored. Broker sends the same owner in
    // the URI and the payload, the URI is what the chart's published address and config/SecurityConfig's matchers
    // both key on, and CashAccountErrorCode is a closed set that AAP 0.6.2 fixes - it holds no mismatch condition,
    // so rejecting a divergent body owner would mean widening an enum this contract pins.
    //
    // WHY THE BODY IS REQUIRED. AAP 0.6.2 defines both writes as carrying body {owner, balance, currency}, and PUT
    // as an ABSOLUTE overwrite of the balance and the currency. Treating that payload as optional made a body-less
    // PUT indistinguishable from a caller asking for 0.00 in the base currency, so a request carrying no
    // instruction at all silently emptied an account. A missing (or JSON-null) body is now Spring's
    // HttpMessageNotReadableException, which error/ApiExceptionHandler renders as 400 INVALID_AMOUNT in the single
    // ApiError shape, and no caller loses anything: broker always sends an entity on create and never calls the
    // update method at all
    // [backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/BrokerService.java:L357-L365].
    //
    // The DEFAULTS INSIDE the body stay, because they are legacy behaviour rather than convenience, and README.md's
    // retail contract table states each of them together with the fact that a body of {} therefore overwrites an
    // account with 0.00 in the base currency: an omitted or null balance is 0.00, since the COMMAREA field
    // WS-BALANCE PIC 9(7)V99 [backend/cash-account-cobol/COBOL/CASH00.cbl:L56] could not be null and an unset
    // field arrived as zeros; an omitted, null or blank currency is the configured base currency, which broker
    // itself already substitutes before calling [.../broker/BrokerService.java:L83, L357-L365].
    //
    // The owner travels raw. domain/OwnerNormalizer, reached through the service, is the single place that trims,
    // uppercases and rejects a blank or over-32-character owner as 400 INVALID_OWNER, and the two defaults above
    // are applied there as well (AAP 0.4.6). Trimming or defaulting here would leave the shadow replay path, which
    // never passes through this controller, judged against different input handling.
    @PostMapping("/{owner}")
    public CashAccountResponse createCashAccount(@PathVariable("owner") String owner,
            @RequestBody CashAccountResponse body) {

        return service.create(owner, body.balance(), body.currency());
    }

    @PutMapping("/{owner}")
    public CashAccountResponse updateCashAccount(@PathVariable("owner") String owner,
            @RequestBody CashAccountResponse body) {

        return service.update(owner, body.balance(), body.currency());
    }

    @DeleteMapping("/{owner}")
    public CashAccountResponse deleteCashAccount(@PathVariable("owner") String owner) {
        return service.delete(owner);
    }

    // amount is bound as text and parsed below rather than declared as a BigDecimal parameter, and not because the
    // framework could not parse it. The caller's own signature is @QueryParam("amount") double
    // [backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/client/CashAccountClient.java:L83,
    // L90], so the wire value is whatever Double.toString produced, including the scientific form 1.0E7 - which
    // Spring's own String-to-BigDecimal conversion accepts too, since it delegates to new BigDecimal(String).
    //
    // The reason is WHERE the decision is taken. AAP 0.6.2 fixes this shape - "amount bound from its string form
    // to BigDecimal" - and what it buys is one explicit parse, in this file, that no configuration elsewhere can
    // reinterpret: a Formatter or ConversionService registered by some later WebMvcConfigurer would otherwise own
    // the conversion of every money parameter, free to apply a locale-sensitive or grouping-aware parse or a
    // scaling step, and domain/Money must be the module's single truncation point (AAP 0.12.5). It also makes
    // blank and non-numeric text one condition with one answer, 400 INVALID_AMOUNT, where a declared parameter
    // reports a present-but-empty ?amount= through the MISSING-parameter channel
    // (MissingServletRequestParameterException raised after conversion to null) - the same status and code as it
    // happens, but describing a value the caller did send as one it did not.
    @PutMapping("/{owner}/debit")
    public CashAccountResponse debit(@PathVariable("owner") String owner,
            @RequestParam(name = "amount") String amount) {

        return service.debit(owner, parseAmount(amount));
    }

    @PutMapping("/{owner}/credit")
    public CashAccountResponse credit(@PathVariable("owner") String owner,
            @RequestParam(name = "amount") String amount) {

        return service.credit(owner, parseAmount(amount));
    }

    // Nothing here rescales or rounds the parsed value: domain/Money holds this module's single truncation point,
    // and a second one would destroy the legacy single-truncation semantics that reconciliation is judged against -
    // truncating the product before the signed addition turns 100.00 - 0.03 x 0.30 into 100.00 where the legacy
    // COMPUTE yielded 99.99 (AAP 0.12.5).
    //
    // An absent amount parameter never reaches this method. @RequestParam without a default makes Spring raise
    // MissingServletRequestParameterException, which error/ApiExceptionHandler already renders as INVALID_AMOUNT
    // from the parameter's name, so the guard below covers only the present-but-empty ?amount= case.
    private static BigDecimal parseAmount(String text) {
        String trimmed = text == null ? null : text.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_AMOUNT);
        }

        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException cause) {
            // The cause is kept because it names the offending text, which the ApiError payload must not carry but
            // an operator reading a stack trace needs; the message is left null so the code's own wording is used.
            throw CashAccountException.of(CashAccountErrorCode.INVALID_AMOUNT, null, cause);
        }
    }
}
