package io.durableflow.scheduler;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class InternalSecurity {
  @Bean
  public org.springframework.security.core.userdetails.UserDetailsService disabledPasswordUsers() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
          "Password authentication is disabled");
    };
  }

  @Bean
  public SecurityFilterChain internalChain(HttpSecurity http) {
    return http.csrf(c -> c.disable())
        .httpBasic(c -> c.disable())
        .formLogin(c -> c.disable())
        .authorizeHttpRequests(
            c ->
                c.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .build();
  }
}
