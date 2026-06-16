package org.openwcs.processdesigner.assist;

/**
 * Abstraction over the LLM call for design-time task-assist, so the service is decoupled from the
 * Anthropic SDK and the model can be mocked in tests (no real Anthropic calls). The implementation
 * forces structured output and returns a {@link TaskAssistResult} suggestion. It NEVER changes server
 * state or runs code.
 */
public interface TaskAssistModel {

    /**
     * Ask the model for a task suggestion.
     *
     * @param apiKey       the Anthropic API key (never logged)
     * @param model        the Claude model id (e.g. {@code claude-haiku-4-5})
     * @param systemPrompt grounding: the live curated task catalog + the available data variables
     * @param description  the designer's plain-language description
     * @return the structured suggestion (curated mapping, script draft, or none)
     */
    TaskAssistResult suggest(String apiKey, String model, String systemPrompt, String description);
}
