package io.github.viniciusssantos.flagforge.platform;

import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ProblemDetailsResponseWriter problemDetailsResponseWriter,
            SdkCredentialAuthenticationFilter sdkCredentialAuthenticationFilter,
            HumanAuthenticationFilter humanAuthenticationFilter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class)).permitAll()
                        .requestMatchers("/livez", "/readyz").permitAll()
                        .requestMatchers("/api/v1/evaluate/**")
                        .hasAuthority(
                                SdkCredentialAuthenticationFilter
                                        .EVALUATE_AUTHORITY)
                        .requestMatchers(
                                "/api/v1/organizations",
                                "/api/v1/organizations/current",
                                "/api/v1/projects",
                                "/api/v1/projects/*",
                                "/api/v1/projects/*/environments",
                                "/api/v1/environments/*",
                                "/api/v1/environments/*/publication",
                                "/api/v1/environments/*/audit",
                                "/api/v1/environments/*/revisions",
                                "/api/v1/environments/*/revisions/**",
                                "/api/v1/environments/*/rollback",
                                "/api/v1/environments/*/approval-policy",
                                "/api/v1/environments/*/change-requests",
                                "/api/v1/environments/*/change-requests/**")
                        .authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemDetailsResponseWriter::writeUnauthorized)
                        .accessDeniedHandler(problemDetailsResponseWriter::writeForbidden))
                .addFilterBefore(
                        sdkCredentialAuthenticationFilter,
                        AnonymousAuthenticationFilter.class)
                .addFilterBefore(
                        humanAuthenticationFilter,
                        AnonymousAuthenticationFilter.class)
                .headers(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    UserDetailsService noLocalUsers() {
        return username -> {
            throw new UsernameNotFoundException("No local users are configured");
        };
    }
}
