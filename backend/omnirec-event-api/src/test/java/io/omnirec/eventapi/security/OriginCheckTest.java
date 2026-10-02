// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class OriginCheckTest {

    private static MockHttpServletRequest request(String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/events");
        request.addHeader("Host", host);
        return request;
    }

    @Test
    void recognisesTheCollectorsOwnOrigin() {
        assertTrue(EventApiRequestFilter.isSameOrigin(request("shop.example"), "https://shop.example"));
        assertTrue(EventApiRequestFilter.isSameOrigin(request("localhost:8124"), "http://localhost:8124"));
    }

    @Test
    void rejectsOtherOrigins() {
        assertFalse(EventApiRequestFilter.isSameOrigin(request("shop.example"), "https://evil.example"));
        assertFalse(EventApiRequestFilter.isSameOrigin(request("localhost:8124"), "http://localhost:3000"));
        assertFalse(EventApiRequestFilter.isSameOrigin(request("shop.example"), "not a url"));
        assertFalse(EventApiRequestFilter.isSameOrigin(new MockHttpServletRequest(), "https://shop.example"));
    }
}
