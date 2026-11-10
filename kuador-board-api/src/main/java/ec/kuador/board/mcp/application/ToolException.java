package ec.kuador.board.mcp.application;

/** Error que el agente debe leer y corregir (argumento faltante, issue ajeno...): vuelve como resultado con isError. */
public class ToolException extends RuntimeException {

    public ToolException(String message) {
        super(message);
    }
}
