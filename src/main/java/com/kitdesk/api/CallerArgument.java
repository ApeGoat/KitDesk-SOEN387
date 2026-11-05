package com.kitdesk.api;

import com.kitdesk.config.AuthFilter;
import com.kitdesk.domain.DomainRuleViolation;
import com.kitdesk.service.Caller;
import com.kitdesk.service.ErrorCatalog;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Fills a controller's {@code Caller} parameter with the caller the auth filter verified. */
public class CallerArgument implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(Caller.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest request, WebDataBinderFactory binders) {
        Object caller = request.getAttribute(AuthFilter.CALLER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (caller == null) {
            // Can't happen behind the auth filter, but never default to "anonymous is allowed".
            throw new DomainRuleViolation(ErrorCatalog.UNAUTHORIZED, "A valid bearer token is required.");
        }
        return caller;
    }
}
