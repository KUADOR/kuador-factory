import "server-only";

import { getSessionToken } from "./session";
import type {
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
  Issue,
  IssueInput,
  IssueStatus,
  AgentClient,
  AgentKit,
  Me,
  Member,
  MemberAccess,
  RunnerToken,
  MemberKind,
  Project,
  Session,
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

async function request<T>(path: string, init?: RequestInit & { anonymous?: boolean }): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json", Accept: "application/json" };
  if (!init?.anonymous) {
    const token = await getSessionToken();
    if (token) headers.Authorization = `Bearer ${token}`;
  }
  const res = await fetch(`${BASE_URL}${path}`, { ...init, headers, cache: "no-store" });
  if (!res.ok) {
    let message = `La API respondió ${res.status}.`;
    try {
      const problem = (await res.json()) as { detail?: string; title?: string };
      message = problem.detail ?? problem.title ?? message;
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
export async function fetchWithSession(path: string): Promise<Response> {
  const token = await getSessionToken();
  return fetch(`${BASE_URL}${path}`, { headers: token ? { Authorization: `Bearer ${token}` } : {}, cache: "no-store" });
}

export const boardApi = {
  login: (email: string, password: string) =>
    request<Session>("/api/auth/login", { ...json("POST", { email, password }), anonymous: true }),
  logout: () => request<void>("/api/auth/logout", { method: "POST" }),
  me: () => request<Me>("/api/auth/me"),
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
  createProject: (input: { key: string; name: string; description: string | null; templateKey: string }) =>
    request<Project>("/api/projects", json("POST", input)),
  setMethodology: (key: string, methodology: string | null) =>
    request<Project>(`/api/projects/${encodeURIComponent(key)}/methodology`, json("PUT", { methodology })),

  workspace: () => request<Workspace>("/api/workspace"),
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
  }) => request<{ slug: string; name: string }>("/api/signup/workspace", { ...json("POST", input), anonymous: true }),

  updateProject: (key: string, input: { name: string; description: string | null }) =>
    request<Project>(`/api/projects/${encodeURIComponent(key)}`, json("PUT", input)),
  setStages: (key: string, stages: StageDef[], maxRework: number) =>
    request<Project>(`/api/projects/${encodeURIComponent(key)}/stages`, json("PUT", { stages, maxRework })),
  takeIssue: (key: string) => request<Issue>(`/api/issues/${encodeURIComponent(key)}/take`, { method: "POST" }),
  deliverIssue: (key: string, comment: string | null) =>
    request<Issue>(`/api/issues/${encodeURIComponent(key)}/deliver`, json("POST", { comment })),
  reviewIssue: (key: string, approve: boolean, comment: string | null) =>
    request<Issue>(`/api/issues/${encodeURIComponent(key)}/review`, json("POST", { approve, comment })),
  setTransitions: (key: string, transitions: Transitions) =>
    request<Project>(`/api/projects/${encodeURIComponent(key)}/transitions`, json("PUT", transitions)),

  catalog: <T extends CatalogType>(type: T) => request<CatalogEntries[T][]>(`/api/catalog/${type}`),
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
  linkRepository: (key: string, input: { kind: RepositoryKind; location: string; branch: string | null; token: string | null }) =>
    request<RepositoryLink>(`/api/projects/${encodeURIComponent(key)}/repository`, json("PUT", input)),
  unlinkRepository: (key: string) =>
    request<void>(`/api/projects/${encodeURIComponent(key)}/repository`, { method: "DELETE" }),
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
};
