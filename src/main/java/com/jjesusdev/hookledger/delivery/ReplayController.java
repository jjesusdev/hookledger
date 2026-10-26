package com.jjesusdev.hookledger.delivery;

import com.jjesusdev.hookledger.config.HookledgerProperties;
import com.jjesusdev.hookledger.ingest.WebhookRejectedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/v1/admin")
@ConditionalOnExpression("'${hookledger.admin.token:}' != ''")
public class ReplayController {

    private final ReplayService replayService;
    private final HookledgerProperties properties;

    public ReplayController(ReplayService replayService, HookledgerProperties properties) {
        this.replayService = replayService;
        this.properties = properties;
    }

    @PostMapping("/replay")
    public ReplayResponse replay(@RequestHeader("X-Admin-Token") String token,
                                 @RequestParam(defaultValue = "10") int max) {
        authorize(token);
        if (max < 1 || max > 10) {
            throw new WebhookRejectedException(400, "INVALID_REPLAY",
                    "max must be between 1 and 10");
        }
        return new ReplayResponse(replayService.replay(max));
    }

    private void authorize(String token) {
        byte[] expected = properties.admin().token().getBytes(StandardCharsets.UTF_8);
        byte[] provided = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new WebhookRejectedException(401, "UNAUTHORIZED", "Invalid admin token");
        }
    }

    public record ReplayResponse(int replayed) {
    }
}
