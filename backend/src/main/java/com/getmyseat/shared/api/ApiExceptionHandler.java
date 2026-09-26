package com.getmyseat.shared.api;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

/**
 * Turns every error the API produces into an RFC 9457 {@code ProblemDetail} with a stable {@link ProblemTypes
 * type}. Security failures raised in the filter chain are routed here too, so there is one error format.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

	/** A single field that failed validation, as it appears in the {@code errors} array. */
	public record FieldProblem(String field, String message) {
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex, HttpServletResponse response) {
		return unauthorized(response);
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletResponse response) {
		if (this.trustResolver.isAnonymous(SecurityContextHolder.getContext().getAuthentication())) {
			return unauthorized(response);
		}
		return problem(HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "You don't have permission to do this.");
	}

	@ExceptionHandler(NotFoundException.class)
	ResponseEntity<ProblemDetail> handleNotFound(NotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(ConflictException.class)
	ResponseEntity<ProblemDetail> handleConflict(ConflictException ex) {
		ProblemDetail problem = problemDetail(HttpStatus.CONFLICT, ex.type(), ex.getMessage());
		ex.properties().forEach(problem::setProperty);
		return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
	}

	@ExceptionHandler(UpstreamUnavailableException.class)
	ResponseEntity<ProblemDetail> handleUpstreamUnavailable(UpstreamUnavailableException ex) {
		log.warn("Upstream unavailable: {}", ex.getMessage(), ex);
		return problem(HttpStatus.SERVICE_UNAVAILABLE, ProblemTypes.UPSTREAM_UNAVAILABLE, ex.getMessage());
	}

	@ExceptionHandler(InvalidRequestException.class)
	ResponseEntity<ProblemDetail> handleInvalidRequest(InvalidRequestException ex) {
		return validationProblem(List.of(new FieldProblem(ex.field(), ex.getMessage())));
	}

	/** Method validation on a service, outside Spring MVC's own parameter validation. */
	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
		List<FieldProblem> errors = new ArrayList<>();
		for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
			String field = null;
			for (Path.Node node : violation.getPropertyPath()) {
				field = node.getName();
			}
			errors.add(new FieldProblem(field, violation.getMessage()));
		}
		return validationProblem(errors);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR,
				"Something went wrong on our side. Please try again later.");
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldProblem> errors = new ArrayList<>();
		ex.getBindingResult()
			.getFieldErrors()
			.forEach(error -> errors.add(new FieldProblem(error.getField(), error.getDefaultMessage())));
		ex.getBindingResult()
			.getGlobalErrors()
			.forEach(error -> errors.add(new FieldProblem(error.getObjectName(), error.getDefaultMessage())));
		return asObject(validationProblem(errors));
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
			HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldProblem> errors = new ArrayList<>();
		ex.getParameterValidationResults().forEach(result -> {
			if (result instanceof ParameterErrors parameterErrors) {
				for (FieldError error : parameterErrors.getFieldErrors()) {
					errors.add(new FieldProblem(error.getField(), error.getDefaultMessage()));
				}
				return;
			}
			String field = fieldName(result.getMethodParameter());
			result.getResolvableErrors().forEach(error -> errors.add(new FieldProblem(field, error.getDefaultMessage())));
		});
		return asObject(validationProblem(errors));
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		String field = (ex.getPropertyName() != null) ? ex.getPropertyName() : "request";
		return asObject(validationProblem(List.of(new FieldProblem(field, "has an invalid value"))));
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleMissingServletRequestParameter(
			MissingServletRequestParameterException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return asObject(validationProblem(List.of(new FieldProblem(ex.getParameterName(), "is required"))));
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		if (ex instanceof MissingRequestHeaderException missing) {
			return asObject(validationProblem(List.of(new FieldProblem(missing.getHeaderName(), "is required"))));
		}
		return super.handleServletRequestBindingException(ex, headers, status, request);
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return asObject(problem(HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_REQUEST,
				"The request body is missing or isn't valid JSON."));
	}

	/** Gives the Spring MVC exceptions the base class handles (unknown route, wrong method...) our type URIs. */
	@Override
	protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		if (body instanceof ProblemDetail problem
				&& (problem.getType() == null || URI.create("about:blank").equals(problem.getType()))) {
			URI type = switch (statusCode.value()) {
				case 400 -> ProblemTypes.MALFORMED_REQUEST;
				case 404 -> ProblemTypes.NOT_FOUND;
				case 405 -> ProblemTypes.METHOD_NOT_ALLOWED;
				default -> null;
			};
			if (type != null) {
				problem.setType(type);
			}
			if (statusCode.value() == 404) {
				problem.setDetail("No such resource.");
			}
		}
		return super.createResponseEntity(body, headers, statusCode, request);
	}

	/** A request parameter or header by the name the client sends, else the Java parameter's name. */
	private static @Nullable String fieldName(MethodParameter parameter) {
		RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
		if (requestParam != null && !requestParam.name().isEmpty()) {
			return requestParam.name();
		}
		RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
		if (requestHeader != null && !requestHeader.name().isEmpty()) {
			return requestHeader.name();
		}
		return parameter.getParameterName();
	}

	private ResponseEntity<ProblemDetail> unauthorized(HttpServletResponse response) {
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.UNAUTHORIZED);
		if (!response.containsHeader(HttpHeaders.WWW_AUTHENTICATE)) {
			builder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		}
		return builder.body(problemDetail(HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHORIZED,
				"Sign in and send a valid bearer access token."));
	}

	private static ResponseEntity<ProblemDetail> validationProblem(List<FieldProblem> errors) {
		ProblemDetail problem = problemDetail(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION,
				"The request has invalid fields.");
		problem.setTitle("Validation failed");
		problem.setProperty("errors", errors);
		return ResponseEntity.badRequest().body(problem);
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, URI type, String detail) {
		return ResponseEntity.status(status).body(problemDetail(status, type, detail));
	}

	private static ProblemDetail problemDetail(HttpStatus status, URI type, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(type);
		return problem;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static ResponseEntity<Object> asObject(ResponseEntity<ProblemDetail> entity) {
		return (ResponseEntity) entity;
	}

}
