package com.getmyseat.catalogue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;
import org.jspecify.annotations.Nullable;

import com.getmyseat.shared.api.InvalidRequestException;
import com.getmyseat.shared.api.NotFoundException;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * A part of a Venue sold as a unit of pricing. Seated Sections have numbered Seats; General Admission Sections
 * have a capacity and no Seats.
 */
@Entity
@Table(name = "section")
class Section {

	enum Kind {

		SEATED, GENERAL_ADMISSION

	}

	static final int MAX_SEATS = 10_000;

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "venue_id")
	private Venue venue;

	private String name;

	@Enumerated(EnumType.STRING)
	private Kind kind;

	private @Nullable Integer capacity;

	private int position;

	@OneToMany(mappedBy = "section", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("position")
	@BatchSize(size = 50)
	private List<Seat> seats = new ArrayList<>();

	protected Section() {
	}

	Section(Venue venue, String name, Kind kind, @Nullable Integer capacity, int position) {
		this.id = UUID.randomUUID();
		this.venue = venue;
		this.name = name;
		this.kind = kind;
		this.position = position;
		changeCapacity(capacity);
	}

	void rename(String name) {
		this.name = name;
	}

	void changeCapacity(@Nullable Integer capacity) {
		if (this.kind == Kind.GENERAL_ADMISSION && (capacity == null || capacity < 1)) {
			throw new InvalidRequestException("capacity", "A General Admission Section needs a capacity above zero.");
		}
		if (this.kind == Kind.SEATED && capacity != null) {
			throw new InvalidRequestException("capacity",
					"A Seated Section has no capacity; it holds as many people as it has Seats.");
		}
		this.capacity = capacity;
	}

	void addRows(List<SeatRow> rows) {
		if (this.kind != Kind.SEATED) {
			throw new InvalidRequestException("rows", "A General Admission Section has no Seats.");
		}
		Set<String> labels = new HashSet<>();
		this.seats.forEach(seat -> labels.add(seat.label()));
		List<Seat> added = new ArrayList<>();
		int position = this.seats.stream().mapToInt(Seat::position).max().orElse(-1) + 1;
		for (SeatRow row : rows) {
			List<Integer> numbers = row.numbers();
			String rowLabel = row.normalizedLabel();
			for (int number : numbers) {
				Seat seat = new Seat(this, rowLabel, number, position++);
				if (!labels.add(seat.label())) {
					throw new InvalidRequestException("rows",
							"Seat " + seat.label() + " already exists in Section " + this.name + ".");
				}
				added.add(seat);
			}
		}
		if (this.seats.size() + added.size() > MAX_SEATS) {
			throw new InvalidRequestException("rows", "A Section can have at most " + MAX_SEATS + " Seats.");
		}
		this.seats.addAll(added);
	}

	void removeSeat(UUID seatId) {
		if (!this.seats.removeIf(seat -> seat.id().equals(seatId))) {
			throw new NotFoundException("No Seat with that id in this Section.");
		}
	}

	UUID id() {
		return this.id;
	}

	String name() {
		return this.name;
	}

	Kind kind() {
		return this.kind;
	}

	int position() {
		return this.position;
	}

	@Nullable Integer capacity() {
		return this.capacity;
	}

	List<Seat> seats() {
		return List.copyOf(this.seats);
	}

}
