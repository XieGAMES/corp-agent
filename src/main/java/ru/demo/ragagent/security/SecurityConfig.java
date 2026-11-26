package ru.demo.ragagent.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Единая Basic-аутентификация: и API, и страницы требуют учётные данные.
 * Учётных записей две, обе задаются в настройках (app.auth.*):
 *  - пользователь (app.auth.username / app.auth.password) — роль USER: чат с агентом;
 *  - служба качества (app.auth.quality-username / app.auth.quality-password) — роль QUALITY:
 *    страница заявок /quality.html и её API.
 * Без любого из паролей приложение не стартует.
 *
 * Состояния на сервере нет — каждый запрос сам несёт заголовок Authorization,
 * поэтому HTTP-сессии и CSRF отключены. Браузер показывает свой родной
 * диалог входа и дальше прикладывает учётные данные к запросам сам.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Роль службы качества: даёт доступ к странице заявок и её API. У обычного пользователя роль USER. */
    public static final String ROLE_QUALITY = "QUALITY";

    /** Имя realm в диалоге входа; его же возвращает LoginController при смене пользователя. */
    public static final String REALM = "rag-agent";

    /** Страница заявок службы качества. */
    private static final String QUALITY_PAGE = "/quality.html";

    /** Все запросы — только по роли; консоль службы качества — только её роли. */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                // Встроенный выход перехватывает /logout и отвечает 204: сессий нет, выходить не из чего.
                // «Сменить пользователя» — это LoginController (401), путь должен дойти до него
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        // «Сменить пользователя»: отвечает 401 сам (LoginController)
                        .requestMatchers(LoginController.LOGOUT_PATH).permitAll()
                        .requestMatchers(QUALITY_PAGE, "/api/quality-requests/**").hasRole(ROLE_QUALITY)
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.accessDeniedHandler(this::denyAccess))
                .httpBasic(basic -> basic.realmName(REALM))
                .build();
    }

    /** Кодировщик пароля: понимает формат «{noop}…» открытого текста. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Обе учётные записи приложения. Пароли держим как {noop}: они и так
     * приходят открытым текстом из .env, хешировать их в памяти незачем.
     */
    @Bean
    public UserDetailsService userDetailsService(
            @Value("${app.auth.username:admin}") String username,
            @Value("${app.auth.password:}") String password,
            @Value("${app.auth.quality-username:quality}") String qualityUsername,
            @Value("${app.auth.quality-password:}") String qualityPassword) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Не задан пароль для входа: добавьте строку "
                    + "APP_AUTH_PASSWORD=... в файл .env или переменную окружения");
        }
        if (qualityPassword == null || qualityPassword.isBlank()) {
            throw new IllegalStateException("Не задан пароль службы качества: добавьте строку "
                    + "APP_QUALITY_PASSWORD=... в файл .env или переменную окружения");
        }
        if (qualityUsername.equals(username)) {
            throw new IllegalStateException("Логин службы качества совпадает с логином пользователя: "
                    + username);
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(username).password("{noop}" + password).roles("USER").build(),
                User.withUsername(qualityUsername).password("{noop}" + qualityPassword).roles(ROLE_QUALITY).build());
    }

    /**
     * Отказ в доступе. Для API и прочих адресов — обычный 403. Страницу заявок
     * открывает браузер: вместо технической страницы ошибки возвращаем его в чат
     * с подсказкой, что нужна учётная запись службы качества.
     */
    private void denyAccess(HttpServletRequest request, HttpServletResponse response,
                            AccessDeniedException denied) throws IOException {
        boolean page = QUALITY_PAGE.equals(request.getRequestURI())
                && String.valueOf(request.getHeader("Accept")).contains("text/html");
        if (page) {
            response.sendRedirect("/?forbidden=quality");
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }
}
