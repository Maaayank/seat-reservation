package com.paytm.seats.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Supplies {@link AuthenticatedUser} controller parameters from the request
 * attribute set by {@link BearerTokenFilter}. Fails loudly if a handler asks
 * for a user on a route the filter does not protect.
 */
@Configuration(proxyBeanMethods = false)
class AuthenticatedUserArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(this);
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType() == AuthenticatedUser.class;
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
		Object user = (request != null) ? request.getAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) : null;
		if (user == null) {
			throw new IllegalStateException(
					"route " + parameter.getMethod() + " needs AuthenticatedUser but is not covered by BearerTokenFilter");
		}
		return user;
	}

}
