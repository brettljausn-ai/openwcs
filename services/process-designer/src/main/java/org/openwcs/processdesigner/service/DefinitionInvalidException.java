package org.openwcs.processdesigner.service;

import java.util.List;

/**
 * A definition failed validation on publish (unreachable steps, dangling transitions, unresolved
 * {@code writeTo}/{@code task} references, missing {@code start}). Maps to HTTP 422 with the list
 * of problems so the designer can surface them.
 */
public class DefinitionInvalidException extends RuntimeException {

    private final List<String> problems;

    public DefinitionInvalidException(List<String> problems) {
        super("Definition is invalid: " + problems.size() + " problem(s)");
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
