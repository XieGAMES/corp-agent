package ru.demo.ragagent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

/**
 * Единая Basic-аутентификация: и API, и демо-страница требуют учётные данные.
 * Логин и пароль задаются в app.auth.username / app.auth.password
 * (переменные APP_AUTH_USERNAME / APP_AUTH_PASSWORD или строки в .env);
 * без пароля приложение не стартует.
 *
 * Состояния на сервере нет — каждый запрос сам несёт заголовок Authorization,
 * поэтому HTTP-сессии и CSRF отключены. Браузер показывает свой родной
 * диалог входа и дальше прикладывает учётные данные к запросам сам.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Все запросы — только с Basic-учётными данными. */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .httpBasic(basic -> basic.realmName("rag-agent"))
                .build();
    }

    /** Кодировщик пароля: понимает формат «{noop}…» открытого текста. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Единственный пользователь приложения. Пароль держим как {noop}: он и так
     * приходит открытым текстом из .env, хешировать его в памяти незачем.
     */
    @Bean
    public UserDetailsService userDetailsService(
            @Value("${app.auth.username:admin}") String username,
            @Value("${app.auth.password:}") String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Не задан пароль для входа: добавьте строку "
                    + "APP_AUTH_PASSWORD=... в файл .env или переменную окружения");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(username).password("{noop}" + password).build());
    }
}
