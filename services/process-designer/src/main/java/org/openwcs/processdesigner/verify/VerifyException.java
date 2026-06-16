package org.openwcs.processdesigner.verify;

/**
 * A scan-verify call to master-data failed at the transport/HTTP level (downstream unreachable,
 * 4xx/5xx). Mapped to 502 by {@code ApiExceptionHandler} so the proxy surfaces a clean gateway error
 * instead of 500-ing the service. A clean {@code found:false} from master-data is NOT an error.
 */
public class VerifyException extends RuntimeException {

    public VerifyException(String message, Throwable cause) {
        super(message, cause);
    }
}
