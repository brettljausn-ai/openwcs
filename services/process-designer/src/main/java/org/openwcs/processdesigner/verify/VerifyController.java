package org.openwcs.processdesigner.verify;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scan-verify proxy ({@code POST /api/process-designer/verify}). The handheld runtime posts a
 * scanned/typed value; the proxy resolves it against master-data (forwarding the operator identity)
 * and returns a normalised {@link VerifyResult} the runtime branches on and stores from. Read-only
 * and stateless. Gated by {@code PROCESS_DESIGN_VIEW} (the same permission operators use to run
 * processes) via {@code RbacFilter}. A clean {@code found:false} is a 200 passthrough; a downstream
 * failure surfaces as 502 (see {@code ApiExceptionHandler}).
 */
@RestController
@RequestMapping("/api/process-designer")
public class VerifyController {

    private final VerifyService verifyService;

    public VerifyController(VerifyService verifyService) {
        this.verifyService = verifyService;
    }

    @PostMapping("/verify")
    public VerifyResult verify(@Valid @RequestBody VerifyRequest request) {
        return verifyService.verify(request);
    }
}
