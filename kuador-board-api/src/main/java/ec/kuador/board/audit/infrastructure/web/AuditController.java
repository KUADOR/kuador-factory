package ec.kuador.board.audit.infrastructure.web;

import ec.kuador.board.audit.application.AuditLog;
import ec.kuador.board.audit.domain.AuditEntry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consulta de la auditoría: la del espacio (quien edita) y la de toda la plataforma (quien la administra). */
@RestController
class AuditController {

    private final AuditLog audit;

    AuditController(AuditLog audit) {
        this.audit = audit;
    }

    @GetMapping("/api/workspace/audit")
    AuditLog.Page workspace(@RequestParam(required = false) AuditEntry.Category category, @RequestParam(required = false) UUID actor,
            @RequestParam(required = false) String q, @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant before,
            @RequestParam(defaultValue = "50") int limit) {
        return audit.workspace(category, actor, q, from, before, limit);
    }

    @GetMapping("/api/workspace/audit/export")
    ResponseEntity<byte[]> export(@RequestParam(required = false) AuditEntry.Category category, @RequestParam(required = false) UUID actor,
            @RequestParam(required = false) String q, @RequestParam(required = false) Instant from) {
        StringBuilder csv = new StringBuilder("﻿fecha_utc,persona,tipo,accion,sobre,resultado,estado_http,ip,navegador\r\n");
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
        for (AuditLog.View v : audit.exportWorkspace(category, actor, q, from)) {
            csv.append(String.join(",", field(f.format(v.occurredAt())), field(v.actorName()), field(v.actorKind()), field(v.label()), field(v.target()),
                    field(v.outcome()), String.valueOf(v.status()), field(v.ip()), field(v.userAgent()))).append("\r\n");
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"auditoria.csv\"").body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/api/platform/audit")
    AuditLog.Page platform(@RequestParam(required = false) UUID workspace, @RequestParam(required = false) AuditEntry.Category category,
            @RequestParam(required = false) String q, @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant before,
            @RequestParam(defaultValue = "50") int limit) {
        return audit.platform(workspace, category, q, from, before, limit);
    }

    /** CSV sin fórmulas: un valor que empieza con = + - @ se antepone con ' para que Excel no lo ejecute. */
    private static String field(String v) {
        if (v == null) return "";
        String s = !v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 ? "'" + v : v;
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
