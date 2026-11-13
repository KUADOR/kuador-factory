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

export type CatalogType = "workflow-templates" | "methodologies" | "roles" | "mcp-servers" | "component-templates";

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

export type ComponentKind = "BACKEND" | "FRONTEND" | "MICROFRONTEND" | "SHELL" | "MOBILE" | "LIBRARY" | "OTHER";

/**
 * Plantilla de pieza (API, web, microservicio, microfrontend...). Sin {@code templateRepo}, la pieza nace
 * solo con su README y sus comandos; con él, se copia su código base en {@code templateRef}.
 */
export interface ComponentTemplate extends CatalogEntry {
  description: string;
  kind: ComponentKind;
  stack: string;
  templateRepo: string | null;
  templateRef: string | null;
  variables: { name: string; description: string | null; defaultValue: string | null }[];
  commands: { build: string | null; test: string | null; run: string | null };
  ciChecks: string[];
  suggestedRoles: string[];
  mcpServers: string[];
}

/** Una pieza de un proyecto y dónde vive. {@code path} vacío = raíz de su propio repo. */
export interface ProjectPiece {
  name: string;
  templateKey: string | null;
  templateVersion: number | null;
  templateRef: string | null;
  kind: ComponentKind;
  stack: string;
  repository: string;
  path: string;
  build: string | null;
  test: string | null;
  run: string | null;
  createdAt: string;
}

/** Una pieza a crear: sin {@code repoName} va en una carpeta del repo del proyecto; con él, en un repo propio. */
export interface PieceInput {
  name: string;
  templateKey: string | null;
  repoName: string | null;
  variables: Record<string, string>;
}

export interface PiecesSetup {
  installationId: number;
  repoName: string;
  description: string | null;
  privateRepo: boolean;
  pieces: PieceInput[];
}

export interface SetupResult {
  link: RepositoryLink;
  repositories: { repository: string; url: string | null; protectedBranches: string[]; warnings: string[]; pullRequestUrl: string | null }[];
  pieces: ProjectPiece[];
  documents: number;
}

export interface CatalogEntries {
  "workflow-templates": WorkflowTemplate;
  methodologies: Methodology;
  roles: AgentRole;
  "mcp-servers": McpServer;
  "component-templates": ComponentTemplate;
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
  /** Idioma del equipo (documentos, comentarios, textos para el usuario final) y del código. ISO 639-1. */
  languages: { team: Language; code: Language };
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

/**
 * Nivel en el espacio. OWNER: además el plan, los pagos y quién administra. ADMIN: el equipo, el catálogo, la IA,
 * las conexiones y la auditoría. EDIT: trabaja en los tableros. VIEW: solo mira (y cierra las puertas donde su rol
 * aprueba).
 */
export type MemberAccess = "OWNER" | "ADMIN" | "EDIT" | "VIEW";

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
  /** Tarea del plan de una épica: rol que la trabaja (p. ej. BACKEND). */
  workRole: string | null;
  /** Tarea del plan de una épica: claves de las tareas que deben terminar antes. */
  dependsOn: string[];
  createdAt: string;
  updatedAt: string;
}

export interface Comment {
  id: string;
  authorId: string;
  body: string;
  createdAt: string;
  /** Cuándo lo corrigió su autor (null si nunca). */
  editedAt: string | null;
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
  /** Administra planes y licencias de la plataforma. */
  platformAdmin: boolean;
}

/** Plan que se vende. Límites nulos = sin límite; `factory` incluye la fábrica de software. */
export interface Plan {
  code: string;
  name: string;
  tagline: string;
  priceMonthly: number;
  currency: string;
  features: string[];
  maxMembers: number | null;
  maxProjects: number | null;
  factory: boolean;
  trialDays: number;
  highlighted: boolean;
  sortOrder: number;
  active: boolean;
}

export type LicenseStatus = "TRIAL" | "ACTIVE" | "PENDING_PAYMENT" | "CANCELED";

/** Plan del espacio; `effective` es el que rige hoy (el gratuito si venció la prueba o falta el pago). */
export interface WorkspacePlan {
  plan: Plan;
  effective: Plan;
  status: LicenseStatus;
  trialEndsAt: string | null;
  paidUntil: string | null;
  trialUsed: boolean;
  /** Con cuántos días de anticipación se avisa el vencimiento. */
  warnDays: number;
}

export interface WorkspaceLicense {
  workspaceId: string;
  slug: string;
  name: string;
  createdAt: string;
  plan: string | null;
  status: LicenseStatus | null;
  trialEndsAt: string | null;
  paidUntil: string | null;
}

export type PlanInput = Omit<Plan, "code">;

/** Estado de una entrada del catálogo de la plataforma frente a lo publicado. */
export type PlatformEntryStatus = "PUBLISHED" | "MODIFIED" | "NEW";

export interface PlatformCatalogEntry<T = CatalogEntries[CatalogType]> {
  key: string;
  status: PlatformEntryStatus;
  entry: T;
  /** Solo roles: nombre y color del agente que nace con cada espacio. */
  memberName: string | null;
  color: string | null;
}

export interface PlatformCatalogSummary {
  version: number;
  pending: number;
  releases: { version: number; added: Record<string, string>[]; updated: Record<string, string>[]; publishedAt: string }[];
}

/** Paquete por industria: plantillas recomendadas y el tablero con el que arranca un espacio nuevo. */
export interface CatalogPackage {
  code: string;
  name: string;
  description: string;
  templates: string[];
  starterTemplate: string | null;
  starterKey: string | null;
  starterName: string | null;
  sortOrder: number;
  active: boolean;
}

/** Dónde trabaja un agente: en la PC de una persona (MCP) o en el tablero con la clave de IA de la empresa. */
export type AgentMode = "LOCAL" | "SERVER";

/** Una persona del espacio frente a un tablero. {@code implicit}: lo ve por su nivel (dueño o administra). */
export interface BoardPerson {
  id: string;
  name: string;
  role: string;
  avatarColor: string;
  access: MemberAccess;
  inBoard: boolean;
  implicit: boolean;
}

export interface TeamAgent {
  id: string;
  name: string;
  role: string;
  roleName: string;
  description: string | null;
  avatarColor: string;
  mode: AgentMode;
  /** null = el modelo por defecto del espacio. */
  model: string | null;
  inTeam: boolean;
}

/** Consumo de IA del mes (agentes que trabajan en el tablero). Costos en USD; nulos si falta el precio de algún modelo. */
export interface AiUsageReport {
  month: string;
  runs: number;
  inputTokens: number;
  outputTokens: number;
  costUsd: number | null;
  byAgent: { agentId: string; name: string; runs: number; inputTokens: number; outputTokens: number; costUsd: number | null }[];
  budgetUsd: number | null;
  unpricedModels: string[];
}

export interface AiModelPrice {
  model: string;
  inputPerMTok: number;
  outputPerMTok: number;
}

/** Una línea de la auditoría: quién, qué, sobre qué, cómo terminó y desde dónde. */
export interface AuditItem {
  id: string;
  occurredAt: string;
  workspaceId: string | null;
  actorId: string | null;
  actorName: string | null;
  actorKind: "HUMAN" | "AGENT" | "SYSTEM" | "ANONYMOUS";
  label: string;
  action: string;
  category: "SECURITY" | "WORK";
  target: string | null;
  status: number;
  outcome: "OK" | "DENIED" | "FAILED";
  ip: string | null;
  userAgent: string | null;
}

export interface AuditPage {
  items: AuditItem[];
  /** Para seguir hacia atrás; nulo si no hay más. */
  next: string | null;
}

/** Un proveedor de IA que la empresa puede elegir para sus agentes en el tablero. */
export interface AiProviderOption {
  code: string;
  name: string;
  keyHint: string | null;
  models: string[];
  /** Con ejecución de código propia (Claude); los demás usan las herramientas del tablero. */
  codeExecution: boolean;
}

export interface WorkspaceAiStatus {
  configured: boolean;
  /** Proveedor de la clave guardada (o el sugerido si no hay clave). */
  provider: string | null;
  keyHint: string | null;
  defaultModel: string;
  models: string[];
  providers: AiProviderOption[];
}

/** Proveedor de IA tal como lo administra la plataforma. */
export interface AiProvider {
  code: string;
  name: string;
  protocol: "ANTHROPIC" | "OPENAI_COMPATIBLE";
  baseUrl: string;
  models: string[];
  keyHint: string | null;
  active: boolean;
  sortOrder: number;
}

export interface Attachment {
  id: string;
  issueKey: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  uploadedBy: string | null;
  createdAt: string;
}

/** Adjuntos de una tarjeta; `available` es falso si el servidor no tiene almacenamiento configurado. */
export interface AttachmentListing {
  available: boolean;
  maxBytes: number;
  items: Attachment[];
}

export interface PaymentField {
  key: string;
  label: string;
  secret: boolean;
  required: boolean;
  hint: string | null;
}

/** Tipo de pasarela con adaptador en el servidor (PayPal, …) y las credenciales que pide. */
export interface PaymentType {
  type: string;
  label: string;
  fields: PaymentField[];
}

export type PaymentMode = "SANDBOX" | "LIVE";

/** Pasarela configurada; los secretos nunca vuelven, solo se sabe si están puestos. */
export interface PaymentProviderView {
  code: string;
  type: string;
  name: string;
  mode: PaymentMode;
  active: boolean;
  sortOrder: number;
  values: Record<string, string>;
  secretsSet: string[];
  webhookUrl: string;
}

export interface PaymentOption {
  code: string;
  name: string;
}

export interface Payment {
  id: string;
  planCode: string;
  providerCode: string;
  amount: number;
  currency: string;
  months: number;
  status: "CREATED" | "PAID" | "FAILED";
  createdAt: string;
  paidAt: string | null;
}

export interface TelegramGroup {
  id: string;
  chatId: number;
  title: string;
  onAssigned: boolean;
  onGates: boolean;
  onPlans: boolean;
}

/** Telegram del espacio: su bot propio (si tiene), el de respaldo de la plataforma y los grupos vinculados. */
export interface TelegramSetup {
  ownBot: string | null;
  platformBot: string | null;
  groups: TelegramGroup[];
}

/** Invitación por correo pendiente de aceptar. */
export interface Invitation {
  id: string;
  memberId: string;
  memberName: string;
  email: string;
  createdAt: string;
  expiresAt: string;
  expired: boolean;
}

export interface InvitationPreview {
  workspaceName: string;
  memberName: string;
  email: string;
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

/** GITHUB_APP: con la app de la plataforma (sin token personal); GITHUB: con un token; LOCAL: carpeta. */
export type RepositoryKind = "GITHUB_APP" | "GITHUB" | "LOCAL";

export interface RepositoryLink {
  linked: boolean;
  kind: RepositoryKind | null;
  location: string | null;
  branch: string | null;
  /** Solo GITHUB_APP: la conexión de GitHub del espacio. */
  installationId: number | null;
  linkedAt: string | null;
  linkedBy: string | null;
}

/** Organización (o cuenta) de GitHub conectada al espacio: la app de la plataforma está instalada ahí. */
export interface GitHubConnection {
  installationId: number;
  account: string;
  accountType: "Organization" | "User";
  linkedAt: string;
}

export interface GitHubOverview {
  /** La app de la plataforma; nula hasta que quien opera la plataforma la registre. */
  app: { slug: string; name: string; ownerLogin: string; htmlUrl: string } | null;
  canRegister: boolean;
  connections: GitHubConnection[];
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

/** `status`: el código HTTP de la API cuando falló (p. ej. 409 si algo ya existe). */
export type ActionResult<T> = { ok: true; data: T } | { ok: false; error: string; status?: number };

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
  connection: { server: string; tokenVar: string; url: string };
  files: KitFile[];
}

export type Language = "es" | "en" | "pt";

export type KitFileState = "CURRENT" | "OUTDATED" | "MISSING";

/** Kit de agentes en un repo del proyecto y el PR del tablero que lo actualiza, si hay uno abierto. */
export interface RepoKit {
  repository: string;
  files: { path: string; state: KitFileState }[];
  pullRequest: { number: number; url: string; branch: string } | null;
}

/** Cliente de IA que pide conectarse (pantalla de permiso OAuth). */
export interface OAuthClientInfo {
  clientId: string;
  name: string;
  redirectHost: string;
  local: boolean;
}

/** Lo que una tarjeta muestra del plan de una épica: rol de la tarea, a qué espera y, en la épica, su avance. */
export interface CardPlan {
  role?: string;
  waitingFor?: string[];
  progress?: { done: number; total: number };
  /** Épica con el reparto de tareas esperando aprobación. */
  planPending?: boolean;
}

/** Tarea del plan de una épica (reparto). */
export interface EpicTask {
  key: string;
  title: string;
  status: string;
  statusName: string;
  workRole: string;
  dependsOn: string[];
  pendingDependencies: string[];
  assigneeName: string | null;
}

/** Avisos de una persona: canales y qué avisos recibe. */
export interface NotificationSettings {
  email: boolean;
  telegram: boolean;
  telegramChatId: number | null;
  onAssigned: boolean;
  onGates: boolean;
  onPlans: boolean;
}

export interface NotificationView {
  settings: NotificationSettings;
  /** Correo con el que entra (ahí llegan los avisos). */
  email: string | null;
  /** El servidor tiene SMTP / bot de Telegram configurados. */
  emailAvailable: boolean;
  telegramAvailable: boolean;
  telegramBot: string | null;
}
