package com.mentorloop.shared.security;

import com.mentorloop.shared.config.MentorLoopProperties;
import java.util.Set;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Turns the bearer token into a {@link Caller}. Staff subjects come from configuration, not from token claims. */
@Component
public class CallerResolver implements HandlerMethodArgumentResolver {

    private final Set<String> instructors;
    private final Set<String> admins;

    public CallerResolver(MentorLoopProperties props) {
        this.instructors = Set.copyOf(props.security().instructorSubjects());
        this.admins = Set.copyOf(props.security().adminSubjects());
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Caller.class;
    }

    @Override
    public Caller resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                  WebDataBinderFactory binders) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt) || jwt.getSubject() == null) {
            throw new IllegalStateException("no authenticated subject on a /v1 request");
        }
        String subject = jwt.getSubject();
        Role role = admins.contains(subject) ? Role.ADMIN
                : instructors.contains(subject) ? Role.INSTRUCTOR : Role.LEARNER;
        return new Caller(subject, role);
    }
}
