package ec.kuador.board.mcp.application;

import java.util.Map;

/**
 * Resultado de una herramienta que trae una imagen: va al cliente MCP como bloque de imagen (el modelo la ve), junto con
 * sus datos en texto.
 *
 * @param data la imagen en base64
 */
public record ToolImage(String mimeType, String data, Map<String, Object> meta) {}
