package ec.kuador.board.issue.application;

import ec.kuador.board.issue.domain.Comment;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.shared.error.ForbiddenException;
import ec.kuador.board.shared.error.NotFoundException;
import java.util.List;
import java.util.UUID;

/** Los comentarios de una tarjeta: leerlos, escribir, y que su autor los corrija o los borre. */
final class IssueComments extends IssueSupport {

    IssueComments(Ports ports) {
        super(ports);
    }

    List<Comment> comments(String key) {
        return issues.findComments(load(key));
    }

    Comment comment(String key, UUID authorId, String body) {
        Issue issue = load(key);
        requireMember(authorId);
        return saveComment(issue, authorId, body == null ? "" : body, now());
    }

    /** Su autor corrige el comentario (p. ej. lo acorta para que un agente lea menos). */
    Comment editComment(String key, UUID commentId, UUID actorId, String body) {
        Comment c = ownComment(key, commentId);
        if (!c.authorId().equals(actorId)) throw new ForbiddenException("Solo quien escribió el comentario puede editarlo.");
        return issues.saveComment(c.edited(body, now()));
    }

    /** Lo borra su autor o quien administra el espacio. */
    void deleteComment(String key, UUID commentId, UUID actorId) {
        Comment c = ownComment(key, commentId);
        boolean admin = members.find(actorId).map(MemberView::administers).orElse(false);
        if (!c.authorId().equals(actorId) && !admin) throw new ForbiddenException("Solo quien escribió el comentario (o quien administra) puede borrarlo.");
        issues.deleteComment(c.id());
    }

    /** El comentario, si es de ese issue. */
    Comment ownComment(String key, UUID commentId) {
        Issue issue = load(key);
        return issues.findComment(commentId).filter(c -> c.issueId().equals(issue.id()))
                .orElseThrow(() -> new NotFoundException("No existe ese comentario en " + issue.key() + "."));
    }
}
