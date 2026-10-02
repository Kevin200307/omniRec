// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventprocessing.queue;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * {@code /actuator/deadletters}: dead-letter queue sizes, and replay.
 *
 * <pre>
 *   GET  /actuator/deadletters                      waiting per destination
 *   GET  /actuator/deadletters/{destination}        waiting for one
 *   POST /actuator/deadletters/{destination}        {"dryRun": false, "max": 500}
 * </pre>
 *
 * A POST is a dry run unless {@code "dryRun": false} is sent. Not exposed over
 * HTTP by default: add {@code deadletters} to
 * {@code management.endpoints.web.exposure.include} and protect the
 * management port, since replay moves data.
 */
@Endpoint(id = "deadletters")
public class DeadLetterEndpoint {

    private final DeadLetterReplayer replayer;

    public DeadLetterEndpoint(DeadLetterReplayer replayer) {
        this.replayer = replayer;
    }

    @ReadOperation
    public Map<String, Long> counts() {
        return replayer.counts();
    }

    @ReadOperation
    public DeadLetterReplayer.ReplayResult count(@Selector String destination) {
        return replayer.replay(destination, 1, true);
    }

    @WriteOperation
    public DeadLetterReplayer.ReplayResult replay(@Selector String destination, @Nullable Boolean dryRun,
                                                  @Nullable Integer max) {
        return replayer.replay(destination, max == null ? 1000 : max, dryRun == null || dryRun);
    }
}
