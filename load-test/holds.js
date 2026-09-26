// k6 load test: hundreds of Customers race for the same Seats and General Admission places at one Show.
//
// Setup creates a pool of Customer accounts in the dev Keycloak, signs them in, and publishes a small Show
// through the API as the seed Organizer and Admin. Each virtual user is one Customer: it reads availability,
// tries to hold a few of the Seats or places it saw as free with a fresh Idempotency-Key (or, once everything
// is taken, Seats from its stale view), and then keeps the Hold, retries the request with the same key, or
// releases the Hold so the race goes on. A new Hold also releases the Customer's previous one. Teardown reads every Customer's active Hold and the Show's
// availability and checks that no Seat is held twice and no General Admission Section is oversold.
//
// Run against `docker compose up` with the default Hold time: the post-run check assumes no Hold expires during
// the run. See the README. DEV ONLY: it signs in with the seed passwords.

import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const API = `${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1`;
const KEYCLOAK = __ENV.KEYCLOAK_URL || 'http://localhost:8180';
// The backend only accepts tokens issued by http://localhost:8180. Inside the compose network Keycloak is
// keycloak:8080, so ask for tokens as if from the host; Keycloak derives the issuer from the Host header.
const ISSUER_HOST = __ENV.KEYCLOAK_ISSUER_HOST;
const CUSTOMERS = Number(__ENV.CUSTOMERS || 300);
const SEATED_ROWS = ['A', 'B', 'C', 'D', 'E'];
const SEATS_PER_ROW = 10;
const GENERAL_ADMISSION_CAPACITY = 200;

const holdsCreated = new Counter('holds_created');
const holdsUnavailable = new Counter('holds_unavailable');
const holdsReplayed = new Counter('holds_replayed');
const holdsReleased = new Counter('holds_released');
const releaseRetries = new Counter('release_retries');
const serverErrors = new Counter('server_errors');
const invariantViolations = new Counter('invariant_violations');

export const options = {
	setupTimeout: '5m',
	teardownTimeout: '5m',
	scenarios: {
		race: {
			executor: 'ramping-vus',
			startVUs: 0,
			stages: [
				{ duration: '15s', target: CUSTOMERS / 3 },
				{ duration: '15s', target: CUSTOMERS },
				{ duration: '60s', target: CUSTOMERS },
				{ duration: '15s', target: 0 },
			],
			gracefulRampDown: '10s',
		},
	},
	// A 409 is the API saying "someone else got there first", not a failure.
	thresholds: {
		server_errors: ['count==0'],
		invariant_violations: ['count==0'],
		checks: ['rate==1'],
		// Always true; listed so the summary breaks latency down per endpoint.
		'http_req_duration{name:POST /shows/{id}/holds}': ['max>=0'],
		'http_req_duration{name:GET /shows/{id}/availability}': ['max>=0'],
		'http_req_duration{name:POST /holds/{id}/release}': ['max>=0'],
	},
	summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 404, 409));

export function setup() {
	const customers = createCustomers();
	const organizer = signIn('organizer');
	const admin = signIn('platform-admin');
	const show = publishShow(organizer, admin);
	console.log(`Show ${show.id}: ${show.seats.length} Seats and ${GENERAL_ADMISSION_CAPACITY} General Admission places`);
	return { show, customers: signInAll(customers) };
}

export default function (data) {
	const token = data.customers[(exec.vu.idInTest - 1) % data.customers.length];
	const show = data.show.id;

	const availability = call('GET', `/shows/${show}/availability`, null, 'GET /shows/{id}/availability');
	if (!check(availability, { 'availability is 200': (r) => r.status === 200 })) {
		return;
	}
	const request = pickHoldRequest(availability.json('sections'));

	const key = `${exec.vu.idInTest}-${exec.vu.iterationInScenario}-${Date.now()}`;
	const hold = createHold(show, token, key, request);
	const unavailable = hold.status === 409 && hold.json('type') === 'urn:getmyseat:problem:inventory-unavailable';
	check(hold, { 'hold is 201, or 409 naming what is unavailable': (r) => r.status === 201 || unavailable });
	if (unavailable) {
		holdsUnavailable.add(1);
	}
	if (hold.status !== 201) {
		sleep(Math.random() * 0.5);
		return;
	}
	holdsCreated.add(1);

	const roll = Math.random();
	if (roll < 0.2) {
		// A client retrying after a lost response gets the same Hold back and claims nothing more.
		const replay = createHold(show, token, key, request);
		holdsReplayed.add(1);
		check(replay, {
			'replay is 201 with the same Hold': (r) => r.status === 201 && r.json('id') === hold.json('id'),
		});
	}
	else if (roll < 0.6) {
		const release = releaseHold(hold.json('id'), token);
		if (check(release, { 'release is 200': (r) => r.status === 200 })) {
			holdsReleased.add(1);
		}
	}
	sleep(Math.random() * 0.5);
}

export function teardown(data) {
	const show = data.show.id;
	const tokens = signInAll(data.customers.map((_, i) => customerName(i)));

	const seatOwners = new Map();
	const heldPlaces = new Map();
	let activeHolds = 0;
	for (let i = 0; i < tokens.length; i += 25) {
		const responses = http.batch(tokens.slice(i, i + 25).map((token) => ({
			method: 'GET',
			url: `${API}/shows/${show}/holds/mine`,
			params: { headers: { Authorization: `Bearer ${token}` }, tags: { name: 'GET /shows/{id}/holds/mine' } },
		})));
		responses.forEach((r, j) => {
			recordServerError(r);
			check(r, { 'holds/mine is 200 or 404': (res) => res.status === 200 || res.status === 404 });
			if (r.status !== 200) {
				return;
			}
			activeHolds++;
			for (const item of r.json('items')) {
				if (item.kind === 'SEAT') {
					if (seatOwners.has(item.seatId)) {
						violation(`Seat ${item.seatId} is in two active Holds`);
					}
					seatOwners.set(item.seatId, i + j);
				}
				else {
					heldPlaces.set(item.sectionId, (heldPlaces.get(item.sectionId) || 0) + item.quantity);
				}
			}
		});
	}

	const availability = call('GET', `/shows/${show}/availability`, null, 'GET /shows/{id}/availability');
	let heldSeats = 0;
	let placesLeft = 0;
	for (const section of availability.json('sections')) {
		if (section.kind === 'SEATED') {
			for (const seat of section.seats) {
				if (seat.available === seatOwners.has(seat.id)) {
					violation(`Seat ${seat.id} is ${seat.available ? 'available but held' : 'unavailable but not held'}`);
				}
				heldSeats += seat.available ? 0 : 1;
			}
		}
		else {
			const held = heldPlaces.get(section.id) || 0;
			placesLeft = section.available;
			if (section.available < 0 || section.available + held !== section.capacity) {
				violation(`Section ${section.id}: ${section.available} left + ${held} held != ${section.capacity}`);
			}
		}
	}
	console.log(`After the run: ${activeHolds} active Holds, ${heldSeats} of ${data.show.seats.length} Seats held,`
		+ ` ${placesLeft} of ${GENERAL_ADMISSION_CAPACITY} General Admission places left`);
}

function pickHoldRequest(sections) {
	const all = sections.filter((s) => s.kind === 'SEATED').flatMap((s) => s.seats);
	const free = all.filter((s) => s.available);
	// Once every Seat is taken, try anyway, like a client whose seat map is a moment out of date.
	const seats = (free.length > 0) ? free : all;
	const generalAdmission = sections.find((s) => s.kind === 'GENERAL_ADMISSION');
	const want = 1 + Math.floor(Math.random() * 4);
	const roll = Math.random();
	const request = { seats: [], generalAdmission: [] };
	if (roll < 0.6) {
		// Everyone who looked at the same availability picks among the same free Seats, so they collide.
		request.seats = shuffle(seats).slice(0, want).map((s) => s.id);
	}
	else if (roll < 0.9) {
		request.generalAdmission = [{ sectionId: generalAdmission.id, quantity: want }];
	}
	else {
		request.seats = shuffle(seats).slice(0, 2).map((s) => s.id);
		request.generalAdmission = [{ sectionId: generalAdmission.id, quantity: want }];
	}
	return request;
}

function createHold(show, token, key, request) {
	return call('POST', `/shows/${show}/holds`, request, 'POST /shows/{id}/holds', token, { 'Idempotency-Key': key });
}

/** Releases a Hold, retrying when the database aborted it for a deadlock with Holds being made. */
function releaseHold(id, token) {
	for (let attempt = 1; ; attempt++) {
		const release = call('POST', `/holds/${id}/release`, null, 'POST /holds/{id}/release', token);
		if (attempt === 3 || release.status !== 409 || release.json('type') !== 'urn:getmyseat:problem:conflict') {
			return release;
		}
		releaseRetries.add(1);
	}
}

function publishShow(organizer, admin) {
	const venue = expect(call('POST', '/venues', {
		name: `k6 Arena ${Date.now()}`, address: 'Load test', city: 'Mumbai', timeZone: 'Asia/Kolkata',
	}, null, organizer), 201).json('id');
	const stalls = expect(call('POST', `/venues/${venue}/sections`, {
		name: 'Stalls', kind: 'SEATED', rows: SEATED_ROWS.map((label) => ({ label, seatCount: SEATS_PER_ROW })),
	}, null, organizer), 201).json('id');
	const generalAdmission = expect(call('POST', `/venues/${venue}/sections`, {
		name: 'Fan Pit', kind: 'GENERAL_ADMISSION', capacity: GENERAL_ADMISSION_CAPACITY,
	}, null, organizer), 201).json('id');
	expect(call('POST', `/venues/${venue}/submit`, null, null, organizer), 200);
	expect(call('POST', `/admin/venues/${venue}/approve`, null, null, admin), 200);

	const event = expect(call('POST', '/events', {
		title: 'k6: Hold race', description: 'A Show for the load test.', category: 'MUSIC', language: 'en',
	}, null, organizer), 201).json('id');
	expect(call('POST', `/events/${event}/publish`, null, null, organizer), 200);
	const startsAt = new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString();
	const show = expect(call('POST', `/events/${event}/shows`, { venueId: venue, startsAt }, null, organizer), 201)
		.json('id');
	expect(call('PUT', `/shows/${show}/prices`, { prices: [
		{ sectionId: stalls, amountPaise: 450000, currency: 'INR' },
		{ sectionId: generalAdmission, amountPaise: 250000, currency: 'INR' },
	] }, null, organizer), 200);
	expect(call('POST', `/shows/${show}/publish`, null, null, organizer), 200);

	const sections = expect(call('GET', `/shows/${show}/availability`), 200).json('sections');
	return { id: show, seats: sections.flatMap((s) => s.seats.map((seat) => seat.id)) };
}

/** Creates the Customer accounts, or reuses them from an earlier run. Everyone in the realm gets CUSTOMER. */
function createCustomers() {
	const token = http.post(`${KEYCLOAK}/realms/master/protocol/openid-connect/token`, {
		grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: 'admin',
	});
	expect(token, 200);
	const headers = { Authorization: `Bearer ${token.json('access_token')}`, 'Content-Type': 'application/json' };
	const names = [];
	for (let i = 0; i < CUSTOMERS; i++) {
		names.push(customerName(i));
	}
	for (let i = 0; i < names.length; i += 50) {
		http.batch(names.slice(i, i + 50).map((name) => ({
			method: 'POST',
			url: `${KEYCLOAK}/admin/realms/getmyseat/users`,
			body: JSON.stringify({
				username: name, email: `${name}@example.com`, firstName: 'Load', lastName: 'Test', enabled: true,
				emailVerified: true, credentials: [{ type: 'password', value: 'password', temporary: false }],
			}),
			params: { headers, responseCallback: http.expectedStatuses(201, 409) },
		}))).forEach((r) => expect(r, 201, 409));
	}
	return names;
}

function customerName(i) {
	return `k6-customer-${String(i + 1).padStart(4, '0')}`;
}

function signIn(username) {
	return signInAll([username])[0];
}

function signInAll(usernames) {
	const tokens = [];
	for (let i = 0; i < usernames.length; i += 25) {
		http.batch(usernames.slice(i, i + 25).map((username) => ({
			method: 'POST',
			url: `${KEYCLOAK}/realms/getmyseat/protocol/openid-connect/token`,
			body: { grant_type: 'password', client_id: 'getmyseat-dev-cli', username, password: 'password' },
			params: { headers: ISSUER_HOST ? { Host: ISSUER_HOST } : {} },
		}))).forEach((r) => tokens.push(expect(r, 200).json('access_token')));
	}
	return tokens;
}

function call(method, path, body, name, token, extraHeaders) {
	const headers = Object.assign({ 'Content-Type': 'application/json' }, extraHeaders);
	if (token) {
		headers.Authorization = `Bearer ${token}`;
	}
	const params = { headers, tags: name ? { name } : {} };
	const response = http.request(method, `${API}${path}`, body === null || body === undefined ? null : JSON.stringify(body), params);
	recordServerError(response);
	return response;
}

function recordServerError(response) {
	if (response.status >= 500 || response.status === 0) {
		serverErrors.add(1);
		console.error(`${response.request.method} ${response.request.url}: ${response.status} ${response.body}`);
	}
}

function expect(response, ...statuses) {
	if (!statuses.includes(response.status)) {
		fail(`${response.request.method} ${response.request.url}: ${response.status} ${response.body}`);
	}
	return response;
}

function violation(message) {
	invariantViolations.add(1);
	console.error(message);
	exec.test.fail(message);
}

function shuffle(items) {
	const copy = items.slice();
	for (let i = copy.length - 1; i > 0; i--) {
		const j = Math.floor(Math.random() * (i + 1));
		[copy[i], copy[j]] = [copy[j], copy[i]];
	}
	return copy;
}
