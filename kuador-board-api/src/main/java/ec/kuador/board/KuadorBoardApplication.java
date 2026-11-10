package ec.kuador.board;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// Sin usuario en memoria: la autenticación es solo por tokens (módulo auth). Evita que Spring
// imprima una contraseña generada en el log.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class KuadorBoardApplication {

    public static void main(String[] args) {
        SpringApplication.run(KuadorBoardApplication.class, args);
    }
}
