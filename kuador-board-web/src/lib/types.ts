/** Clave de una columna del flujo del proyecto (ej. "IN_PROGRESS", "PROPOSAL"). */
export type IssueStatus = string;

export const PROJECT_KINDS = ["SOFTWARE", "GENERAL"] as const;
/** Dato anterior a las capacidades: SOFTWARE = el tablero trabaja con un repositorio de código. */
export type ProjectKind = (typeof PROJECT_KINDS)[number];

/**
 * Lo que un tablero sabe hacer además del flujo: DOCUMENTS (manual de trabajo y documentos con versiones), REPOSITORY
 * (repositorio de código: piezas, PR, CI) y SPLIT (repartir una tarjeta grande en tareas).
 */
export const CAPABILITIES = ["DOCUMENTS", "REPOSITORY", "SPLIT"] as const;
export type Capability = (typeof CAPABILITIES)[number];

export const COLUMN_CATEGORIES = ["TODO", "IN_PROGRESS", "DONE"] as const;
export type ColumnCategory = (typeof COLUMN_CATEGORIES)[number];

/** Tipo de documento: lo define cada metodología del catálogo (ej. "SPEC", "ADR"). */
export type DocumentKind = string;

// ---- Catálogo del espacio de trabajo (configurable; trae valores de fábrica) ----

export type CatalogType = "workflow-templates" | "methodologies" | "roles" | "mcp-servers" | "component-templates" | "agent-policies";

interface CatalogEntry {
  id: string | null;
  key: string;
  name: string;
  /** Viene de fábrica: se puede restaurar a sus valores originales. */
  builtIn: boolean;
  /** Bloqueo optimista: se envía al guardar para no pisar cambios de otra persona. */
  version: number;
}

export const FIELD_KINDS = ["TEXT", "NUMBER", "MONEY", "DATE", "CHOICE"] as const;
export type FieldKind = (typeof FIELD_KINDS)[number];

/** Un dato propio de un tipo de tarjeta (p. ej. «Monto»). `options` solo en CHOICE. */
export interface FieldDef {
  key: string;
  label: string;
  kind: FieldKind;
  options: string[];
}

export const ESTIMATE_UNITS = ["POINTS", "HOURS", "NONE"] as const;
/** En qué mide el tablero el tamaño de sus tarjetas; NONE = no estima. */
export type EstimateUnit = (typeof ESTIMATE_UNITS)[number];

export interface IssueTypeDef {
  type: IssueType;
  label: string;
  /** Datos propios del tipo; la API los completa con una lista vacía si faltan. */
  fields?: FieldDef[];
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
  /** Quién cierra la puerta: null = una persona. */
  review?: AgentReview | null;
  /** En una etapa de trabajo: el reparto de tareas de una épica arranca solo al llegar aquí. */
  autoPlan?: boolean;
  /** Lo que hay que verificar antes de sacar la tarjeta de la etapa (quien aprueba o quien entrega). */
  checklist?: string[];
  /** Lo que la tarjeta debe tener para salir de la etapa; lo hace cumplir el tablero. */
  requires?: Requirement[];
}

/** Un requisito de etapa: un adjunto, un documento de tipo `ref` o el dato propio `ref` lleno. */
export interface Requirement {
  kind: "ATTACHMENT" | "DOCUMENT" | "FIELD";
  ref: string | null;
}

/** Quién cierra una puerta: una persona, un agente revisor, o el agente con escalamiento a una persona. */
export type ReviewMode = "PERSON" | "AGENT" | "AGENT_ESCALATE";

export interface AgentReview {
  mode: ReviewMode;
  /** Rol del agente revisor (por defecto PR_REVIEWER). */
  reviewerRole: string | null;
  /** Rutas que siempre decide una persona (patrones glob). */
  sensitive: string[];
  /** Más líneas cambiadas que esto pasa a una persona; null = sin límite. */
  maxChangedLines: number | null;
  /** Al aprobar el agente, el tablero fusiona los PR de la tarjeta. */
  merge?: boolean;
}

export interface WorkflowTemplate extends CatalogEntry {
  description: string | null;
  kind: ProjectKind;
  /** Lo que usa el tablero; la API las completa si faltan. */
  capabilities?: Capability[];
  estimate?: EstimateUnit;
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

export type ComponentKind = "BACKEND" | "FRONTEND" | "MONOLITH" | "MICROFRONTEND" | "SHELL" | "MOBILE" | "LIBRARY" | "OTHER";

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
  /** Lo que el equipo quiere que los agentes sepan de la pieza (versiones, convenciones). */
  notes: string | null;
  createdAt: string;
}

/** Lo que se ajusta de una pieza ya creada. */
export interface PieceUpdate {
  stack: string;
  build: string | null;
  test: string | null;
  run: string | null;
  notes: string | null;
}

/** Una pieza a crear: sin {@code repoName} va en una carpeta del repo del proyecto; con él, en un repo propio. */
export interface PieceInput {
  name: string;
  templateKey: string | null;
  repoName: string | null;
  variables: Record<string, string>;
}

/** Piezas planeadas antes del repo: la fuente del stack para los documentos; el paso del repositorio arranca con ellas. */
export interface PiecePlan {
  layout: "MONOREPO" | "MULTI_REPO";
  pieces: PieceInput[];
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
  "agent-policies": AgentPolicy;
}

/**
 * Reglas de agentes del espacio (una sola entrada, DEFAULT): lo que reciben todos los agentes además del manual de su
 * rol, las perillas de costo y los textos que escriben en el tablero y en el repo. Los textos admiten {variables}.
 */
export interface AgentPolicy extends CatalogEntry {
  description: string | null;
  rules: {
    common: string[];
    board: string[];
    questions: string[];
    epicPlan: string;
    bigCard: string;
    codeReview: string;
    sendBack: string;
    web: string;
    document: string;
    taskSplit: string;
    proposalFields: string;
    /** Agente revisor de una puerta (y con o sin escalamiento). */
    gateReview?: string;
    gateEscalate?: string;
    gateDecide?: string;
  };
  limits: { questionRounds: number; commentsRead: number; filesRead: number; webSearches: number; turnsClaude: number; turnsOther: number };
  messages: {
    returnForReview: string;
    returnWithQuestions: string;
    toGate: string;
    toAgent: string;
    toPerson: string;
    questionsTitle: string;
    pendingTitle: string;
    readDocument: string;
    askFooter: string;
    failedStart: string;
    failedFinish: string;
  };
  repo: { agentsMd: string; workSteps: string; subagent: string; mcpInstructions: string; readme: string; pieceReadme: string; ci: string; gitignore: string };
  /** Modelo y esfuerzo sugeridos por rol (clave del rol); null = los del espacio y del proveedor. */
  roles: Record<string, RoleTuning> | null;
}

export interface RoleTuning {
  model: string | null;
  effort: string | null;
}

/** «Ver qué recibe»: el pedido que recibiría un agente para una tarjeta, sin llamar al modelo. */
export interface AgentPromptPreview {
  agent: string;
  kind: "REFINE" | "BOARD";
  system: string;
  user: string;
  tools: string[];
  core: string[];
  note: string;
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
  capabilities: Capability[];
  estimate: EstimateUnit;
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
  /** Quién revisa esta tarjeta en las puertas: null = lo del tablero. */
  reviewBy: "PERSON" | "AGENT" | null;
  createdAt: string;
  updatedAt: string;
  /** Datos propios del tipo de tarjeta (clave → valor como texto). */
  fields: Record<string, string>;
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
  /** Datos propios del tipo; sin enviar, la API conserva los que tiene. */
  fields?: Record<string, string>;
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
  /** null = el sugerido para su rol o, si no hay, el del espacio. */
  model: string | null;
  /** Cuánto razona (low…max); null = el sugerido para su rol o el del proveedor. */
  effort: string | null;
  /** Lo sugerido para su rol en Catálogo → Reglas de agentes. */
  suggestedModel: string | null;
  suggestedEffort: string | null;
  inTeam: boolean;
  /** false = el rol trabaja sobre el código del repo: solo trabaja en la PC de una persona. */
  serverAllowed: boolean;
}

/** Consumo de IA del mes (agentes que trabajan en el tablero). Costos en USD; nulos si falta el precio de algún modelo. */
export interface AiUsageReport {
  month: string;
  runs: number;
  inputTokens: number;
  outputTokens: number;
  /** De la entrada, los que salieron del caché de prompts (cuestan ~10%). */
  cachedTokens: number;
  costUsd: number | null;
  byAgent: {
    agentId: string;
    name: string;
    runs: number;
    inputTokens: number;
    outputTokens: number;
    cachedTokens: number;
    costUsd: number | null;
    /** Tope mensual propio del agente; null = sin tope. */
    budgetUsd: number | null;
  }[];
  budgetUsd: number | null;
  unpricedModels: string[];
  /** Ninguna tarjeta puede gastar más que esto (corta los bucles); null = sin tope. */
  cardCapUsd: number | null;
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

/** GITHUB_APP: con la app de la plataforma (sin token personal); GITHUB: con un token. */
export type RepositoryKind = "GITHUB_APP" | "GITHUB";

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
  /** Tokens de entrada que no salieron del caché. */
  inputTokens: number | null;
  outputTokens: number | null;
  /** Leídos del caché de prompts (cuestan ~10%) y guardados en él. */
  cacheReadTokens: number | null;
  cacheWriteTokens: number | null;
  summary: string | null;
  error: string | null;
  createdAt: string;
  finishedAt: string | null;
  /** Lo que costó en USD; null si fue local o su modelo no tiene precio. */
  costUsd: number | null;
}

export interface AgentsStatus {
  enabled: boolean;
  reason: string | null;
  model: string;
}

/** `status`: el código HTTP de la API cuando falló (p. ej. 409 si algo ya existe). */
/** Chat del tablero: una vuelta de la conversación (la web la guarda; el API no). */
export interface ChatTurn {
  role: "user" | "assistant";
  text: string;
  epics?: SuggestedEpic[];
}

export interface SuggestedEpic {
  title: string;
  description: string;
}

export interface ChatAnswer {
  text: string;
  epics: SuggestedEpic[];
}

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
/** Una máquina con runner: deja trabajando a los agentes locales de una persona. */
export interface RunnerMachine {
  id: string;
  name: string;
  os: string;
  tool: string;
  roles: string[];
  capacity: number;
  paused: boolean;
  online: boolean;
  version: string | null;
  lastSeen: string;
  memberId: string;
  person: string;
  /** Tarjetas que está trabajando ahora. */
  working: string[];
  /** Cuánto puede trabajar al día (minutos); null = sin tope. */
  dailyMinutes: number | null;
  minutesToday: number;
}

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
  /** Clave pública VAPID para suscribirse a las notificaciones push (null = no configuradas en el servidor). */
  pushPublicKey: string | null;
  /** Dispositivos de la persona que reciben notificaciones push. */
  pushDevices: number;
}

/** La suscripción push de este navegador, como la da PushSubscription.toJSON(). */
export interface PushSubscriptionInput {
  endpoint: string;
  keys: { p256dh: string; auth: string };
}

/** Algo que espera una decisión de quien mira: una puerta, un reparto o una tarjeta que un agente le dejó. */
export interface Decision {
  kind: "GATE" | "PLAN" | "HANDED";
  issueKey: string;
  projectKey: string;
  projectName: string;
  title: string;
  column: string;
  columnName: string;
  /** Lo último que se dijo (la pregunta del agente, su análisis o el comentario más reciente). */
  detail: string | null;
  /** Quién lo dijo. */
  from: string | null;
  since: string | null;
}

/** Un PR que menciona la tarjeta, con su CI resumido. */
export interface CardPull {
  repository: string;
  number: number;
  title: string;
  url: string;
  state: string;
  merged: boolean;
  ci: "GREEN" | "RED" | "PENDING" | "NONE" | "UNREADABLE";
  /** Por qué no se puede fusionar todavía (sin permiso para leer el CI, sin CI). */
  note: string | null;
}

export interface Merged {
  repository: string;
  number: number;
  commit: string;
  status: string;
}

// ---- Dashboard ----

/** Un número con el del período anterior; `null` = sin dato. */
export interface Kpi {
  value: number | null;
  previous: number | null;
}

export interface InsightsCompany {
  days: number;
  from: string;
  to: string;
  done: Kpi;
  leadTimeHours: Kpi;
  hoursSaved: Kpi;
  costPerCard: Kpi;
  savings: { hoursPerPoint: number; cards: number; points: number; estimatedHours: number; agentHours: number; unestimated: number };
  weeks: { start: string; withAgents: number; withoutAgents: number }[];
  flow: {
    projectKey: string;
    name: string;
    agentHours: number;
    personHours: number;
    waitingHours: number;
    stages: { column: string; name: string; gate: boolean; agentHours: number; personHours: number; waitingHours: number }[];
  }[];
  boards: { key: string; name: string; open: number; inProgress: number; waitingDecision: number; stuck: number; done: number; reworkRate: number }[];
  recent: {
    key: string;
    title: string;
    projectKey: string;
    projectName: string;
    doneAt: string;
    by: string | null;
    byAgent: boolean;
    leadTimeHours: number;
    agentsHelped: boolean;
  }[];
  agents: { id: string; name: string; role: string; delivered: number; failed: number; avgMinutes: number; costUsd: number | null }[];
  costUsd: number | null;
  unpricedModels: string[];
}

export interface InsightsMine {
  assigned: { key: string; title: string; projectKey: string; projectName: string; column: string; since: string }[];
  supervised: { key: string; title: string; projectKey: string; column: string; agent: string; working: boolean }[];
  deliveredToday: { key: string; title: string; projectKey: string; agent: string; at: string }[];
  epics: { key: string; title: string; projectKey: string; done: number; total: number; inProgress: number }[];
  week: { closed: number; leadTimeHours: number | null; reworked: number; agentDeliveries: number };
}

// ---- Rutinas ----

export type RoutineFrequency = "DAILY" | "WEEKLY" | "MONTHLY";

export interface Routine {
  id: string;
  projectKey: string;
  title: string;
  description: string | null;
  status: string | null;
  assigneeId: string | null;
  assigneeName: string | null;
  frequency: RoutineFrequency;
  /** «1,4» = lunes y jueves (1 = lunes … 7 = domingo). */
  weekdays: string | null;
  monthDay: number | null;
  at: string;
  timezone: string;
  /** En palabras: «Cada lunes a las 08:00». */
  schedule: string;
  enabled: boolean;
  skipIfOpen: boolean;
  nextRunAt: string | null;
  lastRunAt: string | null;
  lastIssueKey: string | null;
  webhook: boolean;
}

export interface RoutineInput {
  title: string;
  description: string | null;
  status: string | null;
  assigneeId: string | null;
  frequency: RoutineFrequency;
  weekdays: string | null;
  monthDay: number | null;
  at: string;
  timezone: string;
  enabled: boolean;
  skipIfOpen: boolean;
}

/** Una versión guardada de una entrada del catálogo. `savedBy` null = de fábrica o del sistema. */
export interface CatalogRevision {
  id: string;
  version: number;
  savedBy: string | null;
  savedAt: string;
}

// ---- Redactar con IA ----

export interface DraftPlan {
  projectKind: ProjectKind;
  methodology: string | null;
  /** `suggested`: documento base (se redacta al empezar); `written`: ya no es la plantilla. */
  documents: { kind: string; label: string; purpose: string | null; suggested: boolean; written: boolean }[];
  /** Documentos base sin redactar: sin ellos no se crea el repo de un proyecto nuevo. */
  pendingBase: string[];
  /** Hay clave de IA de la empresa: puede trabajar en el servidor. */
  serverReady: boolean;
  goal: string | null;
  /** Los repos ya vinculados: con ellos se reconoce un proyecto que ya existe. */
  repositories: string[];
  /** Cuántas piezas tiene el proyecto (cero en uno recién vinculado). */
  pieces: number;
}

export type DraftStep = "questions" | "draft" | "refine" | "recognize";

export interface DraftApproval {
  documents: { kind: string; content: string }[];
  goal: string | null;
  pieces?: DraftPiece[];
  cards?: { title: string; description: string }[];
  /** Proponer el kit de agentes a los repos por Pull Request. */
  kitPullRequest?: boolean;
}

export interface DraftApproved {
  saved: string[];
  goal: string;
  pieces?: number;
  cards?: string[];
  pullRequests?: string[];
  /** Por qué no hubo PR del kit (ya estaba al día, o GitHub no lo permitió). */
  kitNote?: string;
}

/** Una pieza que encontró el reconocimiento en el código (se puede corregir antes de aprobar). */
export interface DraftPiece {
  name: string;
  kind: string;
  repository: string;
  path: string;
  stack: string;
  build: string;
  test: string;
  run: string;
  notes: string;
}

export interface DraftInput {
  idea: string;
  answers?: Record<string, string>;
  kinds?: string[];
  kind?: string;
  content?: string;
  instruction?: string;
}

/** DONE con `result`; PENDING o RUNNING mientras la máquina trabaja; FAILED con `error`. */
export interface DraftJob {
  id: string | null;
  status: "DONE" | "PENDING" | "RUNNING" | "FAILED";
  result: {
    questions?: { id: string; text: string; options: string[] }[];
    documents?: { kind: string; label: string; content: string }[];
    goal?: string;
    content?: string;
    pieces?: DraftPiece[];
    findings?: string[];
    cards?: { title: string; description: string }[];
  } | null;
  error: string | null;
}

/** Un sistema de la empresa conectado para los agentes (correo, agenda, ERP…). La credencial nunca vuelve de la API. */
export interface BusinessConnection {
  key: string;
  name: string;
  url: string;
  header: string | null;
  hasSecret: boolean;
  tools: BusinessTool[];
  checkedAt: string | null;
  boards: { projectKey: string; roles: string[] }[];
}

/** `readOnly`: solo lee; `allowed`: los agentes la pueden usar (las que escriben, solo si se habilitaron). */
export interface BusinessTool {
  name: string;
  description: string;
  readOnly: boolean;
  allowed: boolean;
}
