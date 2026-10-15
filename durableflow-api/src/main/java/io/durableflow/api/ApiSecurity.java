package io.durableflow.api;

import io.durableflow.persistence.Json;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@io.swagger.v3.oas.annotations.security.SecurityScheme(
    name = "bearerAuth",
    type = io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP,
    scheme = "bearer")
@Configuration
public class ApiSecurity {
  @Bean
  public org.springframework.security.core.userdetails.UserDetailsService disabledPasswordUsers() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
          "Password authentication is disabled");
    };
  }

  @Bean
  public SecurityFilterChain apiChain(
      HttpSecurity http,
      Json json,
      @Value("${DURABLEFLOW_OPERATOR_TOKEN}") String operator,
      @Value("${DURABLEFLOW_ADMIN_TOKEN}") String admin) {
    if (!operator.matches("[a-zA-Z0-9_-]{32,256}")
        || !admin.matches("[a-zA-Z0-9_-]{32,256}")
        || operator.equals(admin))
      throw new IllegalArgumentException(
          "Distinct operator/admin tokens of at least 32 characters are required");
    var operatorHash = hash(operator);
    var adminHash = hash(admin);
    var bearer =
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest request, HttpServletResponse response, FilterChain chain)
              throws ServletException, IOException {
            String authorization = request.getHeader("Authorization");
            if (authorization != null
                && authorization.startsWith("Bearer ")
                && authorization.length() <= 263) {
              byte[] candidate = hash(authorization.substring(7));
              boolean isAdmin = MessageDigest.isEqual(candidate, adminHash),
                  isOperator = MessageDigest.isEqual(candidate, operatorHash);
              if (isAdmin || isOperator) {
                String role = isAdmin ? "ADMIN" : "OPERATOR";
                var auth =
                    new UsernamePasswordAuthenticationToken(
                        role.toLowerCase(Locale.ROOT),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
              }
            }
            chain.doFilter(request, response);
          }
        };
    return http.csrf(c -> c.disable())
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(c -> c.disable())
        .formLogin(c -> c.disable())
        .logout(c -> c.disable())
        .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(
            c ->
                c.authenticationEntryPoint(
                        (req, res, ex) -> problem(res, json, 401, "authentication-required"))
                    .accessDeniedHandler(
                        (req, res, ex) -> problem(res, json, 403, "access-denied")))
        .authorizeHttpRequests(
            c ->
                c.requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/workflow-executions/*/cancel",
                        "/api/v1/task-executions/*/retry")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/api/v1/audit-events")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        "/api/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/actuator/prometheus")
                    .hasAnyRole("OPERATOR", "ADMIN")
                    .anyRequest()
                    .denyAll())
        .build();
  }

  private static byte[] hash(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  static void problem(HttpServletResponse response, Json json, int status, String code)
      throws IOException {
    response.setStatus(status);
    if (status == 401) response.setHeader("WWW-Authenticate", "Bearer realm=\"durableflow\"");
    response.setContentType("application/problem+json");
    response
        .getWriter()
        .write(
            json.write(
                Map.of(
                    "type",
                    "urn:durableflow:problem:" + code,
                    "status",
                    status,
                    "code",
                    code,
                    "detail",
                    "The request is not permitted")));
  }
}
