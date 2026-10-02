// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.plan;

import java.util.List;

/** One or more tracking plans are invalid. The message lists every problem, one per line. */
public class PlanException extends RuntimeException {

    private final List<String> problems;

    public PlanException(List<String> problems) {
        super("tracking plan has " + problems.size() + " problem(s):\n  " + String.join("\n  ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
