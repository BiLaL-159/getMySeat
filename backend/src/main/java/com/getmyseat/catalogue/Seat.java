package com.getmyseat.catalogue;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One numbered Seat in a Seated Section, labelled by row and number (such as {@code A12}). Its id is stable, so
 * Phase 2 inventory can reference it.
 */
@Entity
@Table(name = "seat")
class Seat {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "section_id")
	private Section section;

	private String rowLabel;

	@Column(name = "seat_number")
	private int number;

	private int position;

	protected Seat() {
	}

	Seat(Section section, String rowLabel, int number, int position) {
		this.id = UUID.randomUUID();
		this.section = section;
		this.rowLabel = rowLabel;
		this.number = number;
		this.position = position;
	}

	UUID id() {
		return this.id;
	}

	String rowLabel() {
		return this.rowLabel;
	}

	int number() {
		return this.number;
	}

	int position() {
		return this.position;
	}

	String label() {
		return this.rowLabel + this.number;
	}

}
