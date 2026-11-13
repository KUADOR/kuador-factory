-- Quien escribió un comentario puede corregirlo (p. ej. acortarlo para que un agente lea menos) o borrarlo.
alter table issue_comment add column edited_at timestamptz;
