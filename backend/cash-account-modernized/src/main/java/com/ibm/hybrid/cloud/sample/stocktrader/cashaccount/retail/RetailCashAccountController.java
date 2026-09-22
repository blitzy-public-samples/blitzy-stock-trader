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

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.Money;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/**
 * The retail seam: the legacy CASH00 request codes A/Q/U/X/C/D re-expressed as HTTP verbs under a
 * controller-level /cash-account prefix - not a servlet context path, which would move the chart's root-level
 * /actuator probes along with the published address
 * [infra/stocktrader-operator/helm-charts/stocktrader/values.yaml:L146;
 * infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L203-L222].
 */
@RestController
@RequestMapping(path = "/cash-account", produces = MediaType.APPLICATION_JSON_VALUE)
public class RetailCashAccountController {

    private final RetailCashAccountService service;

    // The service is the only collaborator: owner normalization, validation, the rate lookup and every ledger
    // write live behind it, so migration/shadow/ShadowComparator replays identical behaviour with no HTTP layer in
    // front of it and parity is judged against one implementation. The only thing taken from domain is Money's
    // static input-size bound on the parsed amount parameter, applied where the untrusted text first becomes a
    // number (AAP 0.8.2 permits retail to depend on domain).
    public RetailCashAccountController(RetailCashAccountService service) {
        this.service = service;
    }

    // No mapping restricts the REQUEST media type, and that absence is load-bearing: the caller declares
    // @Consumes(APPLICATION_JSON) even on the two methods that send no entity
    // [backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/client/CashAccountClient.java:L52,
    // L73], so a body-less GET or DELETE may arrive with or without a Content-Type, and a 415 would surface as a
    // silently lost cash call - broker wraps every one in catch Throwable and only logs it
    // [.../broker/BrokerService.java:L357-L365]. The response side is constrained, because the client pairs
    // @Produces(APPLICATION_JSON) with those annotations.
    @GetMapping("/{owner}")
    public CashAccountResponse getCashAccount(@PathVariable("owner") String owner) {
        return service.read(owner);
    }

    // The path variable is authoritative and the body's owner component is ignored: it is what the chart's
    // published address and config/SecurityConfig's matchers both key on, and CashAccountErrorCode is a closed set
    // AAP 0.6.2 fixes, so rejecting a divergent body owner would mean widening an enum this contract pins.
    //
    // The body is required, not optional. AAP 0.6.2 defines both writes as carrying {owner, balance, currency}
    // with PUT an ABSOLUTE overwrite, so an optional payload would make a body-less PUT indistinguishable from a
    // caller asking for 0.00 in the base currency - a request carrying no instruction at all would empty an
    // account. An absent or JSON-null body is Spring's HttpMessageNotReadableException, which
    // error/ApiExceptionHandler renders as 400 INVALID_AMOUNT, and broker always sends an entity on create
    // [backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/BrokerService.java:L357-L365].
    //
    // The DEFAULTS INSIDE the body are legacy behaviour rather than convenience, so a body of {} does overwrite an
    // account with 0.00 in the base currency: an omitted or null balance is 0.00 because the COMMAREA field
    // WS-BALANCE PIC 9(7)V99 [backend/cash-account-cobol/COBOL/CASH00.cbl:L56] could not be null and arrived as
    // zeros, and an omitted currency is the base currency broker itself substitutes
    // [.../broker/BrokerService.java:L83, L357-L365].
    //
    // The owner travels raw, because domain/OwnerNormalizer behind the service is the single place that trims,
    // uppercases and rejects a blank or over-32-character owner (AAP 0.4.6); trimming here would leave the shadow
    // replay path, which never passes through this controller, judged against different input handling.
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

    // amount is bound as text and parsed below, the shape AAP 0.6.2 fixes, because it puts the parse in this file
    // where no configuration elsewhere can reinterpret it: a Formatter or ConversionService registered by a later
    // WebMvcConfigurer would otherwise own every money parameter's conversion and could apply a locale-sensitive
    // parse or a scaling step, while domain/Money must remain the module's single truncation point (AAP 0.12.5).
    // It also makes blank and non-numeric text one condition with one answer, 400 INVALID_AMOUNT, where a declared
    // parameter would report a present-but-empty ?amount= through the MISSING-parameter channel. The caller sends
    // @QueryParam("amount") double
    // [backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/client/CashAccountClient.java:L83,
    // L90], so the wire value is whatever Double.toString produced, including the scientific form 1.0E7.
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

    // Nothing here rescales or rounds the parsed value: domain/Money holds the module's single truncation point,
    // and a second one would turn 100.00 - 0.03 x 0.30 into 100.00 where the legacy COMPUTE yielded 99.99
    // (AAP 0.12.5). An absent parameter never reaches this method - Spring raises
    // MissingServletRequestParameterException, which error/ApiExceptionHandler renders as INVALID_AMOUNT - so the
    // guard below covers only the present-but-empty ?amount= case.
    private static BigDecimal parseAmount(String text) {
        String trimmed = text == null ? null : text.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_AMOUNT);
        }

        BigDecimal parsed;
        try {
            parsed = new BigDecimal(trimmed);
        } catch (NumberFormatException cause) {
            // The cause is kept because it names the offending text, which the ApiError payload must not carry but
            // an operator reading a stack trace needs; the message is left null so the code's own wording is used.
            throw CashAccountException.of(CashAccountErrorCode.INVALID_AMOUNT, null, cause);
        }

        // Well-formed text is not yet a usable number: "1e1000000000" parses in nanoseconds and then costs seconds
        // of CPU and hundreds of megabytes of digits at the first setScale inside domain/Money. The size is refused
        // at this boundary, the moment the value exists and before anything scales it, so the work a caller's typo or
        // a deliberate probe can buy stays bounded. domain/Money owns the limits; no number appears here.
        return Money.requireWithinInputBounds(parsed);
    }
}
