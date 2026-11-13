package ec.kuador.board.boardaccess;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class BoardAccessConfiguration implements WebMvcConfigurer {

    private final BoardAccessInterceptor interceptor;

    BoardAccessConfiguration(BoardAccessInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**", "/mcp");
    }
}
