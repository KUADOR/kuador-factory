package ec.kuador.board.member.infrastructure.web;

import ec.kuador.board.member.application.MemberService;
import ec.kuador.board.member.domain.Member;
import ec.kuador.board.member.domain.MemberKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import ec.kuador.board.member.MemberAccess;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members")
class MemberController {

    private final MemberService service;

    MemberController(MemberService service) {
        this.service = service;
    }

    @GetMapping
    List<MemberResponse> list() {
        return service.list().stream().map(MemberResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    MemberResponse create(@Valid @RequestBody CreateMemberRequest r) {
        return MemberResponse.from(service.create(r.name(), r.kind(), r.role(), r.description(), r.avatarColor(), r.access()));
    }

    record CreateMemberRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull MemberKind kind,
            @NotBlank String role,
            @Size(max = 4000) String description,
            @NotBlank String avatarColor,
            /** Opcional: por defecto VIEW para clientes y EDIT para el resto. */
            MemberAccess access) {}

    record MemberResponse(
            UUID id, String name, MemberKind kind, String role, String description, String avatarColor, MemberAccess access, Instant createdAt) {

        static MemberResponse from(Member m) {
            return new MemberResponse(m.id(), m.name(), m.kind(), m.role(), m.description(), m.avatarColor(), m.access(), m.createdAt());
        }
    }
}
