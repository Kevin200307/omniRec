// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.webhook;

/** The webhook body is not what the adapter expects. Answered with 400; the message is returned to the sender. */
public class WebhookPayloadException extends RuntimeException {

    public WebhookPayloadException(String message) {
        super(message);
    }

    public WebhookPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
