"use server";

import { revalidatePath } from "next/cache";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";

import { ApiError, boardApi } from "@/lib/api";
import { clearSessionToken, safeNext, setSessionToken } from "@/lib/session";
import { THEME_COOKIE, parseTheme, type ThemeMode } from "@/theme/theme";
import type {
  Account,
  Activity,
  AgentRun,
  Exported,
  RepositoryKind,
  RepositoryLink,
  SyncComparison,
  SyncEntry,
  BoardColumn,
  DocumentKind,
  DocumentMeta,
  DocumentVersionMeta,
  DocumentWithContent,
  CatalogEntries,
  CatalogType,
  Methodology,
  StageDef,
  Transitions,
  Workspace,
  ActionResult,
  Comment,
  IssuedToken,
  Issue,
  IssueInput,
  IssueStatus,
  AgentClient,
  AgentKit,
  Member,
  MemberAccess,
  MemberKind,
  Project,
} from "@/lib/types";

async function run<T>(work: () => Promise<T>, pathToRefresh?: string): Promise<ActionResult<T>> {
  let expired = false;
  try {
    const data = await work();
    if (pathToRefresh) revalidatePath(pathToRefresh);
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      expired = true;
    } else if (error instanceof ApiError) {
      return { ok: false, error: error.message };
    } else {
      console.error(error);
      return { ok: false, error: "No se pudo conectar con la API del tablero." };
    }
  }
  // redirect() lanza una excepción interna de Next: va fuera del try para que no se atrape.
  if (expired) {
    await clearSessionToken();
    redirect("/login");
  }
  return { ok: false, error: "Sesión vencida." };
}

const boardPath = (projectKey: string) => `/projects/${projectKey}`;

// ---- Sesión ----

export async function loginAction(email: string, password: string, next: string): Promise<ActionResult<void>> {
  try {
    const session = await boardApi.login(email, password);
    await setSessionToken(session.token, session.expiresAt);
  } catch (error) {
    if (error instanceof ApiError) return { ok: false, error: error.message };
    console.error(error);
    return { ok: false, error: "No se pudo conectar con la API del tablero." };
  }
  redirect(safeNext(next));
}

export async function logoutAction(): Promise<void> {
  try {
    await boardApi.logout();
  } catch {
    // Aunque la API falle, la sesión local se cierra igual.
  }
  await clearSessionToken();
  redirect("/login");
}

export async function changePasswordAction(currentPassword: string, newPassword: string): Promise<ActionResult<void>> {
  return run(() => boardApi.changePassword(currentPassword, newPassword));
}

// ---- Accesos y tokens ----

export async function grantAccessAction(memberId: string, email: string, password: string): Promise<ActionResult<Account>> {
  return run(() => boardApi.grantAccess(memberId, email, password), "/team");
}

export async function createAgentTokenAction(memberId: string, name: string): Promise<ActionResult<IssuedToken>> {
  return run(() => boardApi.createAgentToken(memberId, name), "/team");
}

export async function createRunnerTokenAction(name: string): Promise<ActionResult<IssuedToken>> {
  return run(() => boardApi.createRunnerToken(name), "/agentes-locales");
}

export async function revokeRunnerTokenAction(tokenId: string): Promise<ActionResult<void>> {
  return run(() => boardApi.revokeRunnerToken(tokenId), "/agentes-locales");
}

export async function agentKitAction(client: AgentClient, mcp: string[]): Promise<ActionResult<AgentKit>> {
  return run(() => boardApi.agentKit(client, mcp));
}

export async function superviseIssueAction(projectKey: string, key: string): Promise<ActionResult<Issue>> {
  return run(() => boardApi.supervise(key), `/projects/${projectKey}`);
}

export async function revokeAgentTokenAction(tokenId: string): Promise<ActionResult<void>> {
  return run(() => boardApi.revokeAgentToken(tokenId), "/team");
}

// ---- Tablero ----

export async function createIssueAction(
  projectKey: string,
  input: IssueInput & { status: IssueStatus },
): Promise<ActionResult<Issue>> {
  return run(() => boardApi.createIssue(projectKey, input), boardPath(projectKey));
}

export async function updateIssueAction(
  issue: Pick<Issue, "key" | "projectKey" | "status">,
  input: IssueInput,
  status: IssueStatus,
): Promise<ActionResult<Issue>> {
  return run(async () => {
    const updated = await boardApi.updateIssue(issue.key, input);
    // Cambiar de columna desde el formulario lo deja al final de la columna nueva.
    return status === issue.status ? updated : boardApi.moveIssue(issue.key, status, Number.MAX_SAFE_INTEGER);
  }, boardPath(issue.projectKey));
}

export async function moveIssueAction(
  projectKey: string,
  key: string,
  status: IssueStatus,
  index: number,
): Promise<ActionResult<Issue>> {
  return run(() => boardApi.moveIssue(key, status, index), boardPath(projectKey));
}

export async function deleteIssueAction(projectKey: string, key: string): Promise<ActionResult<void>> {
  return run(() => boardApi.deleteIssue(key), boardPath(projectKey));
}

export async function listAgentRunsAction(issueKey: string): Promise<ActionResult<AgentRun[]>> {
  return run(() => boardApi.agentRuns({ issueKey, limit: 10 }));
}

export async function listActivityAction(key: string): Promise<ActionResult<Activity[]>> {
  return run(() => boardApi.issueActivity(key));
}

export async function listCommentsAction(key: string): Promise<ActionResult<Comment[]>> {
  return run(() => boardApi.comments(key));
}

export async function addCommentAction(key: string, body: string): Promise<ActionResult<Comment>> {
  return run(() => boardApi.addComment(key, body));
}

export async function createProjectAction(input: {
  key: string;
  name: string;
  description: string | null;
  templateKey: string;
}): Promise<ActionResult<Project>> {
  return run(() => boardApi.createProject(input), "/");
}

// ---- Metodología y documentos ----

export async function setMethodologyAction(projectKey: string, methodology: string | null): Promise<ActionResult<Project>> {
  return run(() => boardApi.setMethodology(projectKey, methodology), `/projects/${projectKey}`);
}

export async function createDocAction(
  projectKey: string,
  kind: DocumentKind,
  issueKey: string | null,
): Promise<ActionResult<DocumentWithContent>> {
  return run(() => boardApi.createDoc(projectKey, kind, issueKey), `/projects/${projectKey}/docs`);
}

export async function listMethodologiesAction(): Promise<ActionResult<Methodology[]>> {
  return run(() => boardApi.catalog("methodologies"));
}

// ---- Espacio de trabajo y catálogo (solo personas) ----

export async function updateWorkspaceAction(input: {
  name: string;
  description: string | null;
  brandColor: string;
  brandOnColor: string | null;
  logo: string | null;
}): Promise<ActionResult<Workspace>> {
  // La marca pinta todo el armazón: se refresca el layout completo.
  return run(() => boardApi.updateWorkspace(input), "/");
}

// ---- Alta de espacios (pública) ----

export async function signupCodeAction(email: string): Promise<ActionResult<void>> {
  return run(() => boardApi.signupCode(email));
}

export async function signupVerifyAction(email: string, code: string): Promise<ActionResult<{ signupToken: string }>> {
  return run(() => boardApi.signupVerify(email, code));
}

export async function signupSlugAction(name: string): Promise<ActionResult<{ slug: string }>> {
  return run(() => boardApi.signupSlug(name));
}

/** Crea el espacio y abre sesión con la persona dueña: el onboarding termina dentro del tablero. */
export async function signupCompleteAction(input: {
  signupToken: string;
  email: string;
  companyName: string;
  slug: string | null;
  description: string | null;
  brandColor: string;
  brandOnColor: string | null;
  logo: string | null;
  ownerName: string;
  password: string;
}): Promise<ActionResult<void>> {
  const { email, ...body } = input;
  const created = await run(() => boardApi.signupComplete(body));
  if (!created.ok) return created;
  const session = await run(() => boardApi.login(email, input.password));
  if (!session.ok) return session;
  await setSessionToken(session.data.token, session.data.expiresAt);
  redirect("/");
}

export async function updateProjectAction(key: string, input: { name: string; description: string | null }): Promise<ActionResult<Project>> {
  return run(() => boardApi.updateProject(key, input), `/projects/${key}`);
}

export async function setTransitionsAction(key: string, transitions: Transitions): Promise<ActionResult<Project>> {
  return run(() => boardApi.setTransitions(key, transitions), `/projects/${key}`);
}

export async function saveCatalogEntryAction<T extends CatalogType>(
  type: T,
  entry: CatalogEntries[T],
): Promise<ActionResult<CatalogEntries[T]>> {
  return run(() => boardApi.saveCatalogEntry(type, entry), "/settings");
}

export async function deleteCatalogEntryAction(type: CatalogType, key: string): Promise<ActionResult<void>> {
  return run(() => boardApi.deleteCatalogEntry(type, key), "/settings");
}

export async function restoreCatalogEntryAction<T extends CatalogType>(type: T, key: string): Promise<ActionResult<CatalogEntries[T]>> {
  return run(() => boardApi.restoreCatalogEntry(type, key), "/settings");
}

export async function listIssueDocsAction(issueKey: string): Promise<ActionResult<DocumentMeta[]>> {
  return run(() => boardApi.issueDocs(issueKey));
}

export async function saveDocAction(
  id: string,
  baseVersion: number,
  content: string,
  note: string | null,
): Promise<ActionResult<DocumentWithContent>> {
  return run(() => boardApi.saveDoc(id, baseVersion, content, note));
}

export async function listDocVersionsAction(id: string): Promise<ActionResult<DocumentVersionMeta[]>> {
  return run(() => boardApi.docVersions(id));
}

export async function getDocVersionAction(id: string, version: number): Promise<ActionResult<string>> {
  return run(async () => (await boardApi.docVersion(id, version)).content);
}

export async function restoreDocAction(id: string, version: number, baseVersion: number): Promise<ActionResult<DocumentWithContent>> {
  return run(() => boardApi.restoreDoc(id, version, baseVersion));
}

// ---- Sincronización con el repositorio ----

const syncPath = (key: string) => `/projects/${key}/sync`;

export async function linkRepositoryAction(
  projectKey: string,
  input: { kind: RepositoryKind; location: string; branch: string | null; token: string | null },
): Promise<ActionResult<RepositoryLink>> {
  return run(() => boardApi.linkRepository(projectKey, input), `/projects/${projectKey}/settings`);
}

export async function unlinkRepositoryAction(projectKey: string): Promise<ActionResult<void>> {
  return run(() => boardApi.unlinkRepository(projectKey), `/projects/${projectKey}/settings`);
}

export async function syncCompareAction(projectKey: string, docId: string): Promise<ActionResult<SyncComparison>> {
  return run(() => boardApi.syncCompare(projectKey, docId));
}

export async function syncExportAction(projectKey: string, docId: string, force = false): Promise<ActionResult<Exported>> {
  return run(() => boardApi.syncExport(projectKey, docId, force), syncPath(projectKey));
}

export async function syncExportManyAction(projectKey: string, documentIds: string[]): Promise<ActionResult<Exported>> {
  return run(() => boardApi.syncExportMany(projectKey, documentIds), syncPath(projectKey));
}

export async function syncImportAction(projectKey: string, docId: string, force = false): Promise<ActionResult<SyncEntry>> {
  return run(() => boardApi.syncImport(projectKey, docId, force), syncPath(projectKey));
}

export async function syncImportNewAction(
  projectKey: string,
  group: string,
  issueKey: string | null,
  newIssueTitle: string | null,
): Promise<ActionResult<SyncEntry[]>> {
  return run(() => boardApi.syncImportNew(projectKey, group, issueKey, newIssueTitle), syncPath(projectKey));
}

export async function syncSafeAction(
  projectKey: string,
): Promise<ActionResult<{ imported: number; conflicts: number; exported: Exported | null }>> {
  return run(() => boardApi.syncSafe(projectKey), syncPath(projectKey));
}

export async function updateColumnsAction(
  projectKey: string,
  columns: (Omit<BoardColumn, "key"> & { key: string | null })[],
): Promise<ActionResult<Project>> {
  return run(() => boardApi.updateColumns(projectKey, columns), `/projects/${projectKey}`);
}

export async function createMemberAction(input: {
  name: string;
  kind: MemberKind;
  role: string;
  description: string | null;
  avatarColor: string;
  access?: MemberAccess;
}): Promise<ActionResult<Member>> {
  return run(() => boardApi.createMember(input), "/team");
}

export async function setMemberAccessAction(memberId: string, access: MemberAccess): Promise<ActionResult<{ id: string; access: MemberAccess }>> {
  return run(() => boardApi.setMemberAccess(memberId, access), "/team");
}

// ---- Preferencias de la interfaz ----

/** Guarda el tema elegido (la clase ya se cambió en el navegador; esto lo recuerda para el servidor). */
export async function setThemeAction(mode: ThemeMode): Promise<void> {
  (await cookies()).set(THEME_COOKIE, parseTheme(mode), { path: "/", sameSite: "lax", maxAge: 60 * 60 * 24 * 365 });
}

// ---- Etapas (Fase 1) ----

export async function setStagesAction(key: string, stages: StageDef[], maxRework: number): Promise<ActionResult<Project>> {
  return run(() => boardApi.setStages(key, stages, maxRework), `/projects/${key}`);
}

export async function takeIssueAction(projectKey: string, key: string): Promise<ActionResult<Issue>> {
  return run(() => boardApi.takeIssue(key), `/projects/${projectKey}`);
}

export async function deliverIssueAction(projectKey: string, key: string, comment: string | null): Promise<ActionResult<Issue>> {
  return run(() => boardApi.deliverIssue(key, comment), `/projects/${projectKey}`);
}

export async function reviewIssueAction(projectKey: string, key: string, approve: boolean, comment: string | null): Promise<ActionResult<Issue>> {
  return run(() => boardApi.reviewIssue(key, approve, comment), `/projects/${projectKey}`);
}
