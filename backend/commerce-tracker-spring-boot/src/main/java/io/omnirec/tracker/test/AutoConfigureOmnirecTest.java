// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.test;

import io.omnirec.tracker.config.CommerceTrackerAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replaces the HTTP sender with an {@link OmnirecTestRecorder} bean, so a test
 * context needs no collector and can assert on what was tracked.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ImportAutoConfiguration(AutoConfigureOmnirecTest.RecorderConfiguration.class)
public @interface AutoConfigureOmnirecTest {

    @AutoConfiguration(before = CommerceTrackerAutoConfiguration.class)
    class RecorderConfiguration {
        @Bean
        public OmnirecTestRecorder omnirecTestRecorder() {
            return new OmnirecTestRecorder();
        }
    }
}
