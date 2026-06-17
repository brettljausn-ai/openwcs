package org.openwcs.processdesigner.task;

import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Thin wrapper that runs a curated task's HTTP call and converts any transport/4xx/5xx failure into
 * a {@link TaskExecutionException} (which the engine maps to 502 without advancing the instance).
 * The {@link RestClient} is built with the identity-forwarding interceptor, so the operator's
 * {@code X-Auth-*} headers ride along automatically.
 */
final class TaskHttp {

    private TaskHttp() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> postForMap(RestClient http, String uri, Object body, String taskType) {
        try {
            Map<String, Object> response = http.post()
                    .uri(uri)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            return response == null ? Map.of() : response;
        } catch (RestClientException e) {
            throw new TaskExecutionException(taskType + " task failed: " + e.getMessage(), e);
        }
    }

    static Map<String, Object> getForMap(RestClient http, String uri, String taskType, Object... uriVars) {
        try {
            Map<String, Object> response = http.get()
                    .uri(uri, uriVars)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            return response == null ? Map.of() : response;
        } catch (RestClientException e) {
            throw new TaskExecutionException(taskType + " task failed: " + e.getMessage(), e);
        }
    }

    static List<Map<String, Object>> getForList(RestClient http, String uri, String taskType, Object... uriVars) {
        try {
            List<Map<String, Object>> response = http.get()
                    .uri(uri, uriVars)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() { });
            return response == null ? List.of() : response;
        } catch (RestClientException e) {
            throw new TaskExecutionException(taskType + " task failed: " + e.getMessage(), e);
        }
    }
}
