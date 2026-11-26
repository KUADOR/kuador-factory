package ec.kuador.board.project.application;

import ec.kuador.board.project.SubjectUsage;
import ec.kuador.board.project.SubjectView;
import ec.kuador.board.project.domain.Project;
import ec.kuador.board.project.domain.ProjectRepository;
import ec.kuador.board.project.domain.Subject;
import ec.kuador.board.project.domain.SubjectRepository;
import ec.kuador.board.project.domain.SubjectRepository.Linked;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.BoardScope;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los proyectos del espacio (aquello sobre lo que se trabaja) y cuáles atiende cada tablero. Un tablero siempre atiende
 * al menos uno: al crearlo recibe el suyo, con su mismo nombre, si no se eligen otros.
 */
@Service
public class SubjectService {

    private final SubjectRepository subjects;
    private final ProjectRepository projects;
    private final SubjectUsage usage;
    private final Clock clock;

    SubjectService(SubjectRepository subjects, ProjectRepository projects, SubjectUsage usage, Clock clock) {
        this.subjects = subjects;
        this.projects = projects;
        this.usage = usage;
        this.clock = clock;
    }

    /** Un proyecto y los tableros que lo atienden (solo los que puede ver quien pide). */
    public record SubjectWithBoards(Subject subject, List<String> boards) {}

    @Transactional(readOnly = true)
    public List<SubjectWithBoards> list() {
        return subjects.findAll().stream().map(this::withBoards).toList();
    }

    @Transactional
    public SubjectWithBoards create(String key, String name, String description) {
        return withBoards(insert(key, name, description));
    }

    @Transactional
    public SubjectWithBoards update(String key, String name, String description) {
        Subject updated = load(key).withDetails(name, description); // valida
        subjects.updateDetails(updated.key(), updated.name(), updated.description());
        return withBoards(updated);
    }

    /** Un proyecto se borra solo cuando ningún tablero lo atiende. */
    @Transactional
    public void delete(String key) {
        Subject subject = load(key);
        List<String> boards = subjects.boardsOf(subject.key());
        if (!boards.isEmpty()) {
            throw new DomainException("«" + subject.name() + "» se trabaja en " + (boards.size() == 1 ? "el tablero " : "los tableros ")
                    + String.join(", ", boards) + ": quítalo de ahí antes de borrarlo.");
        }
        subjects.delete(subject.key());
    }

    /** Los proyectos del tablero, en su orden. Un tablero que aún no tiene ninguno guardado responde con el suyo. */
    @Transactional(readOnly = true)
    public List<SubjectView> ofBoard(String projectKey) {
        String key = projectKey.toUpperCase(Locale.ROOT);
        List<Linked> linked = subjects.ofBoard(key);
        if (!linked.isEmpty()) return linked.stream().map(SubjectService::view).toList();
        return projects.findByKey(key).map(p -> List.of(new SubjectView(p.key(), p.name(), p.description(), p.methodology()))).orElse(List.of());
    }

    /** Suma al tablero un proyecto que ya existe. */
    @Transactional
    public List<SubjectView> addToBoard(String projectKey, String subjectKey) {
        Project board = board(projectKey);
        ensureOwn(board);
        subjects.link(board.key(), load(subjectKey).key(), board.methodology());
        return ofBoard(board.key());
    }

    /** Crea un proyecto y lo suma al tablero de una vez. */
    @Transactional
    public List<SubjectView> createInBoard(String projectKey, String key, String name, String description) {
        Project board = board(projectKey);
        ensureOwn(board);
        subjects.link(board.key(), insert(key, name, description).key(), board.methodology());
        return ofBoard(board.key());
    }

    /** Quita el proyecto del tablero: solo si no le quedan tarjetas ahí y el tablero conserva otro. */
    @Transactional
    public List<SubjectView> removeFromBoard(String projectKey, String subjectKey) {
        Project board = board(projectKey);
        Subject subject = load(subjectKey);
        List<Linked> linked = subjects.ofBoard(board.key());
        if (linked.stream().noneMatch(s -> s.subject().key().equals(subject.key()))) return ofBoard(board.key());
        if (linked.size() == 1) throw new DomainException("Un tablero trabaja al menos en un proyecto: suma otro antes de quitar este.");
        long cards = usage.countIssues(board.key(), subject.key());
        if (cards > 0) {
            throw new DomainException("«" + subject.name() + "» tiene " + cards + (cards == 1 ? " tarjeta" : " tarjetas")
                    + " en este tablero: pásalas a otro proyecto o bórralas antes de quitarlo.");
        }
        subjects.unlink(board.key(), subject.key());
        // El manual del tablero es el de su primer proyecto: si se fue el primero, vale el del que queda.
        String first = subjects.ofBoard(board.key()).getFirst().methodology();
        if (!java.util.Objects.equals(first, board.methodology())) projects.updateMethodology(board.key(), first);
        return ofBoard(board.key());
    }

    /**
     * Deja el tablero recién creado con sus proyectos: los elegidos o, si no se eligió ninguno, uno propio con su nombre
     * y su clave (el que ya exista con esa clave se reutiliza).
     */
    void assign(Project board, List<String> subjectKeys) {
        List<String> wanted = subjectKeys == null ? List.of()
                : subjectKeys.stream().filter(k -> k != null && !k.isBlank()).map(k -> k.strip().toUpperCase(Locale.ROOT)).distinct().toList();
        if (wanted.isEmpty()) {
            ensureOwn(board);
            return;
        }
        for (String k : wanted) subjects.link(board.key(), load(k).key(), board.methodology());
    }

    /** Un tablero sin proyectos guardados queda con el suyo antes de sumarle otro. */
    private void ensureOwn(Project board) {
        if (!subjects.ofBoard(board.key()).isEmpty()) return;
        if (subjects.findByKey(board.key()).isEmpty()) {
            subjects.insert(new Subject(java.util.UUID.randomUUID(), board.key(), board.name(), clip(board.description()), clock.instant()));
        }
        subjects.link(board.key(), board.key(), board.methodology());
    }

    /**
     * Elige el manual de trabajo con que el tablero trabaja un proyecto. El del primer proyecto es también el del tablero
     * (el que reciben los proyectos que se sumen después).
     */
    void setMethodology(Project board, String subjectKey, String methodology) {
        ensureOwn(board);
        List<Linked> linked = subjects.ofBoard(board.key());
        String target = linked.stream().map(l -> l.subject().key()).filter(k -> k.equals(subjectKey)).findFirst().orElse(linked.getFirst().subject().key());
        subjects.setMethodology(board.key(), target, methodology);
        if (target.equals(linked.getFirst().subject().key())) projects.updateMethodology(board.key(), methodology);
    }

    /**
     * El tablero cambió de nombre: si trabaja en un solo proyecto, el suyo (misma clave, mismo nombre y de nadie más),
     * el proyecto cambia con él. Así quien usa un tablero por proyecto no tiene que renombrar dos cosas.
     */
    void renameOwn(Project before, Project after) {
        List<Linked> linked = subjects.ofBoard(before.key());
        if (linked.size() != 1) return;
        Subject own = linked.getFirst().subject();
        if (!own.key().equals(before.key()) || !own.name().equals(before.name()) || subjects.boardsOf(own.key()).size() != 1) return;
        subjects.updateDetails(own.key(), after.name(), clip(after.description()));
    }

    long boardsUsingMethodology(String methodology) {
        return subjects.countUsingMethodology(methodology);
    }

    private Subject insert(String key, String name, String description) {
        String wanted = key == null || key.isBlank() ? Subject.keyFor(name, k -> subjects.findByKey(k).isPresent()) : key;
        Subject subject = Subject.create(wanted, name, description, clock.instant());
        if (subjects.findByKey(subject.key()).isPresent()) {
            throw new ConflictException("Ya existe un proyecto con la clave " + subject.key() + ".");
        }
        subjects.insert(subject);
        return subject;
    }

    private SubjectWithBoards withBoards(Subject s) {
        return new SubjectWithBoards(s, subjects.boardsOf(s.key()).stream().filter(BoardScope::allows).toList());
    }

    private Project board(String projectKey) {
        return projects.findByKey(projectKey.toUpperCase(Locale.ROOT)).orElseThrow(() -> new NotFoundException("No existe el tablero " + projectKey + "."));
    }

    private Subject load(String key) {
        String k = key == null ? "" : key.strip().toUpperCase(Locale.ROOT);
        return subjects.findByKey(k).orElseThrow(() -> new NotFoundException("No existe el proyecto " + k + "."));
    }

    private static String clip(String text) {
        return text == null || text.length() <= 2000 ? text : text.substring(0, 2000);
    }

    private static SubjectView view(Linked l) {
        return new SubjectView(l.subject().key(), l.subject().name(), l.subject().description(), l.methodology());
    }
}
