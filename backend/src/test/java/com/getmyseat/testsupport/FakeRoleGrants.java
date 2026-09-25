package com.getmyseat.testsupport;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jspecify.annotations.Nullable;

import com.getmyseat.access.Role;
import com.getmyseat.access.RoleGrantException;
import com.getmyseat.access.RoleGrants;

/**
 * Stands in for Keycloak's role granting in API tests: records each grant, and can be told to fail or to run a
 * hook while a grant is in flight. Call {@link #reset()} before each test.
 */
public final class FakeRoleGrants implements RoleGrants {

	public record Grant(UUID subject, Role role) {
	}

	private final List<Grant> grants = new CopyOnWriteArrayList<>();

	private volatile RoleGrantException.@Nullable Reason failure;

	private volatile Runnable duringGrant = () -> {
	};

	@Override
	public void grant(UUID subject, Role role) {
		this.duringGrant.run();
		RoleGrantException.Reason reason = this.failure;
		if (reason != null) {
			throw new RoleGrantException(reason, "Keycloak failed (fake): " + reason,
					new IllegalStateException("fake failure"));
		}
		Grant grant = new Grant(subject, role);
		if (!this.grants.contains(grant)) {
			this.grants.add(grant);
		}
	}

	public List<Grant> grants() {
		return List.copyOf(this.grants);
	}

	/** Makes every grant fail as if Keycloak were unreachable, or succeed again with {@code false}. */
	public void failing(boolean failing) {
		this.failure = failing ? RoleGrantException.Reason.UNAVAILABLE : null;
	}

	public void failWith(RoleGrantException.Reason reason) {
		this.failure = reason;
	}

	/** Runs {@code hook} inside every grant, before it succeeds or fails. */
	public void duringGrant(Runnable hook) {
		this.duringGrant = hook;
	}

	public void reset() {
		this.grants.clear();
		this.failure = null;
		this.duringGrant = () -> {
		};
	}

}
