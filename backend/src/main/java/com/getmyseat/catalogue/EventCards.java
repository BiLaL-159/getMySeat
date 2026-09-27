package com.getmyseat.catalogue;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.getmyseat.catalogue.EventCard.NextShow;
import com.getmyseat.catalogue.ShowBrowseResponses.Price;

/**
 * What search results show of each Event's upcoming Shows, for a whole page of Events in one query. Plain SQL, as
 * picking the soonest Show per Event is {@code DISTINCT ON}.
 */
@Repository
class EventCards {

	/** An Event's soonest matching Show, and the lowest Section Price across all its matching Shows. */
	record Browse(NextShow nextShow, Price lowestPrice) {
	}

	private final JdbcTemplate jdbc;

	EventCards(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Considers the published Shows starting after {@code now} that match the filters' city and dates, as
	 * {@link EventRepository#published} does.
	 * @return by Event id; Events without such a Show are left out
	 */
	Map<UUID, Browse> of(Collection<UUID> events, EventRepository.Filters filters, Instant now) {
		if (events.isEmpty()) {
			return Map.of();
		}
		StringBuilder where = new StringBuilder("""
				s.event_id = ANY (?) AND s.status = 'PUBLISHED' AND s.starts_at > ?""");
		List<Object> parameters = new ArrayList<>(List.of(Timestamp.from(now)));
		String city = filters.city();
		if (city != null && !city.isBlank()) {
			where.append(" AND lower(v.city) = ?");
			parameters.add(city.strip().toLowerCase(Locale.ROOT));
		}
		// timezone(zone, timestamptz) gives the wall-clock time at the Venue.
		LocalDate from = filters.from();
		if (from != null) {
			where.append(" AND timezone(v.time_zone, s.starts_at) >= ?");
			parameters.add(from.atStartOfDay());
		}
		LocalDate to = filters.to();
		if (to != null) {
			where.append(" AND timezone(v.time_zone, s.starts_at) < ?");
			parameters.add(to.plusDays(1).atStartOfDay());
		}
		String sql = """
				WITH upcoming AS (
				    SELECT s.id, s.event_id, s.starts_at, v.name AS venue_name, v.city, v.time_zone
				    FROM show s JOIN venue v ON v.id = s.venue_id
				    WHERE %s
				), lowest AS (
				    -- Every Section Price is in INR, so the cheapest amount is the cheapest price.
				    SELECT u.event_id, min(p.amount_paise) AS amount_paise, min(p.currency) AS currency
				    FROM upcoming u JOIN section_price p ON p.show_id = u.id
				    GROUP BY u.event_id
				)
				SELECT DISTINCT ON (u.event_id) u.event_id, u.id, u.starts_at, u.venue_name, u.city, u.time_zone,
				    l.amount_paise, l.currency
				FROM upcoming u JOIN lowest l ON l.event_id = u.event_id
				ORDER BY u.event_id, u.starts_at, u.id
				""".formatted(where);
		Map<UUID, Browse> browse = new HashMap<>();
		this.jdbc.query(connection -> {
			PreparedStatement statement = connection.prepareStatement(sql);
			statement.setArray(1, connection.createArrayOf("uuid", events.toArray()));
			for (int i = 0; i < parameters.size(); i++) {
				statement.setObject(i + 2, parameters.get(i));
			}
			return statement;
		}, (row) -> {
			browse.put(row.getObject("event_id", UUID.class),
					new Browse(
							new NextShow(row.getObject("id", UUID.class), row.getTimestamp("starts_at").toInstant(),
									row.getString("venue_name"), row.getString("city"), row.getString("time_zone")),
							new Price(row.getLong("amount_paise"), row.getString("currency"))));
		});
		return browse;
	}

}
