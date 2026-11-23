-- Conexiones que entran iniciando sesión (OAuth) en vez de con una clave: Outlook, Gmail y otros servidores MCP que lo piden.
-- Guarda, cifrado con BOARD_SECRETS_KEY, el cliente registrado y los tokens de la sesión; nulo en las que usan clave.
alter table business_connection add column oauth text;
