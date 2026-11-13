package com.juzo.ai.ordermanager.mcp.client;

/** A downstream service returned an error or could not be reached. */
public class DownstreamException extends RuntimeException {

    private final int statusCode;

    public DownstreamException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public DownstreamException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    /** HTTP status, or 0 when the service could not be reached. */
    public int statusCode() {
        return statusCode;
    }
}
