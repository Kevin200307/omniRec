// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.annotation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.omnirec.tracker.OmnirecTracker;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Implements {@link TrackEvent}. Registered by the starter when Spring AOP is on the classpath. */
@Aspect
public class TrackEventAspect {

    private static final Logger log = LoggerFactory.getLogger(TrackEventAspect.class);

    private final OmnirecTracker tracker;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer names = new DefaultParameterNameDiscoverer();
    private final Map<String, Expression> expressions = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public TrackEventAspect(OmnirecTracker tracker) {
        this.tracker = tracker;
    }

    @AfterReturning(pointcut = "@annotation(trackEvent)", returning = "result")
    public void afterReturning(JoinPoint joinPoint, TrackEvent trackEvent, Object result) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        try {
            MethodBasedEvaluationContext context =
                    new MethodBasedEvaluationContext(result, method, joinPoint.getArgs(), names);
            context.setVariable("result", result);

            Map<String, Object> data = toMap(evaluate(trackEvent.data(), context));
            String userId = asString(evaluate(trackEvent.userId(), context));
            String businessKey = asString(evaluate(trackEvent.businessKey(), context));
            tracker.track(trackEvent.value(), data, userId, businessKey);
        } catch (RuntimeException e) {
            log.error("@TrackEvent(\"{}\") on {}.{} could not track: {}", trackEvent.value(),
                    method.getDeclaringClass().getSimpleName(), method.getName(), e.getMessage());
        }
    }

    private Object evaluate(String expression, MethodBasedEvaluationContext context) {
        if (expression == null || expression.isBlank()) return null;
        return expressions.computeIfAbsent(expression, parser::parseExpression).getValue(context);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        if (value == null) return Map.of();
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) json.convertValue(map, Map.class);
        return json.convertValue(value, Map.class);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
