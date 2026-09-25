package com.getmyseat.booking;

import java.util.HashMap;
import java.util.Map;
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
