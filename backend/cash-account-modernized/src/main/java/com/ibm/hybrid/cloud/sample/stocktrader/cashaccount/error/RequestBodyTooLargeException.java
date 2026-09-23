package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error;

import java.io.IOException;

// WHY AN IOException AND NOT A CashAccountException. This is thrown from inside a ServletInputStream, mid-read,
// by config/RequestBodySizeLimitFilter's counting wrapper - a place whose only declared checked exception is
// IOException. A runtime exception thrown there would escape through Jackson's parser as an unrecognized failure
// and be rendered by the catch-all as 500 INTERNAL; as an IOException it is caught by Spring's message converter
// and re-thrown as HttpMessageNotReadableException carrying this instance as its cause, which is the chain
// error/ApiExceptionHandler resolves back to 413 REQUEST_TOO_LARGE.
//
// The message carries the LIMIT and never the body, not one byte of it: this exception reaches a log record, and
// the whole point of refusing an oversized body is to avoid materializing it.
/** Signals that a request body exceeded the permitted byte count; carries the limit, never the body. */
public final class RequestBodyTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    private final long limitBytes;

    /**
     * @param limitBytes the cap the body exceeded, in bytes
     */
    public RequestBodyTooLargeException(long limitBytes) {
        super("Request body exceeds the permitted " + limitBytes + " bytes.");
        this.limitBytes = limitBytes;
    }

    public long limitBytes() {
        return limitBytes;
    }
}
