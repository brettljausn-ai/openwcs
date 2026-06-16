package org.openwcs.processdesigner.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs a designer-authored JavaScript snippet (spec §7.2) in a locked-down GraalJS sandbox. This is
 * the ONLY new in-process execution path Phase 3 adds and it is safe by construction:
 *
 * <ul>
 *   <li>{@code allowAllAccess(false)}, {@link HostAccess#NONE} — no Java objects/classes reachable;</li>
 *   <li>{@code allowHostClassLoading(false)}, {@code allowNativeAccess(false)},
 *       {@code allowCreateThread(false)}, {@code allowIO} disabled,
 *       {@link PolyglotAccess#NONE} — no class loading, native calls, threads, filesystem, or
 *       cross-language access;</li>
 *   <li>a {@link ResourceLimits} statement limit (default 100_000) caps runaway computation;</li>
 *   <li>a watchdog thread enforces a wall-clock timeout (default 2s) via {@code context.close(true)}
 *       so an infinite loop is interrupted and the service never hangs.</li>
 * </ul>
 *
 * <p>The script sees ONLY a deep, plain copy of the process data object as a frozen global
 * {@code data} (read) and a no-op {@code console.log}. Its return value (or a global {@code result})
 * must be a plain JSON-able object; it is converted to a Java map and merged back as the task's
 * outputs. Output size is capped. Any parse error, limit breach, timeout, or sandbox-escape attempt
 * (e.g. {@code Java.type}, which is not even defined here) throws {@link ScriptExecutionException}
 * and the service stays up.
 *
 * <p><b>No network or host client is exposed in v1</b> (documented future extension). Designer code
 * is NEVER compiled to host JVM bytecode — it runs only inside this interpreter sandbox.
 */
@Component
public class SandboxedScriptRunner {

    private static final Logger log = LoggerFactory.getLogger(SandboxedScriptRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Wraps the snippet so a bare expression or trailing {@code result} object is captured. */
    private static final String WRAPPER_PREFIX = "(function(){\n";
    private static final String WRAPPER_SUFFIX =
            "\n})();";

    private final long statementLimit;
    private final long timeoutMs;
    private final int maxOutputBytes;
    private final ExecutorService watchdog;

    public SandboxedScriptRunner(
            @org.springframework.beans.factory.annotation.Value("${openwcs.process-designer.scripting.statement-limit:100000}") long statementLimit,
            @org.springframework.beans.factory.annotation.Value("${openwcs.process-designer.scripting.timeout-ms:2000}") long timeoutMs,
            @org.springframework.beans.factory.annotation.Value("${openwcs.process-designer.scripting.max-output-bytes:65536}") int maxOutputBytes) {
        this.statementLimit = statementLimit;
        this.timeoutMs = timeoutMs;
        this.maxOutputBytes = maxOutputBytes;
        AtomicLong n = new AtomicLong();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "script-watchdog-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.watchdog = Executors.newCachedThreadPool(tf);
    }

    /**
     * Parse-only validation (spec §7.2 publish gate): confirm the snippet is syntactically valid JS in
     * the same sandbox, without executing it. Throws {@link ScriptExecutionException} on a syntax error.
     */
    public void validateParses(String script) {
        if (script == null || script.isBlank()) {
            throw new ScriptExecutionException("Script is empty.");
        }
        try (Context context = newSandbox()) {
            // Parsing inside the language verifies syntax without running statements.
            context.parse(Source.create("js", wrap(script)));
        } catch (PolyglotException e) {
            throw new ScriptExecutionException("Script does not parse: " + safeMessage(e), e);
        } catch (Exception e) {
            throw new ScriptExecutionException("Script does not parse.", e);
        }
    }

    /**
     * Execute the snippet against a read-only copy of {@code data}, returning the script's result as a
     * plain map to merge back as task outputs. {@code declaredOutputs} (if non-empty) filters the
     * returned map to just those names.
     */
    public Map<String, Object> run(String script, JsonNode data, List<String> declaredOutputs) {
        if (script == null || script.isBlank()) {
            throw new ScriptExecutionException("Script is empty.");
        }

        Context context = newSandbox();
        // Watchdog: hard-close the context on timeout so an infinite loop / heavy snippet is cut off
        // even before the statement limit would trip. close(true) cancels in-flight execution.
        var deadline = watchdog.submit(() -> {
            try {
                Thread.sleep(timeoutMs);
                context.close(true);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // context already closed by the main thread
            }
            return null;
        });

        try {
            // Expose a deep, plain, frozen copy of the data object as the global `data` (read only).
            Value bindings = context.getBindings("js");
            bindings.putMember("__data_json", data == null ? "{}" : MAPPER.writeValueAsString(data));
            // Build `data` and freeze it inside the guest so the script cannot mutate the shared view;
            // provide a no-op console.log capture so console.log(...) never throws.
            context.eval("js",
                    "var data = Object.freeze(JSON.parse(__data_json)); "
                    + "var console = { log: function(){}, warn: function(){}, error: function(){} }; "
                    + "var result = undefined;");

            Value returned = context.eval(Source.create("js", wrap(script)));
            // Prefer an explicit return value; fall back to a global `result` object.
            Value out = returned != null && !returned.isNull() ? returned : context.getBindings("js").getMember("result");
            return toOutputMap(out, declaredOutputs);
        } catch (PolyglotException e) {
            if (e.isCancelled() || e.isInterrupted()) {
                throw new ScriptExecutionException(
                        "Script exceeded the time limit (" + timeoutMs + "ms) and was stopped.", e);
            }
            if (e.isResourceExhausted()) {
                throw new ScriptExecutionException(
                        "Script exceeded the statement limit (" + statementLimit + ").", e);
            }
            // A guest error, or an attempted sandbox escape (host access is simply undefined here).
            throw new ScriptExecutionException("Script failed: " + safeMessage(e), e);
        } catch (ScriptExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ScriptExecutionException("Script failed to run.", e);
        } finally {
            deadline.cancel(true);
            try {
                context.close(true);
            } catch (Exception ignored) {
                // already closed
            }
        }
    }

    /** A fresh, locked-down GraalJS context. Every option that grants host reach is denied. */
    private Context newSandbox() {
        ResourceLimits limits = ResourceLimits.newBuilder()
                .statementLimit(statementLimit, null)
                .build();
        return Context.newBuilder("js")
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(className -> false)
                .allowHostClassLoading(false)
                .allowNativeAccess(false)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowIO(IOAccess.NONE)
                .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .resourceLimits(limits)
                .build();
    }

    private static String wrap(String script) {
        return WRAPPER_PREFIX + script + WRAPPER_SUFFIX;
    }

    /**
     * Convert the script's return value into a plain output map, enforcing the size cap and (if
     * declared) filtering to the declared output names. A non-object / null result yields an empty map.
     */
    private Map<String, Object> toOutputMap(Value out, List<String> declaredOutputs) {
        if (out == null || out.isNull()) {
            return Map.of();
        }
        if (!out.hasMembers()) {
            throw new ScriptExecutionException(
                    "Script must return a plain object of output values (got a "
                    + (out.isString() ? "string" : out.isNumber() ? "number" : "non-object value") + ").");
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        for (String key : out.getMemberKeys()) {
            Value v = out.getMember(key);
            if (v != null && v.canExecute()) {
                continue; // skip functions
            }
            raw.put(key, toJava(v));
        }
        // Enforce output size after coercion to plain JSON.
        try {
            byte[] json = MAPPER.writeValueAsBytes(raw);
            if (json.length > maxOutputBytes) {
                throw new ScriptExecutionException(
                        "Script output is too large (" + json.length + " > " + maxOutputBytes + " bytes).");
            }
        } catch (ScriptExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ScriptExecutionException("Script output is not serializable.", e);
        }

        if (declaredOutputs == null || declaredOutputs.isEmpty()) {
            return raw;
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (String name : declaredOutputs) {
            if (raw.containsKey(name)) {
                filtered.put(name, raw.get(name));
            }
        }
        return filtered;
    }

    /** Coerce a guest {@link Value} into plain Java (maps/lists/primitives) — never a host proxy. */
    private Object toJava(Value v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isNumber()) {
            if (v.fitsInLong()) {
                return v.asLong();
            }
            return v.asDouble();
        }
        if (v.isString()) {
            return v.asString();
        }
        if (v.hasArrayElements()) {
            int n = (int) Math.min(v.getArraySize(), 10_000);
            java.util.List<Object> list = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(toJava(v.getArrayElement(i)));
            }
            return list;
        }
        if (v.hasMembers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String key : v.getMemberKeys()) {
                Value mv = v.getMember(key);
                if (mv != null && mv.canExecute()) {
                    continue;
                }
                m.put(key, toJava(mv));
            }
            return m;
        }
        return v.toString();
    }

    private static String safeMessage(PolyglotException e) {
        String m = e.getMessage();
        if (m == null) {
            return "guest error";
        }
        // Keep it short and free of host detail.
        return m.length() > 200 ? m.substring(0, 200) : m;
    }
}
