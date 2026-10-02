// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker;

import io.omnirec.commerce.catalog.generated.StandardEvents;
import io.omnirec.tracker.annotation.TrackEvent;
import io.omnirec.tracker.config.CommerceTrackerAutoConfiguration;
import io.omnirec.tracker.test.AutoConfigureOmnirecTest;
import io.omnirec.tracker.test.OmnirecTestRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** @TrackEvent in a real Spring context, with the test recorder from @AutoConfigureOmnirecTest. */
class TrackEventAspectTest {

    public record Line(String productId, int quantity) {
    }

    public record Order(String id, String customerId, BigDecimal total, String currency, List<Line> lines) {
    }

    public static class CheckoutService {
        @TrackEvent(value = StandardEvents.PURCHASE_COMPLETED,
                data = "{order: {id: #result.id, total: #result.total, currency: #result.currency, items: #result.lines}}",
                userId = "#result.customerId",
                businessKey = "#result.id")
        public Order placeOrder(String customerId) {
            return new Order("o1", customerId, new BigDecimal("24.00"), "USD", List.of(new Line("P1", 2)));
        }

        @TrackEvent(value = StandardEvents.ORDER_CANCELLED, data = "{order: {id: #orderId}}", userId = "'c_1'")
        public void cancel(String orderId, boolean fail) {
            if (fail) throw new IllegalStateException("payment provider down");
        }

        @TrackEvent(value = StandardEvents.ORDER_CANCELLED, data = "{order: {id: #result.nope}}", userId = "'c_1'")
        public String broken() {
            return "ok";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class App {
        @Bean
        CheckoutService checkoutService() {
            return new CheckoutService();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, CommerceTrackerAutoConfiguration.class,
                    AutoConfigureOmnirecTest.RecorderConfiguration.class))
            .withUserConfiguration(App.class);

    @Test
    void tracksTheReturnValueAfterTheMethodSucceeds() {
        runner.run(context -> {
            context.getBean(CheckoutService.class).placeOrder("c_1");
            context.getBean(OmnirecTestRecorder.class).assertThat("purchase_completed")
                    .withUserId("c_1")
                    .withEventId("evt:purchase_completed:o1")
                    .withData("order.id", "o1")
                    .withData("order.total", new BigDecimal("24.00"))
                    .withData("order.currency", "USD");
        });
    }

    @Test
    void readsMethodParametersByName() {
        runner.run(context -> {
            context.getBean(CheckoutService.class).cancel("o7", false);
            context.getBean(OmnirecTestRecorder.class).assertThat("order_cancelled").withData("order.id", "o7");
        });
    }

    @Test
    void tracksNothingWhenTheMethodThrows() {
        runner.run(context -> {
            assertThrows(IllegalStateException.class, () -> context.getBean(CheckoutService.class).cancel("o7", true));
            context.getBean(OmnirecTestRecorder.class).assertNotTracked("order_cancelled");
        });
    }

    @Test
    void aBrokenExpressionIsLoggedNotThrown() {
        runner.run(context -> {
            assertEquals("ok", context.getBean(CheckoutService.class).broken());
            assertTrue(context.getBean(OmnirecTestRecorder.class).events().isEmpty());
        });
    }

    @Test
    void needsOnlyAnEndpointWithoutTheRecorder() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommerceTrackerAutoConfiguration.class))
                .withPropertyValues("omnirec.tracker.endpoint=http://localhost:1", "omnirec.tracker.async=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(OmnirecTracker.class));
                });
    }

    @Test
    void explainsAMissingEndpoint() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommerceTrackerAutoConfiguration.class))
                .run(context -> assertTrue(context.getStartupFailure().getMessage().contains("omnirec.tracker.endpoint")
                        || String.valueOf(context.getStartupFailure().getCause()).contains("omnirec.tracker.endpoint")));
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> unused() {
        return Map.of();
    }
}
