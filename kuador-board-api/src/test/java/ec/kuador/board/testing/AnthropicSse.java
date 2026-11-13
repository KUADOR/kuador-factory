package ec.kuador.board.testing;

/** Para APIs falsas de Anthropic: convierte un mensaje completo en la respuesta en streaming (SSE). */
public final class AnthropicSse {

    private AnthropicSse() {}

    /** El mensaje completo partido en los eventos SSE que manda la API en streaming. */
    public static String of(String messageJson) {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var msg = (tools.jackson.databind.node.ObjectNode) json.readTree(messageJson);
        var content = msg.get("content");
        var start = msg.deepCopy();
        start.putArray("content");
        start.putNull("stop_reason");
        StringBuilder out = new StringBuilder();
        java.util.function.BiConsumer<String, Object> event = (name, data) -> out.append("event: ").append(name).append("\ndata: ")
                .append(json.writeValueAsString(data)).append("\n\n");
        event.accept("message_start", java.util.Map.of("type", "message_start", "message", start));
        for (int i = 0; i < content.size(); i++) {
            var block = (tools.jackson.databind.node.ObjectNode) content.get(i).deepCopy();
            String kind = block.get("type").asString();
            Object delta = null;
            if (kind.equals("tool_use") || kind.equals("server_tool_use")) {
                delta = java.util.Map.of("type", "input_json_delta", "partial_json", json.writeValueAsString(block.get("input")));
                block.putObject("input");
            } else if (kind.equals("text")) {
                delta = java.util.Map.of("type", "text_delta", "text", block.get("text").asString());
                block.put("text", "");
            }
            event.accept("content_block_start", java.util.Map.of("type", "content_block_start", "index", i, "content_block", block));
            if (delta != null) event.accept("content_block_delta", java.util.Map.of("type", "content_block_delta", "index", i, "delta", delta));
            event.accept("content_block_stop", java.util.Map.of("type", "content_block_stop", "index", i));
        }
        var end = new java.util.LinkedHashMap<String, Object>();
        end.put("stop_reason", msg.get("stop_reason").asString());
        end.put("stop_sequence", null);
        event.accept("message_delta", java.util.Map.of("type", "message_delta", "delta", end, "usage", msg.get("usage")));
        event.accept("message_stop", java.util.Map.of("type", "message_stop"));
        return out.toString();
    }

}
