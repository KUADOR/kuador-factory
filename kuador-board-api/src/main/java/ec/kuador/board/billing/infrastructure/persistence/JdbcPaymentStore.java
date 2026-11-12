package ec.kuador.board.billing.infrastructure.persistence;

import ec.kuador.board.billing.domain.Payment;
import ec.kuador.board.billing.domain.PaymentProvider;
import ec.kuador.board.billing.domain.PaymentStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPaymentStore implements PaymentStore {

    private static final String PROVIDER = "code, type, name, mode, credentials_enc, active, sort_order";
    private static final String PAYMENT = "id, workspace_id, plan_code, provider_code, amount, currency, months, status, external_id, created_at, paid_at";

    private final JdbcClient jdbc;

    JdbcPaymentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StoredProvider> providers() {
        return jdbc.sql("select " + PROVIDER + " from payment_provider order by sort_order, code").query((rs, n) -> provider(rs)).list();
    }

    @Override
    public Optional<StoredProvider> provider(String code) {
        return jdbc.sql("select " + PROVIDER + " from payment_provider where code = ?").param(code).query((rs, n) -> provider(rs)).optional();
    }

    @Override
    public void saveProvider(StoredProvider p, Instant now) {
        jdbc.sql("""
                insert into payment_provider (code, type, name, mode, credentials_enc, active, sort_order, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (code) do update set type = excluded.type, name = excluded.name, mode = excluded.mode,
                    credentials_enc = excluded.credentials_enc, active = excluded.active, sort_order = excluded.sort_order, updated_at = excluded.updated_at""")
                .params(p.code(), p.type(), p.name(), p.mode().name(), p.credentialsEncrypted(), p.active(), p.sortOrder(), Timestamp.from(now)).update();
    }

    @Override
    public void save(Payment p) {
        jdbc.sql("insert into payment (" + PAYMENT + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(p.id(), p.workspaceId(), p.planCode(), p.providerCode(), p.amount(), p.currency(), p.months(), p.status().name(), p.externalId(),
                        Timestamp.from(p.createdAt()), p.paidAt() == null ? null : Timestamp.from(p.paidAt()))
                .update();
    }

    @Override
    public Optional<Payment> find(UUID id) {
        return jdbc.sql("select " + PAYMENT + " from payment where id = ?").param(id).query((rs, n) -> payment(rs)).optional();
    }

    @Override
    public Optional<Payment> findByExternal(String providerCode, String externalId) {
        return jdbc.sql("select " + PAYMENT + " from payment where provider_code = ? and external_id = ?").params(providerCode, externalId)
                .query((rs, n) -> payment(rs)).optional();
    }

    @Override
    public void setExternalId(UUID id, String externalId) {
        jdbc.sql("update payment set external_id = ? where id = ?").params(externalId, id).update();
    }

    @Override
    public boolean markPaid(UUID id, Instant now) {
        return jdbc.sql("update payment set status = 'PAID', paid_at = ? where id = ? and status <> 'PAID'").params(Timestamp.from(now), id).update() == 1;
    }

    @Override
    public void markFailed(UUID id) {
        jdbc.sql("update payment set status = 'FAILED' where id = ? and status = 'CREATED'").param(id).update();
    }

    @Override
    public List<Payment> recent(UUID workspaceId, int limit) {
        return jdbc.sql("select " + PAYMENT + " from payment where workspace_id = ? order by created_at desc limit ?").params(workspaceId, limit)
                .query((rs, n) -> payment(rs)).list();
    }

    private static StoredProvider provider(ResultSet rs) throws SQLException {
        return new StoredProvider(rs.getString("code"), rs.getString("type"), rs.getString("name"), PaymentProvider.Mode.valueOf(rs.getString("mode")),
                rs.getString("credentials_enc"), rs.getBoolean("active"), rs.getInt("sort_order"));
    }

    private static Payment payment(ResultSet rs) throws SQLException {
        Timestamp paid = rs.getTimestamp("paid_at");
        return new Payment(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getString("plan_code"), rs.getString("provider_code"),
                rs.getBigDecimal("amount"), rs.getString("currency"), rs.getInt("months"), Payment.Status.valueOf(rs.getString("status")),
                rs.getString("external_id"), rs.getTimestamp("created_at").toInstant(), paid == null ? null : paid.toInstant());
    }
}
