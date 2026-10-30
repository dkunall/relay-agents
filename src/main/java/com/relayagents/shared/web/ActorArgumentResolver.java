package com.relayagents.shared.web;

import com.relayagents.shared.domain.Actor;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets controllers take an {@link Actor} parameter instead of depending on Spring Security types.
 * The tenant always comes from the token, never from the request body or path.
 */
public class ActorArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String TENANT_CLAIM = "tenant";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(Actor.class);
    }

    @Override
    public Actor resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                 WebDataBinderFactory binderFactory) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt) || !auth.isAuthenticated()) {
            throw new IllegalStateException("Actor requested on a request without a JWT");
        }
        String tenant = jwt.getToken().getClaimAsString(TENANT_CLAIM);
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalStateException("Security config admitted a token without a tenant claim");
        }
        Set<String> scopes = auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith("SCOPE_"))
            .map(a -> a.substring("SCOPE_".length()))
            .collect(Collectors.toSet());
        return new Actor(auth.getName(), tenant, scopes);
    }
}
