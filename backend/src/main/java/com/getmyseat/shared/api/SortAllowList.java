package com.getmyseat.shared.api;

import java.util.Set;
import java.util.TreeSet;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * The properties an endpoint lets clients sort by. Checking {@code sort} against it keeps clients from
 * sorting by internal or unindexed columns.
 */
public final class SortAllowList {

	private final Set<String> properties;

	private SortAllowList(Set<String> properties) {
		this.properties = properties;
	}

	public static SortAllowList of(String... properties) {
		return new SortAllowList(new TreeSet<>(Set.of(properties)));
	}

	/**
	 * Returns the pageable unchanged if every sort property is allowed.
	 * @throws InvalidRequestException for the {@code sort} field otherwise
	 */
	public Pageable check(Pageable pageable) {
		for (Sort.Order order : pageable.getSort()) {
			if (!this.properties.contains(order.getProperty())) {
				throw new InvalidRequestException("sort", "Can't sort by '" + order.getProperty()
						+ "'. Sort by one of: " + String.join(", ", this.properties) + ".");
			}
		}
		return pageable;
	}

}
