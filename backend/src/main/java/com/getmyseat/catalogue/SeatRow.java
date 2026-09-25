package com.getmyseat.catalogue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;

import com.getmyseat.shared.api.InvalidRequestException;

/**
 * A row of Seats as an Organizer defines it: a row label plus either a seat count (Seats 1 to n) or an explicit
 * list of seat numbers, for rows with gaps or that don't start at 1.
 *
 * @param label the row label, 1 to 3 letters; stored in upper case
 * @param seatCount how many Seats, numbered from 1
 * @param seatNumbers the exact seat numbers, in order
 */
record SeatRow(String label, @Nullable Integer seatCount, @Nullable List<Integer> seatNumbers) {

	static final int MAX_SEATS_PER_ROW = 500;

	static final int MAX_SEAT_NUMBER = 9999;

	/**
	 * The seat numbers this row defines, in order.
	 * @throws InvalidRequestException for the {@code rows} field if the definition is invalid
	 */
	List<Integer> numbers() {
		if (this.label == null || !normalizedLabel().matches("[A-Z]{1,3}")) {
			throw invalid("Row labels are 1 to 3 letters, such as A or BB.");
		}
		if ((this.seatCount == null) == (this.seatNumbers == null)) {
			throw invalid("Give row " + this.label + " either a seatCount or a list of seatNumbers, not both.");
		}
		if (this.seatCount != null) {
			if (this.seatCount < 1 || this.seatCount > MAX_SEATS_PER_ROW) {
				throw invalid("Row " + this.label + " needs between 1 and " + MAX_SEATS_PER_ROW + " Seats.");
			}
			return IntStream.rangeClosed(1, this.seatCount).boxed().toList();
		}
		List<Integer> numbers = this.seatNumbers;
		if (numbers.isEmpty() || numbers.size() > MAX_SEATS_PER_ROW) {
			throw invalid("Row " + this.label + " needs between 1 and " + MAX_SEATS_PER_ROW + " Seats.");
		}
		Set<Integer> unique = new LinkedHashSet<>();
		for (Integer number : numbers) {
			if (number == null || number < 1 || number > MAX_SEAT_NUMBER) {
				throw invalid("Seat numbers in row " + this.label + " must be between 1 and " + MAX_SEAT_NUMBER + ".");
			}
			if (!unique.add(number)) {
				throw invalid("Seat " + this.label + number + " is listed twice.");
			}
		}
		return new ArrayList<>(unique);
	}

	String normalizedLabel() {
		return this.label.strip().toUpperCase(Locale.ROOT);
	}

	private static InvalidRequestException invalid(String message) {
		return new InvalidRequestException("rows", message);
	}

}
