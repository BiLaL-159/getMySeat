package com.getmyseat.booking;

import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.getmyseat.catalogue.SellableShow;
import com.getmyseat.catalogue.SellableShow.SellableSection;

/**
 * The inventory tables, in plain SQL: they are claimed with conditional updates (ADR 0003), not through entities.
 * Runs in the caller's transaction.
 */
@Repository
class ShowInventory {

	/** What's left in a General Admission Section at one Show. */
	record GeneralAdmissionLeft(int capacity, int available) {
	}

	private final JdbcTemplate jdbc;

	ShowInventory(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Every Seat available and every General Admission place left. */
	void create(SellableShow show) {
		for (SellableSection section : show.sections()) {
			switch (section.kind()) {
				case SEATED -> this.jdbc.batchUpdate(
						"INSERT INTO seat_inventory (show_id, seat_id, status) VALUES (?, ?, 'AVAILABLE')",
						section.seatIds(), 500, (statement, seat) -> {
							statement.setObject(1, show.id());
							statement.setObject(2, seat);
						});
				case GENERAL_ADMISSION -> this.jdbc.update(
						"INSERT INTO general_admission_inventory (show_id, section_id, capacity, available) VALUES (?, ?, ?, ?)",
						show.id(), section.id(), section.capacity(), section.capacity());
			}
		}
	}

	/**
	 * Marks those of the Show's Seats that are still available as held by the Hold. Takes the row locks in Seat id
	 * order, so overlapping Holds queue up rather than deadlock.
	 * @return the Seats that were claimed
	 */
	List<UUID> holdSeats(UUID show, UUID hold, Collection<UUID> seats) {
		if (seats.isEmpty()) {
			return List.of();
		}
		return this.jdbc.query(connection -> {
			PreparedStatement statement = connection.prepareStatement("""
					WITH claimable AS (
					    SELECT seat_id FROM seat_inventory
					    WHERE show_id = ? AND seat_id = ANY (?) AND status = 'AVAILABLE'
					    ORDER BY seat_id
					    FOR UPDATE
					)
					UPDATE seat_inventory SET status = 'HELD', hold_id = ?
					FROM claimable
					WHERE seat_inventory.show_id = ? AND seat_inventory.seat_id = claimable.seat_id
					RETURNING seat_inventory.seat_id
					""");
			statement.setObject(1, show);
			statement.setArray(2, connection.createArrayOf("uuid", seats.toArray()));
			statement.setObject(3, hold);
			statement.setObject(4, show);
			return statement;
		}, (row, rowNumber) -> row.getObject("seat_id", UUID.class));
	}

	/**
	 * Takes {@code quantity} places from the Show's General Admission Section, only if that many are left.
	 * @return empty if they were taken; otherwise how many places are left
	 */
	OptionalInt holdPlaces(UUID show, UUID section, int quantity) {
		int taken = this.jdbc.update(
				"UPDATE general_admission_inventory SET available = available - ? WHERE show_id = ? AND section_id = ? AND available >= ?",
				quantity, show, section, quantity);
		if (taken == 1) {
			return OptionalInt.empty();
		}
		return OptionalInt.of(this.jdbc.queryForObject(
				"SELECT available FROM general_admission_inventory WHERE show_id = ? AND section_id = ?", Integer.class,
				show, section));
	}

	/** Whether each of the Show's Seats is available, by Seat id. */
	Map<UUID, Boolean> seats(UUID show) {
		Map<UUID, Boolean> seats = new HashMap<>();
		this.jdbc.query("SELECT seat_id, status FROM seat_inventory WHERE show_id = ?",
				row -> {
					seats.put(row.getObject("seat_id", UUID.class), "AVAILABLE".equals(row.getString("status")));
				}, show);
		return seats;
	}

	/** The Show's General Admission inventory, by Section id. */
	Map<UUID, GeneralAdmissionLeft> generalAdmission(UUID show) {
		Map<UUID, GeneralAdmissionLeft> sections = new HashMap<>();
		this.jdbc.query("SELECT section_id, capacity, available FROM general_admission_inventory WHERE show_id = ?",
				row -> {
					sections.put(row.getObject("section_id", UUID.class),
							new GeneralAdmissionLeft(row.getInt("capacity"), row.getInt("available")));
				}, show);
		return sections;
	}

}
