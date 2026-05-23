package com.firstclub.membership.common.idempotency;

import com.firstclub.membership.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class InFlightDuplicateException extends ApiException {
    public InFlightDuplicateException(String scope, String key) {
        super(HttpStatus.CONFLICT, "IDEMPOTENT_DUPLICATE_INFLIGHT",
                "Request with the same Idempotency-Key is already being processed (scope=%s, key=%s)"
                        .formatted(scope, key));
    }
}
