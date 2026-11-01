package local.printer.auth;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import local.printer.common.ApiResponses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.CookieTheftException;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    // Persistent tokens are opaque random values. This key binds framework authentication,
    // rather than signing a username/password-derived cookie.
    private static final String REMEMBER_KEY = "printer-persistent-login-v1";
    @Bean PasswordEncoder passwordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }

    @Bean PersistentTokenBasedRememberMeServices rememberMeServices(UserDetailsService users, DataSource dataSource, Environment env) {
        var tokens = new JdbcTokenRepositoryImpl();
        tokens.setDataSource(dataSource);
        var remember = new PersistentTokenBasedRememberMeServices(REMEMBER_KEY, users, tokens) {
            @Override public Authentication autoLogin(HttpServletRequest request, HttpServletResponse response) {
                try { return super.autoLogin(request, response); }
                // The framework has revoked all user tokens and cleared the cookie.
                // Continue anonymously so the existing JSON 401 contract remains intact.
                catch (CookieTheftException revoked) { return null; }
            }
        };
        remember.setCookieName("PRINTER_REMEMBER");
        remember.setParameter("remember-me");
        remember.setTokenValiditySeconds(14 * 24 * 60 * 60);
        remember.setUseSecureCookie(env.getProperty("SESSION_COOKIE_SECURE", Boolean.class, false));
        remember.setCookieCustomizer(cookie -> cookie.setAttribute("SameSite", "Lax"));
        return remember;
    }

    @Bean UserDetailsService userDetailsService(AuthUserMapper mapper) {
        return username -> {
            var account = mapper.findByUsername(username);
            if (account == null || !("CUSTOMER".equals(account.role()) || "STAFF".equals(account.role()))) {
                throw new UsernameNotFoundException("账号或密码错误");
            }
            return new SessionUser(account);
        };
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, UserDetailsService users, PasswordEncoder encoder,
            PersistentTokenBasedRememberMeServices remember) throws Exception {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        http.authenticationProvider(provider)
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/repair/", "/repair/index.html", "/repair/style.css", "/repair/api.js", "/repair/app.js").permitAll()
                .requestMatchers(HttpMethod.GET, "/app", "/app/", "/app/**").permitAll()
                .requestMatchers(HttpMethod.HEAD, "/app", "/app/", "/app/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll()
                .requestMatchers("/api/orders", "/api/orders/**").hasRole("CUSTOMER")
                .requestMatchers("/api/conversations", "/api/conversations/**").hasRole("CUSTOMER")
                .requestMatchers("/api/repair-drafts", "/api/repair-drafts/**").hasRole("CUSTOMER")
                .requestMatchers("/api/ticket-operations", "/api/ticket-operations/**").hasRole("CUSTOMER")
                .requestMatchers("/api/staff/**").hasRole("STAFF")
                .requestMatchers(HttpMethod.POST, "/api/tickets/*/accept", "/api/tickets/*/notes", "/api/tickets/*/close").hasRole("STAFF")
                .requestMatchers("/api/tickets", "/api/tickets/**").hasAnyRole("CUSTOMER", "STAFF")
                .anyRequest().authenticated())
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, failure) -> ApiResponses.error(request, response, 401, "UNAUTHENTICATED", "请先登录"))
                .accessDeniedHandler((request, response, failure) -> ApiResponses.error(request, response, 403,
                    failure instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN",
                    failure instanceof CsrfException ? "请求校验失败，请重新获取CSRF令牌" : "没有执行此操作的权限")))
            .formLogin(login -> login.loginPage("/api/auth/login").loginProcessingUrl("/api/auth/login")
                .successHandler((request, response, authentication) -> ApiResponses.write(request, response, 200,
                    ((SessionUser) authentication.getPrincipal()).identity()))
                .failureHandler((request, response, failure) -> ApiResponses.error(request, response, 401, "AUTHENTICATION_FAILED", "账号或密码错误")))
            .rememberMe(config -> config.key(REMEMBER_KEY).rememberMeServices(remember))
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .deleteCookies("JSESSIONID", "PRINTER_REMEMBER")
                .logoutSuccessHandler((request, response, authentication) -> ApiResponses.write(request, response, 200, java.util.Map.of("loggedOut", true))))
            .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()));
        // 保留默认 HttpSessionCsrfTokenRepository 和 XOR token 处理；登录/退出均由框架清理旧 token。
        return http.build();
    }
}
