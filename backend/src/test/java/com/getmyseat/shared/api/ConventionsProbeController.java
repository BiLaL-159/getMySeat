package com.getmyseat.shared.api;

import java.util.List;
import java.util.stream.IntStream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Test-only endpoints that exercise the shared API conventions before any real feature uses them. */
@RestController
@RequestMapping("/api/v1/test-probe")
class ConventionsProbeController {

	static final int TOTAL_ITEMS = 250;

	private static final SortAllowList SORTABLE = SortAllowList.of("name", "createdAt");

	record ProbeRequest(@NotBlank String name, @Min(1) int quantity) {
	}

	@GetMapping("/admin-only")
	@PreAuthorize("hasRole('ADMIN')")
	String adminOnly() {
		return "ok";
	}

	@PostMapping("/validated")
	ProbeRequest validated(@Valid @RequestBody ProbeRequest request) {
		return request;
	}

	@GetMapping("/validated-param")
	int validatedParam(@RequestParam @Min(1) int quantity) {
		return quantity;
	}

	@GetMapping("/not-found")
	void notFound() {
		throw new NotFoundException("No Event with that id.");
	}

	@GetMapping("/conflict")
	void conflict() {
		throw new ConflictException("This application has already been decided.");
	}

	@GetMapping("/upstream-unavailable")
	void upstreamUnavailable() {
		throw new UpstreamUnavailableException("Keycloak is unavailable. Nothing was changed; try again.",
				new IllegalStateException("connection refused"));
	}

	@GetMapping("/boom")
	void boom() {
		throw new IllegalStateException("secret internal detail");
	}

	@GetMapping("/page")
	Page<String> page(Pageable pageable) {
		SORTABLE.check(pageable);
		List<String> items = IntStream.range(0, TOTAL_ITEMS).mapToObj(i -> "item-" + i).toList();
		int from = (int) Math.min(pageable.getOffset(), TOTAL_ITEMS);
		int to = Math.min(from + pageable.getPageSize(), TOTAL_ITEMS);
		return new PageImpl<>(items.subList(from, to), pageable, TOTAL_ITEMS);
	}

}
