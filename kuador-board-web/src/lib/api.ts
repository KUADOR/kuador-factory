import "server-only";

import { headers as incomingHeaders } from "next/headers";

import { getSessionToken } from "./session";
import type {
  PieceUpdate,
  AgentPromptPreview,
  Account,
  Activity,
  AgentRun,
  AgentsStatus,
  Exported,
  RepositoryKind,
  RepositoryLink,
  SyncComparison,
  SyncEntry,
  SyncStatus,
  BoardColumn,
  DocsOverview,
  DocumentKind,
  DocumentMeta,
  DocumentVersionMeta,
  DocumentWithContent,
  CatalogEntries,
  CatalogType,
  StageDef,
  Transitions,
  Workspace,
  AgentToken,
  Comment,
  IssuedToken,
  GitHubConnection,
  GitHubOverview,
  Issue,
  PieceInput,
  PiecesSetup,
  ProjectPiece,
  SetupResult,
  IssueInput,
  IssueStatus,
  AgentClient,
  AgentKit,
  EpicTask,
  Language,
  NotificationSettings,
  NotificationView,
  OAuthClientInfo,
  RepoKit,
  Me,
  Member,
  MemberAccess,
  RunnerToken,
  MemberKind,
  Project,
  Session,
  Plan,
  PlanInput,
  WorkspaceLicense,
  WorkspacePlan,
  LicenseStatus,
  Invitation,
  InvitationPreview,
  TelegramGroup,
  TelegramSetup,
  Payment,
  PaymentMode,
  PaymentOption,
  PaymentProviderView,
  PaymentType,
  AttachmentListing,
  AgentMode,
  BoardPerson,
  TeamAgent,
  WorkspaceAiStatus,
  CatalogPackage,
  PlatformCatalogEntry,
  PlatformCatalogSummary,
  AiModelPrice,
  AuditPage,
  AiProvider,
  AiUsageReport,
} from "./types";

const BASE_URL = process.env.BOARD_API_URL ?? "http://127.0.0.1:8091";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

/**
 * La IP y el navegador de la persona, para la auditoría: el API ve llegar las peticiones desde este servidor. La IP es
 * la última de X-Forwarded-For (la que agregó nuestro proxy), no lo que diga el navegador.
 */
async function clientHeaders(): Promise<Record<string, string>> {
  try {
    const h = await incomingHeaders();
    const hops = (h.get("x-forwarded-for") ?? "").split(",").map((s) => s.trim()).filter(Boolean);
    const ip = hops.at(-1) ?? h.get("x-real-ip") ?? "";
    const agent = h.get("user-agent") ?? "";
    // El secreto compartido le dice al API que la petición viene de la web: solo entonces confía en la IP reenviada.
    const secret = process.env.BOARD_WEB_SECRET;
    return {
      ...(secret ? { "X-Kuador-Web-Secret": secret } : {}),
      ...(ip ? { "X-Kuador-Client-IP": ip } : {}),
      ...(agent ? { "X-Kuador-Client-Agent": agent.slice(0, 300) } : {}),
    };
  } catch {
    // Fuera de una petición (p. ej. al compilar): sin IP, pero con el secreto.
    return process.env.BOARD_WEB_SECRET ? { "X-Kuador-Web-Secret": process.env.BOARD_WEB_SECRET } : {};
  }
}

async function request<T>(path: string, init?: RequestInit & { anonymous?: boolean }): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json", Accept: "application/json", ...(await clientHeaders()) };
  if (!init?.anonymous) {
    const token = await getSessionToken();
    if (token) headers.Authorization = `Bearer ${token}`;
  }
  const res = await fetch(`${BASE_URL}${path}`, { ...init, headers, cache: "no-store" });
  if (!res.ok) {
    let message = `La API respondió ${res.status}.`;
    try {
      const problem = (await res.json()) as { detail?: string; title?: string; error_description?: string };
      message = problem.detail ?? problem.error_description ?? problem.title ?? message;
    } catch {
      // Cuerpo vacío o no JSON: se queda el mensaje genérico.
    }
    throw new ApiError(res.status, message);
  }
  return (res.status === 204 ? undefined : await res.json()) as T;
}

const json = (method: string, body: unknown): RequestInit => ({ method, body: JSON.stringify(body) });

export const kitQuery = (client: string, mcp: string[]) => new URLSearchParams([["client", client], ...mcp.map((k) => ["mcp", k])]).toString();

/**
 * Descarga binaria con la sesión (el .zip del kit). Devuelve la respuesta de la API tal cual para
 * reenviarla al navegador desde un route handler.
 */
export async function fetchWithSession(path: string, signal?: AbortSignal, init?: RequestInit & { duplex?: "half" }): Promise<Response> {
  const token = await getSessionToken();
  const headers = new Headers(init?.headers);
  for (const [k, v] of Object.entries(await clientHeaders())) headers.set(k, v);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return fetch(`${BASE_URL}${path}`, { ...init, headers, cache: "no-store", signal });
}

export const boardApi = {
  login: (email: string, password: string) =>
    request<Session>("/api/auth/login", { ...json("POST", { email, password }), anonymous: true }),
  logout: () => request<void>("/api/auth/logout", { method: "POST" }),
  me: () => request<Me>("/api/auth/me"),
  requestPasswordReset: (email: string) => request<void>("/api/auth/password-reset", { ...json("POST", { email }), anonymous: true }),
  confirmPasswordReset: (input: { token: string | null; email: string | null; code: string | null; password: string }) =>
    request<void>("/api/auth/password-reset/confirm", { ...json("POST", input), anonymous: true }),
  plans: () => request<Plan[]>("/api/public/plans", { anonymous: true }),
  attachments: (key: string) => request<AttachmentListing>(`/api/issues/${encodeURIComponent(key)}/attachments`),
  deleteAttachment: (id: string) => request<void>(`/api/attachments/${encodeURIComponent(id)}`, { method: "DELETE" }),
  platformCatalogSummary: () => request<PlatformCatalogSummary>("/api/platform/catalog"),
  platformCatalog: <T extends CatalogType>(type: T) => request<PlatformCatalogEntry<CatalogEntries[T]>[]>(`/api/platform/catalog/${type}`),
  savePlatformCatalogEntry: (type: CatalogType, key: string, entry: unknown, extra: { memberName?: string | null; color?: string | null }) =>
    request<PlatformCatalogEntry>(`/api/platform/catalog/${type}/${encodeURIComponent(key)}`, json("PUT", { entry, ...extra })),
  discardPlatformCatalogEntry: (type: CatalogType, key: string) =>
    request<void>(`/api/platform/catalog/${type}/${encodeURIComponent(key)}/discard`, { method: "POST" }),
  removePlatformCatalogEntry: (type: CatalogType, key: string) =>
    request<void>(`/api/platform/catalog/${type}/${encodeURIComponent(key)}`, { method: "DELETE" }),
  publishPlatformCatalog: () => request<PlatformCatalogSummary>("/api/platform/catalog/publish", { method: "POST" }),
  platformPackages: () => request<CatalogPackage[]>("/api/platform/packages"),
  savePlatformPackage: (p: CatalogPackage) => request<CatalogPackage>(`/api/platform/packages/${encodeURIComponent(p.code)}`, json("PUT", p)),
  packages: () => request<CatalogPackage[]>("/api/public/packages", { anonymous: true }),
  workspacePackage: () => request<CatalogPackage | undefined>("/api/workspace/package"),
  boardTeam: (key: string) => request<TeamAgent[]>(`/api/projects/${encodeURIComponent(key)}/team`),
  boardPeople: (key: string) => request<BoardPerson[]>(`/api/projects/${encodeURIComponent(key)}/people`),
  addBoardPerson: (key: string, memberId: string) =>
    request<BoardPerson[]>(`/api/projects/${encodeURIComponent(key)}/people`, json("POST", { memberId })),
  removeBoardPerson: (key: string, memberId: string) =>
    request<BoardPerson[]>(`/api/projects/${encodeURIComponent(key)}/people/${encodeURIComponent(memberId)}`, { method: "DELETE" }),
  addToTeam: (key: string, memberId: string) => request<TeamAgent[]>(`/api/projects/${encodeURIComponent(key)}/team`, json("POST", { memberId })),
  createTeamAgent: (key: string, input: { name: string; role: string; description: string | null; mode: AgentMode; model: string | null }) =>
    request<TeamAgent[]>(`/api/projects/${encodeURIComponent(key)}/team/agents`, json("POST", input)),
  removeFromTeam: (key: string, memberId: string) =>
    request<TeamAgent[]>(`/api/projects/${encodeURIComponent(key)}/team/${encodeURIComponent(memberId)}`, { method: "DELETE" }),
  setAgentProfile: (memberId: string, input: { mode: AgentMode; model: string | null }) =>
    request<TeamAgent>(`/api/agents/${encodeURIComponent(memberId)}/profile`, json("PUT", input)),
  aiUsage: () => request<AiUsageReport>("/api/workspace/ai/usage"),
  setAiBudget: (budgetUsd: number | null) => request<AiUsageReport>("/api/workspace/ai/budget", json("PUT", { budgetUsd })),
  aiPrices: () => request<AiModelPrice[]>("/api/platform/ai-prices"),
  saveAiPrice: (model: string, inputPerMTok: number, outputPerMTok: number) =>
    request<AiModelPrice[]>(`/api/platform/ai-prices/${encodeURIComponent(model)}`, json("PUT", { inputPerMTok, outputPerMTok })),
  deleteAiPrice: (model: string) => request<AiModelPrice[]>(`/api/platform/ai-prices/${encodeURIComponent(model)}`, { method: "DELETE" }),
  workspaceAudit: (query: string) => request<AuditPage>(`/api/workspace/audit?${query}`),
  platformAudit: (query: string) => request<AuditPage>(`/api/platform/audit?${query}`),
  workspaceAi: () => request<WorkspaceAiStatus>("/api/workspace/ai"),
  aiProviders: () => request<AiProvider[]>("/api/platform/ai-providers"),
  saveAiProvider: (code: string, input: Omit<AiProvider, "code">) =>
    request<AiProvider[]>(`/api/platform/ai-providers/${encodeURIComponent(code)}`, json("PUT", input)),
  saveWorkspaceAi: (input: { provider: string; apiKey: string | null; defaultModel: string }) => request<WorkspaceAiStatus>("/api/workspace/ai", json("PUT", input)),
  removeWorkspaceAi: () => request<WorkspaceAiStatus>("/api/workspace/ai", { method: "DELETE" }),
  billingSettings: () => request<{ reminderDays: number[] }>("/api/platform/billing-settings"),
  saveBillingSettings: (reminderDays: number[]) => request<{ reminderDays: number[] }>("/api/platform/billing-settings", json("PUT", { reminderDays })),
  paymentTypes: () => request<PaymentType[]>("/api/platform/payment-types"),
  paymentProviders: () => request<PaymentProviderView[]>("/api/platform/payment-providers"),
  savePaymentProvider: (
    code: string,
    input: { type: string; name: string; mode: PaymentMode; credentials: Record<string, string>; active: boolean; sortOrder: number },
  ) => request<PaymentProviderView>(`/api/platform/payment-providers/${encodeURIComponent(code)}`, json("PUT", input)),
  paymentOptions: () => request<PaymentOption[]>("/api/workspace/plan/payment-options"),
  payments: () => request<Payment[]>("/api/workspace/plan/payments"),
  checkout: (input: { plan: string; provider: string; months: number }) =>
    request<{ paymentId: string; payUrl: string }>("/api/workspace/plan/checkout", json("POST", input)),
  confirmPayment: (id: string) => request<Payment>(`/api/workspace/plan/payments/${encodeURIComponent(id)}/confirm`, { method: "POST" }),
  telegramSetup: () => request<TelegramSetup>("/api/workspace/telegram"),
  connectTelegramBot: (token: string) => request<TelegramSetup>("/api/workspace/telegram", json("PUT", { token })),
  disconnectTelegramBot: () => request<TelegramSetup>("/api/workspace/telegram", { method: "DELETE" }),
  telegramGroupCode: () => request<{ command: string; bot: string; url: string }>("/api/workspace/telegram/group-code", { method: "POST" }),
  updateTelegramGroup: (id: string, input: { onAssigned: boolean; onGates: boolean; onPlans: boolean }) =>
    request<TelegramGroup>(`/api/workspace/telegram/groups/${encodeURIComponent(id)}`, json("PUT", input)),
  removeTelegramGroup: (id: string) => request<void>(`/api/workspace/telegram/groups/${encodeURIComponent(id)}`, { method: "DELETE" }),
  invitations: () => request<Invitation[]>("/api/invitations"),
  invite: (input: { memberId?: string; name?: string; role?: string; access?: MemberAccess; email: string; projects?: string[] }) =>
    request<Invitation>("/api/invitations", json("POST", input)),
  resendInvitation: (id: string) => request<Invitation>(`/api/invitations/${encodeURIComponent(id)}/resend`, { method: "POST" }),
  cancelInvitation: (id: string) => request<void>(`/api/invitations/${encodeURIComponent(id)}`, { method: "DELETE" }),
  invitationPreview: (token: string) => request<InvitationPreview>(`/api/public/invitations?token=${encodeURIComponent(token)}`, { anonymous: true }),
  acceptInvitation: (token: string, password: string) =>
    request<{ email: string }>("/api/public/invitations/accept", { ...json("POST", { token, password }), anonymous: true }),
  /** `undefined`: el espacio no tiene planes (instalación propia, sin límites). */
  workspacePlan: () => request<WorkspacePlan | undefined>("/api/workspace/plan"),
  changePlan: (plan: string) => request<WorkspacePlan>("/api/workspace/plan", json("PUT", { plan })),
  platformPlans: () => request<Plan[]>("/api/platform/plans"),
  savePlan: (code: string, input: PlanInput) => request<Plan>(`/api/platform/plans/${encodeURIComponent(code)}`, json("PUT", input)),
  platformWorkspaces: () => request<WorkspaceLicense[]>("/api/platform/workspaces"),
  setLicense: (workspaceId: string, input: { plan: string; status: LicenseStatus; trialEndsAt: string | null; paidUntil: string | null }) =>
    request<WorkspaceLicense>(`/api/platform/workspaces/${encodeURIComponent(workspaceId)}/license`, json("PUT", input)),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<void>("/api/auth/password", json("PUT", { currentPassword, newPassword })),
  accounts: () => request<Account[]>("/api/auth/accounts"),
  grantAccess: (memberId: string, email: string, password: string) =>
    request<Account>(`/api/members/${encodeURIComponent(memberId)}/credentials`, json("PUT", { email, password })),
  agentTokens: () => request<AgentToken[]>("/api/auth/tokens"),
  runnerTokens: () => request<RunnerToken[]>("/api/auth/runner-tokens"),
  createRunnerToken: (name: string) => request<IssuedToken>("/api/auth/runner-tokens", json("POST", { name })),
  revokeRunnerToken: (tokenId: string) => request<void>(`/api/auth/runner-tokens/${encodeURIComponent(tokenId)}`, { method: "DELETE" }),
  agentKit: (client: AgentClient, mcp: string[]) => request<AgentKit>(`/api/agent-kit?${kitQuery(client, mcp)}`),
  supervise: (key: string) => request<Issue>(`/api/issues/${encodeURIComponent(key)}/supervise`, { method: "POST" }),
  createAgentToken: (memberId: string, name: string) =>
    request<IssuedToken>(`/api/members/${encodeURIComponent(memberId)}/tokens`, json("POST", { name })),
  revokeAgentToken: (tokenId: string) =>
    request<void>(`/api/auth/tokens/${encodeURIComponent(tokenId)}`, { method: "DELETE" }),

  projects: () => request<Project[]>("/api/projects"),
  project: (key: string) => request<Project>(`/api/projects/${encodeURIComponent(key)}`),
  deleteProject: (key: string, confirm: string) =>
    request<void>(`/api/projects/${encodeURIComponent(key)}?confirm=${encodeURIComponent(confirm)}`, { method: "DELETE" }),
  createProject: (input: { key: string; name: string; description: string | null; templateKey: string }) =>
    request<Project>("/api/projects", json("POST", input)),
  setMethodology: (key: string, methodology: string | null) =>
    request<Project>(`/api/projects/${encodeURIComponent(key)}/methodology`, json("PUT", { methodology })),

  workspace: () => request<Workspace>("/api/workspace"),
  notifications: () => request<NotificationView>("/api/notifications"),
  updateNotifications: (s: Omit<NotificationSettings, "telegramChatId">) => request<NotificationView>("/api/notifications", json("PUT", s)),
  telegramLink: () => request<{ url: string }>("/api/notifications/telegram/link", { method: "POST" }),
  unlinkTelegram: () => request<NotificationView>("/api/notifications/telegram", { method: "DELETE" }),
  testNotifications: () => request<{ channels: string[] }>("/api/notifications/test", { method: "POST" }),
  updateLanguages: (team: Language, code: Language) => request<Workspace>("/api/workspace/languages", json("PUT", { team, code })),
  oauthClient: (clientId: string, redirectUri: string | null) =>
    request<OAuthClientInfo>(`/api/oauth/client?${new URLSearchParams([["client_id", clientId], ...(redirectUri ? [["redirect_uri", redirectUri]] : [])])}`),
  oauthAuthorize: (input: { clientId: string; redirectUri: string | null; codeChallenge: string | null; codeChallengeMethod: string | null; state: string | null; approved: boolean }) =>
    request<{ redirect: string }>("/api/oauth/authorize", json("POST", input)),
  updateWorkspace: (input: { name: string; description: string | null; brandColor: string; brandOnColor: string | null; logo: string | null }) =>
    request<Workspace>("/api/workspace", json("PUT", input)),

  // Alta pública de espacios (sin sesión).
  signupCode: (email: string) => request<void>("/api/signup/code", { ...json("POST", { email }), anonymous: true }),
  signupVerify: (email: string, code: string) =>
    request<{ signupToken: string }>("/api/signup/verify", { ...json("POST", { email, code }), anonymous: true }),
  signupSlug: (name: string) => request<{ slug: string }>(`/api/signup/slug?name=${encodeURIComponent(name)}`, { anonymous: true }),
  signupComplete: (input: {
    signupToken: string;
    companyName: string;
    slug: string | null;
    description: string | null;
    brandColor: string;
    brandOnColor: string | null;
    logo: string | null;
    ownerName: string;
    password: string;
    plan: string | null;
    packageCode?: string | null;
  }) => request<{ slug: string; name: string }>("/api/signup/workspace", { ...json("POST", input), anonymous: true }),

  updateProject: (key: string, input: { name: string; description: string | null }) =>
    request<Project>(`/api/projects/${encodeURIComponent(key)}`, json("PUT", input)),
  setStages: (key: string, stages: StageDef[], maxRework: number) =>
    request<Project>(`/api/projects/${encodeURIComponent(key)}/stages`, json("PUT", { stages, maxRework })),
  takeIssue: (key: string) => request<Issue>(`/api/issues/${encodeURIComponent(key)}/take`, { method: "POST" }),
  deliverIssue: (key: string, comment: string | null) =>
    request<Issue>(`/api/issues/${encodeURIComponent(key)}/deliver`, json("POST", { comment })),
  epicTasks: (key: string) => request<EpicTask[]>(`/api/issues/${encodeURIComponent(key)}/tasks`),
  reviewPlan: (key: string, approve: boolean, comment: string | null) =>
    request<Issue>(`/api/issues/${encodeURIComponent(key)}/plan`, json("POST", { approve, comment })),
  reviewIssue: (key: string, approve: boolean, comment: string | null) =>
    request<Issue>(`/api/issues/${encodeURIComponent(key)}/review`, json("POST", { approve, comment })),
  setTransitions: (key: string, transitions: Transitions) =>
    request<Project>(`/api/projects/${encodeURIComponent(key)}/transitions`, json("PUT", transitions)),

  catalog: <T extends CatalogType>(type: T) => request<CatalogEntries[T][]>(`/api/catalog/${type}`),
  agentRulesCore: () => request<string[]>("/api/agent-rules/core"),
  agentPromptPreview: (memberId: string, issue: string) =>
    request<AgentPromptPreview>(`/api/agents/${encodeURIComponent(memberId)}/prompt-preview?issue=${encodeURIComponent(issue)}`),
  catalogEntry: <T extends CatalogType>(type: T, key: string) =>
    request<CatalogEntries[T]>(`/api/catalog/${type}/${encodeURIComponent(key)}`),
  /** Crea (version 0, clave nueva) o actualiza con la versión que se editó. */
  saveCatalogEntry: <T extends CatalogType>(type: T, entry: CatalogEntries[T]) =>
    request<CatalogEntries[T]>(`/api/catalog/${type}/${encodeURIComponent(entry.key)}`, json("PUT", entry)),
  deleteCatalogEntry: (type: CatalogType, key: string) =>
    request<void>(`/api/catalog/${type}/${encodeURIComponent(key)}`, { method: "DELETE" }),
  restoreCatalogEntry: <T extends CatalogType>(type: T, key: string) =>
    request<CatalogEntries[T]>(`/api/catalog/${type}/${encodeURIComponent(key)}/restore`, { method: "POST" }),

  docsOverview: (projectKey: string) => request<DocsOverview>(`/api/projects/${encodeURIComponent(projectKey)}/docs`),
  issueDocs: (issueKey: string) => request<DocumentMeta[]>(`/api/issues/${encodeURIComponent(issueKey)}/docs`),
  createDoc: (projectKey: string, kind: DocumentKind, issueKey: string | null) =>
    request<DocumentWithContent>(`/api/projects/${encodeURIComponent(projectKey)}/docs`, json("POST", { kind, issueKey })),
  doc: (id: string) => request<DocumentWithContent>(`/api/docs/${encodeURIComponent(id)}`),
  saveDoc: (id: string, baseVersion: number, content: string, note: string | null) =>
    request<DocumentWithContent>(`/api/docs/${encodeURIComponent(id)}`, json("PUT", { baseVersion, content, note })),
  docVersions: (id: string) => request<DocumentVersionMeta[]>(`/api/docs/${encodeURIComponent(id)}/versions`),
  docVersion: (id: string, version: number) =>
    request<{ version: DocumentVersionMeta; content: string }>(`/api/docs/${encodeURIComponent(id)}/versions/${version}`),
  restoreDoc: (id: string, version: number, baseVersion: number) =>
    request<DocumentWithContent>(`/api/docs/${encodeURIComponent(id)}/versions/${version}/restore`, json("POST", { baseVersion })),

  repository: (key: string) => request<RepositoryLink>(`/api/projects/${encodeURIComponent(key)}/repository`),
  github: () => request<GitHubOverview>("/api/github"),
  githubManifest: (appName: string, organization: string | null) =>
    request<{ postUrl: string; manifest: string }>("/api/github/app/manifest", json("POST", { appName, organization })),
  githubCompleteApp: (code: string, state: string) => request<GitHubOverview["app"]>("/api/github/app", json("POST", { code, state })),
  githubStartInstall: () => request<{ url: string }>("/api/github/installations/start", { method: "POST" }),
  githubCompleteInstall: (installationId: number, code: string, state: string) =>
    request<GitHubConnection>("/api/github/installations", json("POST", { installationId, code, state })),
  githubDisconnect: (installationId: number) => request<void>(`/api/github/installations/${installationId}`, { method: "DELETE" }),
  pieces: (key: string) => request<ProjectPiece[]>(`/api/projects/${encodeURIComponent(key)}/components`),
  updatePiece: (key: string, name: string, input: PieceUpdate) =>
    request<ProjectPiece>(`/api/projects/${encodeURIComponent(key)}/components/${encodeURIComponent(name)}`, json("PUT", input)),
  setupPieces: (key: string, input: PiecesSetup) => request<SetupResult>(`/api/projects/${encodeURIComponent(key)}/components/setup`, json("POST", input)),
  addPiece: (key: string, piece: PieceInput, privateRepo: boolean) =>
    request<SetupResult>(`/api/projects/${encodeURIComponent(key)}/components`, json("POST", { piece, privateRepo })),
  linkRepository: (
    key: string,
    input: { kind: RepositoryKind; location: string; branch: string | null; token: string | null; installationId?: number | null },
  ) =>
    request<RepositoryLink>(`/api/projects/${encodeURIComponent(key)}/repository`, json("PUT", input)),
  unlinkRepository: (key: string) =>
    request<void>(`/api/projects/${encodeURIComponent(key)}/repository`, { method: "DELETE" }),
  agentKitStatus: (key: string) => request<RepoKit[]>(`/api/projects/${encodeURIComponent(key)}/sync/agent-kit`),
  proposeAgentKit: (key: string) => request<RepoKit[]>(`/api/projects/${encodeURIComponent(key)}/sync/agent-kit`, { method: "POST" }),
  syncStatus: (key: string) => request<SyncStatus>(`/api/projects/${encodeURIComponent(key)}/sync`),
  syncCompare: (key: string, docId: string) =>
    request<SyncComparison>(`/api/projects/${encodeURIComponent(key)}/sync/docs/${encodeURIComponent(docId)}/compare`),
  syncExport: (key: string, docId: string, force: boolean) =>
    request<Exported>(`/api/projects/${encodeURIComponent(key)}/sync/docs/${encodeURIComponent(docId)}/export`, json("POST", { force })),
  syncExportMany: (key: string, documentIds: string[]) =>
    request<Exported>(`/api/projects/${encodeURIComponent(key)}/sync/export`, json("POST", { documentIds, forced: [] })),
  syncImport: (key: string, docId: string, force: boolean) =>
    request<SyncEntry>(`/api/projects/${encodeURIComponent(key)}/sync/docs/${encodeURIComponent(docId)}/import`, json("POST", { force })),
  syncImportNew: (key: string, group: string, issueKey: string | null, newIssueTitle: string | null) =>
    request<SyncEntry[]>(`/api/projects/${encodeURIComponent(key)}/sync/import-new`, json("POST", { group, issueKey, newIssueTitle })),
  syncSafe: (key: string) =>
    request<{ imported: number; conflicts: number; exported: Exported | null }>(`/api/projects/${encodeURIComponent(key)}/sync/safe`, {
      method: "POST",
    }),

  updateColumns: (key: string, columns: (Omit<BoardColumn, "key"> & { key: string | null })[]) =>
    request<Project>(`/api/projects/${encodeURIComponent(key)}/columns`, json("PUT", { columns })),

  members: () => request<Member[]>("/api/members"),
  agentsStatus: () => request<AgentsStatus>("/api/agents/status"),
  agentRuns: (filter: { agentId?: string; issueKey?: string; limit?: number }) => {
    const q = new URLSearchParams();
    if (filter.agentId) q.set("agentId", filter.agentId);
    if (filter.issueKey) q.set("issueKey", filter.issueKey);
    q.set("limit", String(filter.limit ?? 20));
    return request<AgentRun[]>(`/api/agents/runs?${q}`);
  },
  createMember: (input: {
    name: string;
    kind: MemberKind;
    role: string;
    description: string | null;
    avatarColor: string;
    access?: MemberAccess;
  }) => request<Member>("/api/members", json("POST", input)),
  setMemberAccess: (memberId: string, access: MemberAccess) =>
    request<{ id: string; access: MemberAccess }>(`/api/members/${encodeURIComponent(memberId)}/access`, json("PUT", { access })),

  issues: (projectKey: string) => request<Issue[]>(`/api/projects/${encodeURIComponent(projectKey)}/issues`),
  createIssue: (projectKey: string, input: IssueInput & { status: IssueStatus }) =>
    request<Issue>(`/api/projects/${encodeURIComponent(projectKey)}/issues`, json("POST", input)),
  assignIssue: (key: string, assigneeId: string | null) => request<Issue>(`/api/issues/${encodeURIComponent(key)}/assignee`, json("PUT", { assigneeId })),
  updateIssue: (key: string, input: IssueInput) =>
    request<Issue>(`/api/issues/${encodeURIComponent(key)}`, json("PUT", input)),
  moveIssue: (key: string, status: IssueStatus, index: number) =>
    request<Issue>(`/api/issues/${encodeURIComponent(key)}/move`, json("POST", { status, index })),
  deleteIssue: (key: string) => request<void>(`/api/issues/${encodeURIComponent(key)}`, { method: "DELETE" }),

  issueActivity: (key: string) => request<Activity[]>(`/api/issues/${encodeURIComponent(key)}/activity`),
  projectActivity: (projectKey: string, limit = 100) =>
    request<Activity[]>(`/api/projects/${encodeURIComponent(projectKey)}/activity?limit=${limit}`),

  comments: (key: string) => request<Comment[]>(`/api/issues/${encodeURIComponent(key)}/comments`),
  addComment: (key: string, body: string) =>
    request<Comment>(`/api/issues/${encodeURIComponent(key)}/comments`, json("POST", { body })),
  editComment: (key: string, id: string, body: string) =>
    request<Comment>(`/api/issues/${encodeURIComponent(key)}/comments/${encodeURIComponent(id)}`, json("PUT", { body })),
  deleteComment: (key: string, id: string) =>
    request<void>(`/api/issues/${encodeURIComponent(key)}/comments/${encodeURIComponent(id)}`, { method: "DELETE" }),
  retryAgentRun: (id: string) => request<void>(`/api/agents/runs/${encodeURIComponent(id)}/retry`, { method: "POST" }),
};
