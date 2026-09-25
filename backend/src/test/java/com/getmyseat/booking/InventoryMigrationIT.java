package com.getmyseat.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The inventory migration, against the real schema: it backfills Shows published before Phase 2, and its
 * constraints hold whatever the application does. Neither can be reached through the API, so this test uses SQL.
 */
class InventoryMigrationIT {

	static final PostgreSQLContainer postgres = new PostgreSQLContainer(
			DockerImageName.parse("postgres:17.11-alpine"));

	static JdbcClient sql;

	static UUID published = UUID.randomUUID();

	static UUID draft = UUID.randomUUID();

	static UUID seated = UUID.randomUUID();

	static UUID standing = UUID.randomUUID();

	static List<UUID> seats = List.of(UUID.randomUUID(), UUID.randomUUID());

	@BeforeAll
	static void migrateWithShowsPublishedBeforeInventory() {
		postgres.start();
		DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword());
		sql = JdbcClient.create(dataSource);
		Flyway.configure().dataSource(dataSource).target("5").load().migrate();

		UUID venue = UUID.randomUUID();
		UUID event = UUID.randomUUID();
		sql.sql("""
				INSERT INTO venue (id, owner_subject, name, address, city, time_zone, status, decided_by, decided_at,
				  created_at, version)
				VALUES (?, ?, 'Hall', '1 Main Road', 'Mumbai', 'Asia/Kolkata', 'APPROVED', ?, now(), now(), 0)
				""").params(venue, UUID.randomUUID(), UUID.randomUUID()).update();
		sql.sql("INSERT INTO section (id, venue_id, name, kind, capacity, position) VALUES (?, ?, 'Stalls', 'SEATED', NULL, 0)")
			.params(seated, venue)
			.update();
		sql.sql("INSERT INTO section (id, venue_id, name, kind, capacity, position) VALUES (?, ?, 'Standing', 'GENERAL_ADMISSION', 300, 1)")
			.params(standing, venue)
			.update();
		for (int i = 0; i < seats.size(); i++) {
			sql.sql("INSERT INTO seat (id, section_id, row_label, seat_number, position) VALUES (?, ?, 'A', ?, ?)")
				.params(seats.get(i), seated, i + 1, i)
				.update();
		}
		sql.sql("""
				INSERT INTO event (id, owner_subject, title, description, category, language, status, created_at,
				  published_at, version)
				VALUES (?, ?, 'Gig', 'A gig.', 'MUSIC', 'en', 'PUBLISHED', now(), now(), 1)
				""").params(event, UUID.randomUUID()).update();
		sql.sql("""
				INSERT INTO show (id, event_id, venue_id, starts_at, status, created_at, published_at, version)
				VALUES (?, ?, ?, now() + interval '7 days', 'PUBLISHED', now(), now(), 1)
				""").params(published, event, venue).update();
		sql.sql("""
				INSERT INTO show (id, event_id, venue_id, starts_at, status, created_at, version)
				VALUES (?, ?, ?, now() + interval '7 days', 'DRAFT', now(), 0)
				""").params(draft, event, venue).update();

		Flyway.configure().dataSource(dataSource).load().migrate();
	}

	@AfterAll
	static void stop() {
		postgres.stop();
	}

	@Test
	void aShowPublishedBeforeInventoryGetsEverySeatAvailable() {
		List<Map<String, Object>> rows = sql.sql("SELECT seat_id, status, hold_id FROM seat_inventory WHERE show_id = ?")
			.param(published)
			.query()
			.listOfRows();

		assertThat(rows).extracting(row -> row.get("seat_id")).containsExactlyInAnyOrderElementsOf(seats);
		assertThat(rows).extracting(row -> row.get("status")).containsOnly("AVAILABLE");
		assertThat(rows).extracting(row -> row.get("hold_id")).containsOnlyNulls();
	}

	@Test
	void aShowPublishedBeforeInventoryGetsEveryGeneralAdmissionPlaceLeft() {
		Map<String, Object> row = sql
			.sql("SELECT section_id, capacity, available FROM general_admission_inventory WHERE show_id = ?")
			.param(published)
			.query()
			.singleRow();

		assertThat(row).containsEntry("section_id", standing).containsEntry("capacity", 300).containsEntry("available", 300);
	}

	@Test
	void aDraftShowGetsNoInventory() {
		assertThat(count("seat_inventory", draft)).isZero();
		assertThat(count("general_admission_inventory", draft)).isZero();
	}

	@Test
	void generalAdmissionAvailabilityStaysBetweenZeroAndCapacity() {
		UUID show = UUID.randomUUID();
		UUID section = UUID.randomUUID();
		sql.sql("INSERT INTO general_admission_inventory VALUES (?, ?, 10, 10)").params(show, section).update();

		assertViolation("UPDATE general_admission_inventory SET available = -1 WHERE show_id = ?", show);
		assertViolation("UPDATE general_admission_inventory SET available = 11 WHERE show_id = ?", show);
		sql.sql("UPDATE general_admission_inventory SET available = 0 WHERE show_id = ?").param(show).update();
	}

	@Test
	void aSeatHasAHoldExactlyWhenItIsHeld() {
		UUID show = UUID.randomUUID();
		UUID seat = UUID.randomUUID();
		sql.sql("INSERT INTO seat_inventory (show_id, seat_id, status) VALUES (?, ?, 'AVAILABLE')")
			.params(show, seat)
			.update();

		assertViolation("UPDATE seat_inventory SET status = 'HELD' WHERE show_id = ?", show);
		assertViolation("UPDATE seat_inventory SET hold_id = gen_random_uuid() WHERE show_id = ?", show);
		assertViolation("UPDATE seat_inventory SET status = 'BOOKED' WHERE show_id = ?", show);
		sql.sql("UPDATE seat_inventory SET status = 'HELD', hold_id = gen_random_uuid() WHERE show_id = ?")
			.param(show)
			.update();
	}

	private static int count(String table, UUID show) {
		return sql.sql("SELECT count(*) FROM " + table + " WHERE show_id = ?").param(show).query(Integer.class).single();
	}

	private static void assertViolation(String update, UUID show) {
		assertThatThrownBy(() -> sql.sql(update).param(show).update())
			.isInstanceOf(DataIntegrityViolationException.class);
	}

}
