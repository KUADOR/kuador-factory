"use server";

import { revalidatePath } from "next/cache";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";

import { ApiError, boardApi } from "@/lib/api";
import { clearSessionToken, safeNext, setSessionToken } from "@/lib/session";
import { subjectCookie } from "@/lib/subjects";
import { THEME_COOKIE, parseTheme, type ThemeMode } from "@/theme/theme";
import type {
  BoardDesign,
  DeletePlan,
  DesignQuestion,
  DesignRequest,
  BusinessConnection,
  ConnectionLogin,
  MailConnectionInput,
  ApiConnectionInput,
  ApiSpec,
  DemoErpInfo,
  MailInbox,
  MailInboxInput,
  MailDetection,
  Capability,
  EstimateUnit,
  IssueTypeDef,
  Routine,
  RoutineInput,
  CatalogRevision,
  DraftInput,
  DraftJob,
  ChatAnswer,
  DraftStep,
  RunnerMachine,
  PieceUpdate,
  ProjectPiece,
  AgentPromptPreview,
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
  PieceInput,
  PiecePlan,
  PiecesSetup,
  SetupResult,
  IssueInput,
  IssueStatus,
  AgentClient,
  AgentKit,
  EpicTask,
  EpicPlanStatus,
  Language,
  NotificationSettings,
  NotificationView,
  PushSubscriptionInput,
  CardPull,
  Merged,
  Member,
  MemberAccess,
  MemberKind,
  Project,
  RepoKit,
  LicenseStatus,
  Plan,
  PlanInput,
  WorkspaceLicense,
  WorkspacePlan,
  Invitation,
  TelegramGroup,
  TelegramSetup,
  PaymentMode,
  PaymentProviderView,
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
  DraftApproval,
  DraftApproved,
  Subject,
  SubjectListing,
  Team,
  TeamRef,
} from "@/lib/types";

async function run<T>(work: () => Promise<T>, pathToRefresh?: string, layoutToo = false): Promise<ActionResult<T>> {
  let expired = false;
  try {
    const data = await work();
    // Lo de un proyecto pedido desde el proyecto (clave «~GYM»): se refresca su página en Organización.
    const scoped = pathToRefresh ? /^\/projects\/~([^/]+)/.exec(pathToRefresh) : null;
    if (scoped) revalidatePath(`/org/projects/${scoped[1]}`, "layout");
    else if (pathToRefresh) revalidatePath(pathToRefresh);
    // Cambia lo que espera a alguien: el menú (número de «Mis decisiones») se vuelve a calcular en toda la app.
    if (layoutToo) revalidatePath("/", "layout");
    return { ok: true, data };
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      expired = true;
    } else if (error instanceof ApiError) {
      return { ok: false, error: error.message, status: error.status };
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

export async function createAgentTokenAction(memberId: string, name: string): Promise<ActionResult<IssuedToken>> {
  return run(() => boardApi.createAgentToken(memberId, name), "/team");
}

export async function createRunnerTokenAction(name: string): Promise<ActionResult<IssuedToken>> {
  return run(() => boardApi.createRunnerToken(name), "/local-agents");
}

export async function revokeRunnerTokenAction(tokenId: string): Promise<ActionResult<void>> {
  return run(() => boardApi.revokeRunnerToken(tokenId), "/local-agents");
}

export async function saveInboxAction(projectKey: string, id: string | null, input: MailInboxInput): Promise<ActionResult<MailInbox>> {
  const path = `/projects/${projectKey}/settings/intake`;
  return run(() => (id ? boardApi.updateInbox(id, input) : boardApi.createInbox(projectKey, input)), path);
}

export async function deleteInboxAction(projectKey: string, id: string): Promise<ActionResult<void>> {
  return run(() => boardApi.deleteInbox(id), `/projects/${projectKey}/settings/intake`);
}

export async function checkInboxAction(projectKey: string, id: string): Promise<ActionResult<{ created: string[]; error: string | null }>> {
  return run(() => boardApi.checkInbox(id), `/projects/${projectKey}/settings/intake`);
}

export async function saveRoutineAction(projectKey: string, id: string | null, input: RoutineInput): Promise<ActionResult<Routine>> {
  const path = `/projects/${projectKey}/settings/intake`;
  return run(() => (id ? boardApi.updateRoutine(id, input) : boardApi.createRoutine(projectKey, input)), path);
}

export async function deleteRoutineAction(projectKey: string, id: string): Promise<ActionResult<void>> {
  return run(() => boardApi.deleteRoutine(id), `/projects/${projectKey}/settings/intake`);
}

export async function runRoutineAction(projectKey: string, id: string): Promise<ActionResult<{ issueKey: string | null; skipped: string | null }>> {
  return run(() => boardApi.runRoutine(id), `/projects/${projectKey}/settings/intake`);
}

export async function routineWebhookAction(projectKey: string, id: string): Promise<ActionResult<{ secret: string }>> {
  return run(() => boardApi.routineWebhook(id), `/projects/${projectKey}/settings/intake`);
}

export async function draftAskAction(projectKey: string, step: DraftStep, input: DraftInput, local: boolean): Promise<ActionResult<DraftJob>> {
  return run(() => boardApi.draftAsk(projectKey, step, input, local));
}

export async function askBoardChatAction(
  projectKey: string,
  question: string,
  history: { role: string; text: string }[],
): Promise<ActionResult<ChatAnswer>> {
  return run(() => boardApi.askBoardChat(projectKey, question, history));
}

export async function draftJobAction(id: string): Promise<ActionResult<DraftJob>> {
  return run(() => boardApi.draftJob(id));
}

export async function draftApproveAction(projectKey: string, approval: DraftApproval): Promise<ActionResult<DraftApproved>> {
  return run(() => boardApi.draftApprove(projectKey, approval), `/projects/${projectKey}/docs`, true);
}

export async function saveProjectGoalAction(projectKey: string, goal: string): Promise<ActionResult<{ goal: string | null }>> {
  return run(() => boardApi.saveProjectGoal(projectKey, goal), `/projects/${projectKey}`);
}

export async function saveHoursPerPointAction(hours: number): Promise<ActionResult<{ hoursPerPoint: number }>> {
  return run(() => boardApi.saveHoursPerPoint(hours), "/");
}

/** Cuántas decisiones esperan a quien mira (el número del menú, para refrescarlo sin recargar la página). */
export async function decisionsCountAction(): Promise<ActionResult<number>> {
  return run(async () => (await boardApi.decisions()).length);
}

export async function approveRunnerAction(userCode: string): Promise<ActionResult<{ machineName: string }>> {
  return run(() => boardApi.approveRunner(userCode), "/local-agents");
}

export async function updateRunnerMachineAction(
  id: string,
  patch: { paused?: boolean; roles?: string[]; capacity?: number; dailyMinutes?: number; noDailyLimit?: boolean },
): Promise<ActionResult<RunnerMachine>> {
  return run(() => boardApi.updateRunnerMachine(id, patch), "/local-agents");
}

export async function deleteRunnerMachineAction(id: string): Promise<ActionResult<void>> {
  return run(() => boardApi.deleteRunnerMachine(id), "/local-agents");
}

export async function agentKitAction(client: AgentClient, mcp: string[]): Promise<ActionResult<AgentKit>> {
  return run(() => boardApi.agentKit(client, mcp));
}

export async function setReviewByAction(projectKey: string, key: string, reviewBy: "PERSON" | "AGENT" | null): Promise<ActionResult<Issue>> {
  return run(() => boardApi.setReviewBy(key, reviewBy), `/projects/${projectKey}`);
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

export async function assignIssueAction(projectKey: string, key: string, assigneeId: string | null): Promise<ActionResult<Issue>> {
  return run(() => boardApi.assignIssue(key, assigneeId), boardPath(projectKey), true);
}

/** Posición «al final de la columna»: el mayor int de Java. */
const END_OF_COLUMN = 2_147_483_647;

export async function updateIssueAction(
  issue: Pick<Issue, "key" | "projectKey" | "status">,
  input: IssueInput,
  status: IssueStatus,
): Promise<ActionResult<Issue>> {
  return run(async () => {
    const updated = await boardApi.updateIssue(issue.key, input);
    // Cambiar de columna desde el formulario lo deja al final de la columna nueva (el API recorta la posición al tamaño
    // de la columna). El índice es un int de Java: Number.MAX_SAFE_INTEGER no cabe y el API respondía 400.
    return status === issue.status ? updated : boardApi.moveIssue(issue.key, status, END_OF_COLUMN);
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

/** Borrar varias tarjetas juntas; con {@code preview}, solo qué se borraría (para la confirmación). */
export async function deleteIssuesAction(projectKey: string, keys: string[], preview: boolean): Promise<ActionResult<DeletePlan>> {
  return preview ? run(() => boardApi.deleteIssues(keys, true)) : run(() => boardApi.deleteIssues(keys, false), boardPath(projectKey));
}

export async function listAgentRunsAction(issueKey: string): Promise<ActionResult<AgentRun[]>> {
  return run(() => boardApi.agentRuns({ issueKey, limit: 10 }));
}

export async function listActivityAction(key: string): Promise<ActionResult<Activity[]>> {
  return run(() => boardApi.issueActivity(key));
}

export async function listAttachmentsAction(key: string): Promise<ActionResult<AttachmentListing>> {
  return run(() => boardApi.attachments(key));
}

export async function deleteAttachmentAction(id: string): Promise<ActionResult<void>> {
  return run(() => boardApi.deleteAttachment(id));
}

export async function listCommentsAction(key: string): Promise<ActionResult<Comment[]>> {
  return run(() => boardApi.comments(key));
}

export async function addCommentAction(key: string, body: string): Promise<ActionResult<Comment>> {
  return run(() => boardApi.addComment(key, body));
}

export async function editCommentAction(key: string, id: string, body: string): Promise<ActionResult<Comment>> {
  return run(() => boardApi.editComment(key, id, body));
}

export async function deleteCommentAction(key: string, id: string): Promise<ActionResult<void>> {
  return run(() => boardApi.deleteComment(key, id));
}

/** Vuelve a lanzar un trabajo de agente que falló o se saltó. */
export async function retryAgentRunAction(id: string, projectKey: string): Promise<ActionResult<void>> {
  return run(() => boardApi.retryAgentRun(id), `/projects/${projectKey}`);
}

export async function createProjectAction(input: {
  key: string;
  name: string;
  description: string | null;
  templateKey: string;
  /** Proyectos del espacio en que trabaja; vacío = uno propio, con su nombre. */
  subjects?: string[];
  /** El equipo que lo atiende; vacío = uno nuevo con el nombre del tablero. */
  teamId?: string | null;
}): Promise<ActionResult<Project>> {
  return run(() => boardApi.createProject(input), "/");
}

// ---- Proyectos: aquello sobre lo que se trabaja (el tablero es el equipo y su flujo) ----

export async function createSubjectAction(input: { name: string; description: string | null }): Promise<ActionResult<SubjectListing>> {
  return run(() => boardApi.createSubject(input), "/org/projects");
}

export async function updateSubjectAction(key: string, input: { name: string; description: string | null }): Promise<ActionResult<SubjectListing>> {
  return run(() => boardApi.updateSubject(key, input), "/org/projects", true);
}

export async function deleteSubjectAction(key: string): Promise<ActionResult<void>> {
  return run(() => boardApi.deleteSubject(key), "/org/projects");
}

/** El manual del proyecto: vale en todos los tableros que usan sus documentos. */
export async function setSubjectMethodologyAction(key: string, methodology: string | null): Promise<ActionResult<SubjectListing>> {
  return run(() => boardApi.setSubjectMethodology(key, methodology), `/org/projects/${key}`, true);
}

export async function addBoardSubjectAction(projectKey: string, input: { subjectKey: string } | { name: string }): Promise<ActionResult<Subject[]>> {
  return run(() => boardApi.addBoardSubject(projectKey, input), boardPath(projectKey), true);
}

export async function removeBoardSubjectAction(projectKey: string, subjectKey: string): Promise<ActionResult<Subject[]>> {
  return run(async () => {
    const left = await boardApi.removeBoardSubject(projectKey, subjectKey);
    const jar = await cookies();
    if (jar.get(subjectCookie(projectKey))?.value === subjectKey) jar.delete(subjectCookie(projectKey));
    return left;
  }, boardPath(projectKey), true);
}

/** Recuerda el proyecto elegido en un tablero (nulo = todos): filtra las tarjetas y es el de sus documentos y su repositorio. */
export async function chooseSubjectAction(projectKey: string, subjectKey: string | null): Promise<void> {
  const jar = await cookies();
  if (subjectKey) jar.set(subjectCookie(projectKey), subjectKey, { path: "/", sameSite: "lax", maxAge: 60 * 60 * 24 * 365 });
  else jar.delete(subjectCookie(projectKey));
  revalidatePath(boardPath(projectKey), "layout");
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

export async function cardPullsAction(key: string): Promise<ActionResult<CardPull[]>> {
  return run(() => boardApi.cardPulls(key));
}

export async function mergePullAction(projectKey: string, key: string, repository: string, number: number): Promise<ActionResult<Merged>> {
  return run(() => boardApi.mergePull(key, repository, number, true), `/projects/${projectKey}`, true);
}

export async function subscribePushAction(s: PushSubscriptionInput): Promise<ActionResult<NotificationView>> {
  return run(() => boardApi.subscribePush(s), "/account");
}

export async function unsubscribePushAction(endpoint: string): Promise<ActionResult<NotificationView>> {
  return run(() => boardApi.unsubscribePush(endpoint), "/account");
}

export async function updateNotificationsAction(input: Omit<NotificationSettings, "telegramChatId">): Promise<ActionResult<NotificationView>> {
  return run(() => boardApi.updateNotifications(input), "/account");
}

export async function notificationsAction(): Promise<ActionResult<NotificationView>> {
  return run(() => boardApi.notifications());
}

export async function telegramLinkAction(): Promise<ActionResult<{ url: string }>> {
  return run(() => boardApi.telegramLink());
}

export async function unlinkTelegramAction(): Promise<ActionResult<NotificationView>> {
  return run(() => boardApi.unlinkTelegram(), "/account");
}

export async function testNotificationsAction(): Promise<ActionResult<{ channels: string[] }>> {
  return run(() => boardApi.testNotifications());
}

export async function updateLanguagesAction(team: Language, code: Language): Promise<ActionResult<Workspace>> {
  return run(() => boardApi.updateLanguages(team, code), "/workspace");
}

/**
 * Respuesta de la persona en la pantalla de permiso OAuth. El destino lo valida la API (un redirect registrado
 * del cliente); el navegador vuelve ahí con el código o con el error.
 */
export async function oauthDecisionAction(input: {
  clientId: string;
  redirectUri: string | null;
  codeChallenge: string | null;
  codeChallengeMethod: string | null;
  state: string | null;
  approved: boolean;
}): Promise<ActionResult<never>> {
  let target: string;
  try {
    target = (await boardApi.oauthAuthorize(input)).redirect;
  } catch (error) {
    return { ok: false, error: error instanceof ApiError ? error.message : "No se pudo completar la conexión." };
  }
  redirect(target);
}

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
  plan: string | null;
  packageCode?: string | null;
  /** A dónde ir al terminar (p. ej. a pagar el plan elegido). */
  next?: string;
}): Promise<ActionResult<void>> {
  const { email, next, ...body } = input;
  const created = await run(() => boardApi.signupComplete(body));
  if (!created.ok) return created;
  const session = await run(() => boardApi.login(email, input.password));
  if (!session.ok) return session;
  await setSessionToken(session.data.token, session.data.expiresAt);
  // Solo destinos internos conocidos: el asistente del tablero inicial o el pago del plan.
  redirect(next === "/workspace/plan?bienvenida=1" || (next && /^\/projects\/[A-Z0-9]{2,10}\/setup$/.test(next)) ? next : "/");
}

export async function savePlatformCatalogEntryAction(
  type: CatalogType,
  key: string,
  entry: unknown,
  extra: { memberName?: string | null; color?: string | null },
): Promise<ActionResult<PlatformCatalogEntry>> {
  return run(() => boardApi.savePlatformCatalogEntry(type, key, entry, extra), "/platform");
}

export async function discardPlatformCatalogEntryAction(type: CatalogType, key: string): Promise<ActionResult<void>> {
  return run(() => boardApi.discardPlatformCatalogEntry(type, key), "/platform");
}

export async function removePlatformCatalogEntryAction(type: CatalogType, key: string): Promise<ActionResult<void>> {
  return run(() => boardApi.removePlatformCatalogEntry(type, key), "/platform");
}

export async function publishPlatformCatalogAction(): Promise<ActionResult<PlatformCatalogSummary>> {
  return run(() => boardApi.publishPlatformCatalog(), "/platform");
}

export async function savePlatformPackageAction(p: CatalogPackage): Promise<ActionResult<CatalogPackage>> {
  return run(() => boardApi.savePlatformPackage(p), "/platform");
}

export async function addBoardPersonAction(key: string, memberId: string): Promise<ActionResult<BoardPerson[]>> {
  return run(() => boardApi.addBoardPerson(key, memberId), `/projects/${key}`);
}

export async function removeBoardPersonAction(key: string, memberId: string): Promise<ActionResult<BoardPerson[]>> {
  return run(() => boardApi.removeBoardPerson(key, memberId), `/projects/${key}`);
}

// ---- Equipos (Organización → Equipos) ----

export async function createTeamAction(input: { name: string; members?: string[] }): Promise<ActionResult<Team>> {
  return run(() => boardApi.createTeam(input), "/org/teams");
}

export async function renameTeamAction(id: string, name: string): Promise<ActionResult<Team>> {
  return run(() => boardApi.renameTeam(id, name), "/org/teams");
}

export async function deleteTeamAction(id: string): Promise<ActionResult<void>> {
  return run(() => boardApi.deleteTeam(id), "/org/teams");
}

export async function addTeamMemberAction(id: string, memberId: string): Promise<ActionResult<Team>> {
  return run(() => boardApi.addTeamMember(id, memberId), "/org/teams");
}

export async function removeTeamMemberAction(id: string, memberId: string): Promise<ActionResult<Team>> {
  return run(() => boardApi.removeTeamMember(id, memberId), "/org/teams");
}

/** Cambia el equipo de un tablero: cambia quién lo ve, así que se refresca todo. */
export async function assignTeamAction(id: string, projectKey: string): Promise<ActionResult<TeamRef>> {
  return run(() => boardApi.assignTeam(id, projectKey), "/", true);
}

export async function teamAgentsAction(id: string): Promise<ActionResult<TeamAgent[]>> {
  return run(() => boardApi.teamAgents(id));
}

export async function createAgentInTeamAction(
  id: string,
  input: { name: string; role: string; description: string | null; mode: AgentMode; model: string | null },
): Promise<ActionResult<TeamAgent[]>> {
  return run(() => boardApi.createAgentInTeam(id, input), "/org/teams");
}

export async function addToTeamAction(key: string, memberId: string): Promise<ActionResult<TeamAgent[]>> {
  return run(() => boardApi.addToTeam(key, memberId), `/projects/${key}`);
}

export async function createTeamAgentAction(
  key: string,
  input: { name: string; role: string; description: string | null; mode: AgentMode; model: string | null },
): Promise<ActionResult<TeamAgent[]>> {
  return run(() => boardApi.createTeamAgent(key, input), `/projects/${key}`);
}

export async function removeFromTeamAction(key: string, memberId: string): Promise<ActionResult<TeamAgent[]>> {
  return run(() => boardApi.removeFromTeam(key, memberId), `/projects/${key}`);
}

export async function setAgentProfileAction(memberId: string, input: { mode: AgentMode; model: string | null; effort?: string | null }): Promise<ActionResult<TeamAgent>> {
  return run(() => boardApi.setAgentProfile(memberId, input));
}

export async function setCardCapAction(cardCapUsd: number | null): Promise<ActionResult<AiUsageReport>> {
  return run(() => boardApi.setCardCap(cardCapUsd), "/connections");
}

export async function setAgentBudgetAction(agentId: string, monthlyUsd: number | null): Promise<ActionResult<AiUsageReport>> {
  return run(() => boardApi.setAgentBudget(agentId, monthlyUsd), "/connections");
}

export async function setAiBudgetAction(budgetUsd: number | null): Promise<ActionResult<AiUsageReport>> {
  return run(() => boardApi.setAiBudget(budgetUsd), "/connections");
}

export async function saveAiPriceAction(model: string, inputPerMTok: number, outputPerMTok: number): Promise<ActionResult<AiModelPrice[]>> {
  return run(() => boardApi.saveAiPrice(model, inputPerMTok, outputPerMTok), "/platform");
}

export async function deleteAiPriceAction(model: string): Promise<ActionResult<AiModelPrice[]>> {
  return run(() => boardApi.deleteAiPrice(model), "/platform");
}

/** Más líneas de auditoría (paginación hacia atrás). {@code scope}: el espacio o toda la plataforma. */
export async function auditPageAction(scope: "workspace" | "platform", query: string): Promise<ActionResult<AuditPage>> {
  try {
    return { ok: true, data: scope === "platform" ? await boardApi.platformAudit(query) : await boardApi.workspaceAudit(query) };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : "No se pudo cargar la auditoría." };
  }
}

export async function saveAiProviderAction(code: string, input: Omit<AiProvider, "code">): Promise<ActionResult<AiProvider[]>> {
  return run(() => boardApi.saveAiProvider(code, input), "/platform");
}

export async function saveWorkspaceAiAction(input: { provider: string; apiKey: string | null; defaultModel: string }): Promise<ActionResult<WorkspaceAiStatus>> {
  return run(() => boardApi.saveWorkspaceAi(input), "/connections");
}

export async function removeWorkspaceAiAction(): Promise<ActionResult<WorkspaceAiStatus>> {
  return run(() => boardApi.removeWorkspaceAi(), "/connections");
}

export async function saveBillingSettingsAction(reminderDays: number[]): Promise<ActionResult<{ reminderDays: number[] }>> {
  return run(() => boardApi.saveBillingSettings(reminderDays), "/platform");
}

export async function savePaymentProviderAction(
  code: string,
  input: { type: string; name: string; mode: PaymentMode; credentials: Record<string, string>; active: boolean; sortOrder: number },
): Promise<ActionResult<PaymentProviderView>> {
  return run(() => boardApi.savePaymentProvider(code, input), "/platform");
}

/** Inicia el pago; devuelve la URL de la pasarela, a donde el navegador va a pagar. */
export async function checkoutAction(input: { plan: string; provider: string; months: number }): Promise<ActionResult<{ paymentId: string; payUrl: string }>> {
  return run(() => boardApi.checkout(input));
}

export async function telegramSetupAction(): Promise<ActionResult<TelegramSetup>> {
  return run(() => boardApi.telegramSetup());
}

export async function connectTelegramBotAction(token: string): Promise<ActionResult<TelegramSetup>> {
  return run(() => boardApi.connectTelegramBot(token), "/connections");
}

export async function disconnectTelegramBotAction(): Promise<ActionResult<TelegramSetup>> {
  return run(() => boardApi.disconnectTelegramBot(), "/connections");
}

export async function telegramGroupCodeAction(): Promise<ActionResult<{ command: string; bot: string; url: string }>> {
  return run(() => boardApi.telegramGroupCode());
}

export async function updateTelegramGroupAction(id: string, input: { onAssigned: boolean; onGates: boolean; onPlans: boolean }): Promise<ActionResult<TelegramGroup>> {
  return run(() => boardApi.updateTelegramGroup(id, input), "/connections");
}

export async function removeTelegramGroupAction(id: string): Promise<ActionResult<void>> {
  return run(() => boardApi.removeTelegramGroup(id), "/connections");
}

export async function inviteAction(input: {
  memberId?: string;
  name?: string;
  role?: string;
  access?: MemberAccess;
  email: string;
  /** Tableros donde queda (quien edita o solo mira ve únicamente esos). */
  projects?: string[];
}): Promise<ActionResult<Invitation>> {
  return run(() => boardApi.invite(input), "/team");
}

export async function resendInvitationAction(id: string): Promise<ActionResult<Invitation>> {
  return run(() => boardApi.resendInvitation(id), "/team");
}

export async function cancelInvitationAction(id: string): Promise<ActionResult<void>> {
  return run(() => boardApi.cancelInvitation(id), "/team");
}

/** Acepta la invitación y deja la sesión iniciada. */
export async function acceptInvitationAction(token: string, password: string): Promise<ActionResult<void>> {
  const accepted = await run(() => boardApi.acceptInvitation(token, password));
  if (!accepted.ok) return accepted;
  const session = await run(() => boardApi.login(accepted.data.email, password));
  if (!session.ok) return session;
  await setSessionToken(session.data.token, session.data.expiresAt);
  redirect("/");
}

export async function requestPasswordResetAction(email: string): Promise<ActionResult<void>> {
  return run(() => boardApi.requestPasswordReset(email));
}

export async function confirmPasswordResetAction(input: {
  token: string | null;
  email: string | null;
  code: string | null;
  password: string;
}): Promise<ActionResult<void>> {
  return run(() => boardApi.confirmPasswordReset(input));
}

export async function changePlanAction(plan: string): Promise<ActionResult<WorkspacePlan>> {
  return run(() => boardApi.changePlan(plan), "/workspace/plan");
}

export async function savePlanAction(code: string, input: PlanInput): Promise<ActionResult<Plan>> {
  return run(() => boardApi.savePlan(code, input), "/platform");
}

export async function setLicenseAction(
  workspaceId: string,
  input: { plan: string; status: LicenseStatus; trialEndsAt: string | null; paidUntil: string | null },
): Promise<ActionResult<WorkspaceLicense>> {
  return run(() => boardApi.setLicense(workspaceId, input), "/platform");
}

/** Borra el tablero con todo su contenido y vuelve a la lista de proyectos. */
export async function deleteProjectAction(key: string, confirm: string): Promise<ActionResult<void>> {
  const r = await run(() => boardApi.deleteProject(key, confirm));
  if (!r.ok) return r;
  revalidatePath("/", "layout");
  redirect("/projects");
}

export async function updateProjectAction(key: string, input: { name: string; description: string | null }): Promise<ActionResult<Project>> {
  return run(() => boardApi.updateProject(key, input), `/projects/${key}`);
}

export async function setTransitionsAction(key: string, transitions: Transitions): Promise<ActionResult<Project>> {
  return run(() => boardApi.setTransitions(key, transitions), `/projects/${key}`);
}

export async function agentPromptPreviewAction(memberId: string, issue: string): Promise<ActionResult<AgentPromptPreview>> {
  return run(() => boardApi.agentPromptPreview(memberId, issue));
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

export async function catalogRevisionsAction(type: CatalogType, key: string): Promise<ActionResult<CatalogRevision[]>> {
  return run(() => boardApi.catalogRevisions(type, key));
}

export async function catalogRevisionAction<T extends CatalogType>(type: T, key: string, id: string): Promise<ActionResult<CatalogEntries[T]>> {
  return run(() => boardApi.catalogRevision(type, key, id));
}

export async function restoreCatalogRevisionAction<T extends CatalogType>(type: T, key: string, id: string): Promise<ActionResult<CatalogEntries[T]>> {
  return run(() => boardApi.restoreCatalogRevision(type, key, id), "/settings");
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
  input: { kind: RepositoryKind; location: string; branch: string | null; token: string | null; installationId?: number | null },
): Promise<ActionResult<RepositoryLink>> {
  return run(() => boardApi.linkRepository(projectKey, input), `/projects/${projectKey}/settings`);
}

export async function savePiecePlanAction(projectKey: string, plan: PiecePlan): Promise<ActionResult<PiecePlan>> {
  return run(() => boardApi.savePiecePlan(projectKey, plan), `/projects/${projectKey}/setup`);
}

export async function setupPiecesAction(projectKey: string, input: PiecesSetup): Promise<ActionResult<SetupResult>> {
  return run(() => boardApi.setupPieces(projectKey, input), `/projects/${projectKey}/settings`);
}

export async function updatePieceAction(projectKey: string, name: string, input: PieceUpdate): Promise<ActionResult<ProjectPiece>> {
  return run(() => boardApi.updatePiece(projectKey, name, input), `/projects/${projectKey}/sync/setup`);
}

export async function addPieceAction(projectKey: string, piece: PieceInput, privateRepo: boolean): Promise<ActionResult<SetupResult>> {
  return run(() => boardApi.addPiece(projectKey, piece, privateRepo), `/projects/${projectKey}/settings`);
}

// ---- Conexiones de GitHub ----

export async function githubManifestAction(appName: string, organization: string | null): Promise<ActionResult<{ postUrl: string; manifest: string }>> {
  return run(() => boardApi.githubManifest(appName, organization));
}

export async function githubStartInstallAction(): Promise<ActionResult<{ url: string }>> {
  return run(() => boardApi.githubStartInstall());
}

export async function githubDisconnectAction(installationId: number): Promise<ActionResult<void>> {
  return run(() => boardApi.githubDisconnect(installationId), "/connections");
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

export async function proposeAgentKitAction(projectKey: string): Promise<ActionResult<RepoKit[]>> {
  return run(() => boardApi.proposeAgentKit(projectKey), syncPath(projectKey));
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

export async function designBoardAction(body: DesignRequest): Promise<ActionResult<BoardDesign>> {
  return run(() => boardApi.designBoard(body));
}

export async function designQuestionsAction(description: string): Promise<ActionResult<{ questions: DesignQuestion[] }>> {
  return run(() => boardApi.designQuestions(description));
}

export async function createDesignedBoardAction(design: BoardDesign, key: string, name: string): Promise<ActionResult<{ key: string }>> {
  return run(() => boardApi.createDesignedBoard(design, key, name), "/", true);
}

export async function saveConnectionAction(
  key: string,
  body: { name: string; url: string; header: string | null; secret: string | null },
): Promise<ActionResult<BusinessConnection>> {
  return run(() => boardApi.saveConnection(key, body), "/connections");
}

/** Lee la documentación de una API (desde su dirección o pegada) para elegir qué operaciones usa el agente. */
export async function readApiSpecAction(body: { url: string | null; text: string | null }): Promise<ActionResult<ApiSpec>> {
  return run(() => boardApi.readApiSpec(body));
}

export async function saveApiConnectionAction(key: string, body: ApiConnectionInput): Promise<ActionResult<BusinessConnection>> {
  return run(() => boardApi.saveApiConnection(key, body), "/connections");
}

export async function demoErpAction(): Promise<ActionResult<DemoErpInfo>> {
  return run(() => boardApi.demoErp());
}

/** Con qué conector se conecta un correo, según dónde está alojado su dominio. */
export async function detectMailAction(email: string): Promise<ActionResult<MailDetection>> {
  return run(() => boardApi.detectMail(email));
}

/** Un buzón por IMAP y SMTP con su correo y contraseña (Hostinger, cPanel…). */
export async function saveMailConnectionAction(key: string, body: MailConnectionInput): Promise<ActionResult<BusinessConnection>> {
  return run(() => boardApi.saveMailConnection(key, body), "/connections");
}

/** Conexión que pide iniciar sesión: devuelve la dirección del sistema a la que debe ir el navegador. */
export async function startConnectionLoginAction(
  key: string,
  body: ConnectionLogin,
): Promise<ActionResult<{ authorizationUrl: string }>> {
  return run(() => boardApi.startConnectionLogin(key, body), "/connections");
}

export async function refreshConnectionAction(key: string): Promise<ActionResult<BusinessConnection>> {
  return run(() => boardApi.refreshConnection(key), "/connections");
}

export async function setConnectionWritesAction(key: string, tools: string[]): Promise<ActionResult<BusinessConnection>> {
  return run(() => boardApi.setConnectionWrites(key, tools), "/connections");
}

export async function setConnectionBoardsAction(key: string, boards: { projectKey: string; roles: string[] }[]): Promise<ActionResult<BusinessConnection>> {
  return run(() => boardApi.setConnectionBoards(key, boards), "/connections");
}

export async function deleteConnectionAction(key: string): Promise<ActionResult<void>> {
  return run(() => boardApi.deleteConnection(key), "/connections");
}

export async function setCardAction(key: string, issueTypes: IssueTypeDef[], estimate: EstimateUnit): Promise<ActionResult<Project>> {
  return run(() => boardApi.setCard(key, issueTypes, estimate), `/projects/${key}`);
}

export async function setCapabilitiesAction(key: string, capabilities: Capability[]): Promise<ActionResult<Project>> {
  return run(() => boardApi.setCapabilities(key, capabilities), `/projects/${key}`);
}

export async function setStagesAction(key: string, stages: StageDef[], maxRework: number): Promise<ActionResult<Project>> {
  return run(() => boardApi.setStages(key, stages, maxRework), `/projects/${key}`);
}

export async function takeIssueAction(projectKey: string, key: string): Promise<ActionResult<Issue>> {
  return run(() => boardApi.takeIssue(key), `/projects/${projectKey}`, true);
}

export async function deliverIssueAction(projectKey: string, key: string, comment: string | null): Promise<ActionResult<Issue>> {
  return run(() => boardApi.deliverIssue(key, comment), `/projects/${projectKey}`, true);
}

export async function listEpicTasksAction(key: string): Promise<ActionResult<EpicTask[]>> {
  return run(() => boardApi.epicTasks(key));
}

/** El reparto de la épica y si ya se puede aprobar (la misma regla que aplica la API al aprobar). */
export async function epicPlanAction(key: string): Promise<ActionResult<EpicPlanStatus>> {
  return run(() => boardApi.epicPlan(key));
}

export async function reviewPlanAction(projectKey: string, key: string, approve: boolean, comment: string | null): Promise<ActionResult<Issue>> {
  return run(() => boardApi.reviewPlan(key, approve, comment), `/projects/${projectKey}`, true);
}

export async function reviewIssueAction(projectKey: string, key: string, approve: boolean, comment: string | null): Promise<ActionResult<Issue>> {
  return run(() => boardApi.reviewIssue(key, approve, comment), `/projects/${projectKey}`, true);
}
