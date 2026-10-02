package cn.bugstack.ai.trigger.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
public class SiteSecurityConfig {
    @org.springframework.beans.factory.annotation.Value("${management.server.port:0}")
    private int managementPort;
    @Bean public PasswordEncoder accountPasswordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean public SecurityContextRepository siteSecurityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain siteSecurity(HttpSecurity http, SecurityContextRepository repository) throws Exception {
        http.formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(c -> c.securityContextRepository(repository))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(r -> managementPort > 0 && r.getLocalPort() == managementPort
                                && (r.getRequestURI().equals("/actuator/prometheus") || r.getRequestURI().equals("/actuator/health")))
                        .permitAll()
                        .requestMatchers("/", "/index.html", "/auth.js", "/favicon.ico", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/register").permitAll()
                        .requestMatchers("/observe.html", "/observe-mcp.html", "/agent-config.html",
                                "/api/v1/observe/**", "/api/v1/agent/query_agent_config/**",
                                "/api/v1/agent/armory_api", "/api/v1/agent/armory_agent",
                                "/actuator/**", "/ops/**").hasRole("ADMIN")
                        // This existing upload endpoint is also used by the ordinary chat UI.
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/ai-client-rag-order/file/upload").authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> {
                            if (req.getRequestURI().endsWith(".html")) res.sendRedirect("/index.html");
                            else { res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                                res.getWriter().write("{\"code\":\"401\",\"info\":\"请先登录\"}"); }
                        })
                        .accessDeniedHandler((req, res, ex) -> {
                            res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write("{\"code\":\"403\",\"info\":\"无权访问或登录校验已过期，请刷新页面\"}");
                        }))
                .logout(l -> l.logoutUrl("/api/v1/auth/logout")
                        .deleteCookies("JSESSIONID", "SESSION").invalidateHttpSession(true)
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)));
        return http.build();
    }
}
