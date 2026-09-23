package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.fx;

/** Signals that no exchange rate could be determined; the caller translates it to 503 EXCHANGE_RATE_UNAVAILABLE. */
public class ExchangeRateUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    // Optional context, so a translating catch block or log line can name the pair; both are null when the failure
    // has no pair to report, such as a client-side configuration fault.
    private final String base;
    private final String quote;

    public ExchangeRateUnavailableException(String message) {
        this(message, null, null, null);
    }

    public ExchangeRateUnavailableException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    private ExchangeRateUnavailableException(String message, Throwable cause, String base, String quote) {
        super(message, cause);
        this.base = base;
        this.quote = quote;
    }

    public static ExchangeRateUnavailableException forPair(String base, String quote, String reason) {
        return forPair(base, quote, reason, null);
    }

    public static ExchangeRateUnavailableException forPair(String base, String quote, String reason, Throwable cause) {
        return new ExchangeRateUnavailableException(describe(base, quote, reason), cause, base, quote);
    }

    public String base() {
        return base;
    }

    public String quote() {
        return quote;
    }

    // Absent codes render as a question mark rather than failing, because this runs while an error is already
    // being reported. The message reaches the logs, so a caller's reason must never carry the endpoint, a
    // credential or a response body.
    private static String describe(String base, String quote, String reason) {
        StringBuilder message = new StringBuilder("no exchange rate for ")
                .append(base == null ? "?" : base)
                .append("->")
                .append(quote == null ? "?" : quote);
        if (reason != null && !reason.isBlank()) {
            message.append(": ").append(reason);
        }
        return message.toString();
    }
}
