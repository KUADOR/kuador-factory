/** Clave de una columna del flujo del proyecto (ej. "IN_PROGRESS", "PROPOSAL"). */
export type IssueStatus = string;

export const PROJECT_KINDS = ["SOFTWARE", "GENERAL"] as const;
/** SOFTWARE habilita metodología, documentos y sincronización con repositorios. */
export type ProjectKind = (typeof PROJECT_KINDS)[number];

export const COLUMN_CATEGORIES = ["TODO", "IN_PROGRESS", "DONE"] as const;
export type ColumnCategory = (typeof COLUMN_CATEGORIES)[number];

/** Tipo de documento: lo define cada metodología del catálogo (ej. "SPEC", "ADR"). */
export type DocumentKind = string;

// ---- Catálogo del espacio de trabajo (configurable; trae valores de fábrica) ----

export type CatalogType = "workflow-templates" | "methodologies" | "roles" | "mcp-servers";

interface CatalogEntry {
  id: string | null;
  key: string;
  name: string;
  /** Viene de fábrica: se puede restaurar a sus valores originales. */
  builtIn: boolean;
  /** Bloqueo optimista: se envía al guardar para no pisar cambios de otra persona. */
  version: number;
}

export interface IssueTypeDef {
  type: IssueType;
  label: string;
}

/**
 * Cómo trabaja una columna: quiénes la cubren (un issue que llega queda sin asignar y lo toma
 * cualquiera con uno de esos roles), si es puerta (solo la cierra una persona con rol aprobador)
 * y a dónde vuelve un issue rechazado.
 */
export interface StageDef {
  column: string;
  roles: string[];
  gate: boolean;
  approvers: string[];
  rejectTo: string | null;
}

export interface WorkflowTemplate extends CatalogEntry {
  description: string | null;
  kind: ProjectKind;
  columns: BoardColumn[];
  issueTypes: IssueTypeDef[];
  stages: StageDef[];
  transitions: Transition[];
}

export type DocumentScope = "PROJECT" | "ISSUE";
export type DocumentCondition = "ALWAYS" | "SUBSTANTIAL";

export interface DocumentDef {
  kind: DocumentKind;
  label: string;
  scope: DocumentScope;
  required: boolean;
  purpose: string;
  /** Ruta en el repo; en documentos de issue lleva {feature} o {task}. */
  pathPattern: string;
  template: string;
  /** Rol que lo escribe (clave del catálogo) o nulo si lo escribe una persona. */
  writtenBy: string | null;
  condition: DocumentCondition;
  agentInstructions: string | null;
}

export interface Methodology extends CatalogEntry {
  summary: string;
  agentRules: string[];
  documents: DocumentDef[];
}

export type RoleAutomation = "NONE" | "REFINE_ISSUE";

export interface AgentRole extends CatalogEntry {
  description: string;
  instructions: string;
  tools: string[];
  automation: RoleAutomation;
}

export type McpTransport = "STDIO" | "HTTP";

export interface McpServer extends CatalogEntry {
  description: string;
  transport: McpTransport;
  command: string | null;
  args: string[];
  url: string | null;
  envVars: { name: string; required: boolean; description: string | null }[];
  docsUrl: string | null;
}

export interface CatalogEntries {
  "workflow-templates": WorkflowTemplate;
  methodologies: Methodology;
  roles: AgentRole;
  "mcp-servers": McpServer;
}

export interface Workspace {
  id: string;
  slug: string;
  name: string;
  /** A qué se dedica la empresa (opcional). */
  description: string | null;
  /** Color base de la marca (#rrggbb): pinta acciones, selección y foco del tablero. */
  brandColor: string;
  /** Texto encima del color de marca (botones principales); nulo = automático. */
  brandOnColor: string | null;
  /** Logo como data URL (png, jpeg o webp) o nulo. */
  logo: string | null;
  createdAt: string;
}

export interface Transition {
  from: string;
  to: string;
}

/** Reglas de transición del flujo. Sin restricción, cualquier movimiento vale. */
export interface Transitions {
  restricted: boolean;
  allowed: Transition[];
}

export interface DocumentMeta {
  id: string;
  projectKey: string;
  issueKey: string | null;
  kind: DocumentKind;
  title: string;
  path: string;
  currentVersion: number;
  createdAt: string;
  updatedAt: string;
  updatedBy: string | null;
}

export interface DocumentWithContent {
  document: DocumentMeta;
  content: string;
}

export interface DocsOverview {
  /** Metodología del proyecto (del catálogo) o nula si aún no eligió. */
  methodology: Methodology | null;
  documents: DocumentMeta[];
}

export interface DocumentVersionMeta {
  version: number;
  authorId: string | null;
  note: string | null;
  createdAt: string;
  length: number;
}

export interface BoardColumn {
  key: string;
  name: string;
  category: ColumnCategory;
}

export const ISSUE_TYPES = ["EPIC", "STORY", "TASK", "BUG"] as const;
export type IssueType = (typeof ISSUE_TYPES)[number];

export const PRIORITIES = ["HIGHEST", "HIGH", "MEDIUM", "LOW", "LOWEST"] as const;
export type Priority = (typeof PRIORITIES)[number];

export type MemberKind = "HUMAN" | "AGENT";

export interface Project {
  id: string;
  key: string;
  name: string;
  description: string | null;
  kind: ProjectKind;
  /** Plantilla del catálogo con la que nació (sus columnas y tipos ya se copiaron al proyecto). */
  templateKey: string;
  /** Clave de la metodología del catálogo. */
  methodology: string | null;
  columns: BoardColumn[];
  issueTypes: IssueTypeDef[];
  transitions: Transitions;
  stages: StageDef[];
  /** Freno de bucles: vueltas atrás permitidas antes de pasar el issue a una persona. */
  maxRework: number;
  createdAt: string;
}

/** EDIT trabaja en el tablero; VIEW solo mira (y cierra las puertas donde su rol aprueba). */
export type MemberAccess = "EDIT" | "VIEW";

export interface Member {
  id: string;
  name: string;
  kind: MemberKind;
  role: string;
  description: string | null;
  avatarColor: string;
  access: MemberAccess;
  createdAt: string;
}

export interface Issue {
  id: string;
  key: string;
  projectKey: string;
  title: string;
  description: string | null;
  type: IssueType;
  status: IssueStatus;
  priority: Priority;
  storyPoints: number | null;
  assigneeId: string | null;
  reporterId: string | null;
  parentKey: string | null;
  labels: string[];
  position: number;
  /** Veces que volvió a una etapa anterior. */
  reworkCount: number;
  /** Persona que puso a los agentes a trabajar: solo sus agentes, en su Claude Code, lo atienden. */
  supervisorId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Comment {
  id: string;
  authorId: string;
  body: string;
  createdAt: string;
}

/** Campos editables de un issue (PUT /api/issues/{key}). */
export interface IssueInput {
  title: string;
  description: string | null;
  type: IssueType;
  priority: Priority;
  storyPoints: number | null;
  assigneeId: string | null;
  parentKey: string | null;
  labels: string[];
}

export interface Me {
  id: string;
  workspaceId: string;
  name: string;
  kind: MemberKind;
  role: string;
  avatarColor: string;
  access: MemberAccess;
}

export interface Session {
  token: string;
  expiresAt: string;
  member: Me;
}

export interface Account {
  memberId: string;
  email: string;
}

export interface AgentToken {
  id: string;
  memberId: string;
  name: string;
  createdAt: string;
  lastUsedAt: string | null;
}

export interface IssuedToken {
  token: string;
  info: AgentToken;
}

export type ActivityType = "CREATED" | "UPDATED" | "MOVED" | "ASSIGNED" | "COMMENTED" | "DELETED" | "DOCUMENT";

export interface Activity {
  id: string;
  eventId: string;
  /** Nulo en documentos del proyecto. */
  issueKey: string | null;
  actorId: string | null;
  type: ActivityType;
  field: string | null;
  from: string | null;
  to: string | null;
  occurredAt: string;
}

export type RepositoryKind = "GITHUB" | "LOCAL";

export interface RepositoryLink {
  linked: boolean;
  kind: RepositoryKind | null;
  location: string | null;
  branch: string | null;
  linkedAt: string | null;
  linkedBy: string | null;
}

export type SyncState = "SYNCED" | "BOARD_AHEAD" | "FILE_AHEAD" | "CONFLICT" | "BOARD_ONLY" | "FILE_DELETED";

export interface SyncDoc {
  id: string;
  issueKey: string | null;
  kind: DocumentKind;
  title: string;
  path: string;
  version: number;
}

export interface SyncEntry {
  document: SyncDoc;
  state: SyncState;
  fileExists: boolean;
  /** PR abierto del tablero que lleva este documento y qué versión exportó. */
  openPr: { number: number; url: string; version: number } | null;
}

export interface Exported {
  documents: number;
  direct: boolean;
  pullRequest: number | null;
  pullRequestUrl: string | null;
}

export interface UntrackedGroup {
  group: string;
  issueScoped: boolean;
  files: { path: string; kind: DocumentKind }[];
  suggestedTitle: string | null;
}

export interface SyncStatus {
  link: RepositoryLink;
  entries: SyncEntry[];
  untracked: UntrackedGroup[];
  openPullRequests: { number: number; url: string; documents: number }[];
}

export interface SyncComparison {
  document: SyncDoc;
  boardContent: string;
  fileContent: string | null;
  state: SyncState;
}

export type AgentRunStatus = "RUNNING" | "SUCCEEDED" | "FAILED" | "SKIPPED";

export interface AgentRun {
  id: string;
  agentId: string;
  issueKey: string;
  projectKey: string;
  requestedBy: string | null;
  status: AgentRunStatus;
  model: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  summary: string | null;
  error: string | null;
  createdAt: string;
  finishedAt: string | null;
}

export interface AgentsStatus {
  enabled: boolean;
  reason: string | null;
  model: string;
}

export type ActionResult<T> = { ok: true; data: T } | { ok: false; error: string };

/** Conexión de agentes locales de una persona (token que solo abre el servidor MCP del tablero). */
export type RunnerToken = AgentToken;

/** Archivo del kit para el repositorio, en el formato del cliente de IA elegido. */
export interface KitFile {
  path: string;
  content: string;
}

/** Cliente de IA con kit propio; «mcp» es cualquier otro cliente con MCP. */
export type AgentClient = "claude-code" | "codex" | "mcp";

/** Cómo se conecta el espacio: servidor MCP (con el nombre de la empresa), variables de entorno y URL. */
export interface AgentKit {
  connection: { server: string; tokenVar: string; urlVar: string; url: string };
  files: KitFile[];
}
