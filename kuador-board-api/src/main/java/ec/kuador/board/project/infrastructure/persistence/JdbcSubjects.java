package ec.kuador.board.project.infrastructure.persistence;

import ec.kuador.board.project.domain.Subject;
import ec.kuador.board.project.domain.SubjectRepository;
import ec.kuador.board.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL directo; cada consulta filtra por el espacio en contexto. */
@Repository
class JdbcSubjects implements SubjectRepository {

    private static final String COLUMNS = "s.id, s.subject_key, s.name, s.description, s.created_at";
    private static final RowMapper<Subject> ROW = (rs, n) -> new Subject(rs.getObject("id", UUID.class), rs.getString("subject_key"),
            rs.getString("name"), rs.getString("description"), rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcSubjects(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public List<Subject> findAll() {
        return jdbc.sql("select " + COLUMNS + " from subject s where s.workspace_id = ? order by lower(s.name)")
                .params(TenantContext.require()).query(ROW).list();
    }

    @Override
    public Optional<Subject> findByKey(String key) {
        return jdbc.sql("select " + COLUMNS + " from subject s where s.workspace_id = ? and s.subject_key = ?")
                .params(TenantContext.require(), key).query(ROW).optional();
    }

    @Override
    public void insert(Subject s) {
        jdbc.sql("insert into subject (id, workspace_id, subject_key, name, description, created_at) values (?, ?, ?, ?, ?, ?)")
                .params(s.id(), TenantContext.require(), s.key(), s.name(), s.description(), Timestamp.from(s.createdAt())).update();
    }

    @Override
    public void updateDetails(String key, String name, String description) {
        jdbc.sql("update subject set name = ?, description = ? where workspace_id = ? and subject_key = ?")
                .params(name, description, TenantContext.require(), key).update();
    }

    @Override
    public void delete(String key) {
        jdbc.sql("delete from subject where workspace_id = ? and subject_key = ?").params(TenantContext.require(), key).update();
    }

    @Override
    public List<Linked> ofBoard(String projectKey) {
        return jdbc.sql("select " + COLUMNS + ", b.methodology from board_subject b join subject s on s.workspace_id = b.workspace_id and s.subject_key = b.subject_key"
                + " where b.workspace_id = ? and b.project_key = ? order by b.position, b.added_at, s.subject_key")
                .params(TenantContext.require(), projectKey).query((rs, n) -> new Linked(ROW.mapRow(rs, n), rs.getString("methodology"))).list();
    }

    @Override
    public void setMethodology(String projectKey, String subjectKey, String methodology) {
        jdbc.sql("update board_subject set methodology = ? where workspace_id = ? and project_key = ? and subject_key = ?")
                .params(methodology, TenantContext.require(), projectKey, subjectKey).update();
    }

    @Override
    public long countUsingMethodology(String methodology) {
        return jdbc.sql("""
                select count(*) from (
                    select project_key from board_subject where workspace_id = ? and methodology = ?
                    union select project_key from project where workspace_id = ? and methodology = ?) boards""")
                .params(TenantContext.require(), methodology, TenantContext.require(), methodology).query(Long.class).single();
    }

    @Override
    public List<String> boardsOf(String subjectKey) {
        return jdbc.sql("select project_key from board_subject where workspace_id = ? and subject_key = ? order by project_key")
                .params(TenantContext.require(), subjectKey).query(String.class).list();
    }

    @Override
    public void link(String projectKey, String subjectKey, String methodology) {
        jdbc.sql("""
                insert into board_subject (workspace_id, project_key, subject_key, position, added_at, methodology)
                select ?, ?, ?, coalesce((select max(position) + 1 from board_subject where workspace_id = ? and project_key = ?), 0), ?, ?
                on conflict do nothing""")
                .params(TenantContext.require(), projectKey, subjectKey, TenantContext.require(), projectKey, Timestamp.from(clock.instant()), methodology)
                .update();
    }

    @Override
    public void unlink(String projectKey, String subjectKey) {
        jdbc.sql("delete from board_subject where workspace_id = ? and project_key = ? and subject_key = ?")
                .params(TenantContext.require(), projectKey, subjectKey).update();
    }
}
