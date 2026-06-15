package org.openwcs.assistant.chat;

/**
 * Per-request holder for the active warehouseId, used to scope the assistant's tool calls. The
 * service sets it before invoking the {@link ChatModel}; {@link AnthropicChatModel} reads it when
 * executing a tool so the model only has to supply the optional, item-level parameters (sku/huId/etc.)
 * while every tool stays scoped to the right warehouse. Thread-local because each chat request is
 * handled on one servlet thread.
 */
public final class AssistantContext {

    private static final ThreadLocal<String> WAREHOUSE = new ThreadLocal<>();

    private AssistantContext() {
    }

    public static void setWarehouseId(String warehouseId) {
        WAREHOUSE.set(warehouseId);
    }

    public static String warehouseId() {
        return WAREHOUSE.get();
    }

    public static void clear() {
        WAREHOUSE.remove();
    }
}
