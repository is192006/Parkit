# ParkIt --- Business Rules

**Version:** 2.0 (revised after design review, 2026-11-15)\
**Version note:** 2.1 (pending decisions closed, 2026-11-15)**Status:** Approved baseline. The only provisional item is the frontend
technology (see ARCHITECTURE.md §2), pending the UCS553 rubric check.

This document is the authoritative list of ParkIt business rules. If an
implementation detail conflicts with a rule here, stop and request
clarification rather than silently changing the rule.

## Policy constants (Version 1)

| Constant | Value |
|---|---|
| Minimum advance booking | 30 minutes |
| Payment hold | 5 minutes (never extended) |
| Late-arrival grace | 15 minutes after scheduled start |
| Early check-in allowance | 30 minutes before scheduled start |
| Customer cancellation cutoff | 2 hours before scheduled start |
| Time increment | 15 minutes |
| Minimum duration | 1 hour |
| Maximum duration | 7 days |
| Booking horizon | Start no more than 30 days after now |
| Overstay penalty | ₹100 per overstay incident |
| Overstay grace | 0 minutes |
| Reassignment wait | Up to 15 minutes |
| Active payment holds per customer | 1 |

These values are held in externalized application configuration (not
hard-coded in business logic) but are fixed policy for Version 1.

## BR-01 --- Single facility

ParkIt Version 1 manages exactly one privately operated facility.
Facility name, opening time, closing time, 24-hour flag and timezone are
configurable by an admin.

## BR-02 --- User roles

The system has CUSTOMER and ADMIN roles. Customers manage their own
profiles, vehicles and bookings. Admins manage facility operations.
Users cannot grant themselves ADMIN. The first admin is provisioned at
deployment from secured configuration, never through public
registration.

## BR-03 --- Vehicle categories and EV compatibility

Slot categories are CAR and BIKE. An electric car is a CAR for slot
compatibility. There is no EV slot category in Version 1.

## BR-04 --- Customer does not select a slot

The customer submits an interval and a vehicle; the system assigns a
compatible slot. Customers cannot choose a slot. The system (not the
customer) may later reassign the slot under BR-26.

## BR-05 --- Minimum advance booking

Scheduled start must be at least 30 minutes after the current instant.
Applies equally to customer and admin bookings. Evaluated when the hold
is created; it is not re-evaluated at payment confirmation.

## BR-06 --- Booking interval, limits and operating hours

1. Scheduled start and end are facility-local date-times entered in
   15-minute increments (minute ∈ {00, 15, 30, 45}, seconds zero).
2. End must be later than start. Duration must be ≥ 1 hour and ≤ 7 days.
3. Start must be no more than 30 days after the current instant.
4. Overnight and multi-day bookings are allowed. A parked vehicle may
   remain in the facility while it is closed.
5. Scheduled start and scheduled end must each fall
   within an open period, because vehicle entry and exit happen only
   while the facility is open. If `open_24_hours` is true, every instant
   is open. If `closing_time` is earlier than `opening_time`, the open
   period crosses midnight (for example 18:00--02:00). `opening_time`
   equal to `closing_time` is invalid unless `open_24_hours` is true.
6. The full interval is charged, including closed hours.
7. A slot must be available for the entire interval.

## BR-07 --- Time overlap

Intervals are half-open `[start, end)`. They overlap when
`newStart < existingEnd AND newEnd > existingStart`. Adjacent intervals
do not overlap. No turnover buffer exists in Version 1.

## BR-08 --- Effective occupancy interval

For availability, each booking occupies its slot as follows:

| Status | Occupies slot during |
|---|---|
| PENDING_PAYMENT, hold_expires_at > now | `[scheduled_start, scheduled_end)` |
| PENDING_PAYMENT, hold_expires_at ≤ now | Nothing (treated as expired even before the scheduler runs) |
| CONFIRMED | `[scheduled_start, scheduled_end)` |
| CHECKED_IN, now < scheduled_end | `[min(actual_check_in, scheduled_start), scheduled_end)` |
| CHECKED_IN, now ≥ scheduled_end (overstaying) | `[actual_check_in, ∞)` until check-out |
| COMPLETED, CANCELLED, PAYMENT_EXPIRED, NO_SHOW | Nothing |

Active slot blocks also make a slot unavailable for their interval.
After an early check-out the remaining scheduled time becomes available;
no partial refund is given.

## BR-09 --- Balanced allocation

When several compatible slots are available for the whole interval:

1. Consider only ACTIVE slots of the vehicle's category.
2. Exclude slots with any conflict under BR-08 or an active slot block.
3. For each remaining slot, count bookings whose `confirmed_at` falls in
   `[now − 30 days, now]`, including bookings later cancelled, no-show
   or completed. Holds that never confirmed are not counted.
4. Prefer the lowest count; break ties by ascending `sort_order`.
5. Perform steps 1--4 inside the allocation transaction under the lock
   defined in ARCHITECTURE.md §6.

The count is derived from booking records. `confirmed_at` is set once on
first confirmation and never cleared. The count measures bookings, not
hours occupied; this is intentional. A newly added slot will be preferred
until its count catches up.

## BR-10 --- Payment hold

Starting payment allocates a slot and creates a PENDING_PAYMENT booking
with `hold_expires_at = now + 5 minutes`. A quote does not allocate a
slot or create a hold. Payment retries never extend `hold_expires_at`. A
hold blocks allocation but is not occupancy. A customer may have at most
one active (unexpired) hold, including holds created for them by an
admin. A customer may release their own hold; the booking becomes
CANCELLED (reason `HOLD_RELEASED`). No refund applies because no payment
was collected.

Expired holds become PAYMENT_EXPIRED. Correctness depends on the
expiry time, not on when the scheduler runs (BR-08).

## BR-11 --- Payment attempts

Payment is simulated. Each attempt is a separate payment record; failed
attempts are retained. A booking becomes CONFIRMED only when a BOOKING
payment succeeds while the booking is PENDING_PAYMENT and
`hold_expires_at > now`, checked under a row lock. A success reported
after expiry is rejected; a future real gateway that captured money
would trigger an automatic full refund. Every payment request carries an
idempotency key; repeating a request never confirms twice or charges
twice. At most one BOOKING payment per booking can succeed.

## BR-12 --- Booking reference and QR

The booking reference is generated when the hold is created: a random,
non-sequential, URL-safe value with at least 128 bits of entropy. The QR
code encodes only this reference and is available only once the booking
is CONFIRMED. The QR image is generated on request, not stored. Looking
up a reference requires ADMIN authorization or ownership.

## BR-13 --- Customer cancellation

A customer may cancel a CONFIRMED booking if
`now ≤ scheduled_start − 2 hours`. Later cancellation is refused. An
eligible cancellation creates a full simulated refund of the successful
BOOKING payment. Bookings created less than 2 hours before start can
never be cancelled by the customer; the UI must warn before payment.

## BR-14 --- Admin cancellation

An admin may cancel a PENDING_PAYMENT or CONFIRMED booking at any time
before check-in. A reason is mandatory. A successful BOOKING payment, if
any, is fully refunded. The action is audited.

## BR-15 --- Refunds

Refund attempts are separate records linked to the payment. At most one
PENDING or SUCCESS refund may exist per payment, which prevents duplicate
refunds. A FAILED refund may be retried by an admin as a new refund
record. Refund amount equals the payment amount (full refunds only).
Booking status and refund status are independent.

## BR-16 --- Late arrival (no-show)

Check-in is allowed only while `now < scheduled_start + 15 minutes`,
or while a reassignment wait (BR-26) is active. At
or after that instant, a booking still CONFIRMED is a no-show and
becomes NO_SHOW, whether or not the scheduler has run, unless a
reassignment wait is active. No refund. A
NO_SHOW booking cannot be checked in.

## BR-17 --- Early check-in

Check-in is permitted from `scheduled_start − 30 minutes`, at no extra
charge, only if: the booking is CONFIRMED; a valid reference/QR is
presented; the facility is open; and the assigned slot has no conflict
(BR-08) or active block in `[now, scheduled_start)`. Otherwise the
customer waits for scheduled start (subject to BR-16). Scheduled times
never change; actual check-in is stored separately.

## BR-18 --- Check-in and check-out

Only an admin performs check-in and check-out. Check-in requires
CONFIRMED status and the window in BR-16/BR-17. Check-in and check-out
occur only while the facility is open. Check-out requires CHECKED_IN and
results in COMPLETED. `actual_check_out ≥ actual_check_in`.

## BR-19 --- Overstay

If `actual_check_out > scheduled_end + overstay grace`, one fixed ₹100
penalty is assessed per overstay incident regardless of length. The
assessed amount is stored on the booking. It is collected through a
separate OVERSTAY payment record (BR-20).

Overstay duration counts **only facility open time** after scheduled
end: `overstayMinutes = open minutes in [scheduled_end, actual_check_out)`.
Closed hours never count. A penalty applies only if
`overstayMinutes > 0` (overstay grace is 0 minutes). The duration is
recorded on the booking.

Collection workflow: at check-out an OVERSTAY payment is
created as PENDING; the admin records simulated counter collection,
which marks it SUCCESS. Check-out is not blocked by non-payment; an
uncollected penalty remains visible as outstanding.

A vehicle still inside at closing time may remain parked until the
facility reopens. If its scheduled end fell at or before closing and it
checks out promptly after reopening, only the open minutes between
scheduled end and closing, and between reopening and check-out, count.

## BR-20 --- Pricing

Rates (hourly and daily) are configured per category (CAR, BIKE).

```
billableHours = ceil(durationMinutes / 60)
fullDays      = billableHours / 24          (integer division)
remHours      = billableHours mod 24
charge        = fullDays × dailyRate + min(remHours × hourlyRate, dailyRate)
```

The partial-day charge never exceeds the daily rate. Examples at
₹200/day, ₹30/hour: 2 h → ₹60; 23 h → ₹200; 26 h → ₹200 + ₹60 = ₹260;
50 h 15 min → 51 h → ₹400 + ₹90 = ₹490.

The hourly and daily rates are snapshotted on the booking when the hold
is created. The amount shown at payment is the amount charged. Later
rate changes do not affect existing bookings.

## BR-21 --- Vehicle ownership and overlap

A booking's vehicle must belong to the booking customer and be active. A
vehicle may not have two bookings in PENDING_PAYMENT (unexpired),
CONFIRMED or CHECKED_IN whose scheduled intervals overlap. Registration
numbers are normalized (uppercase, spaces and hyphens removed) before
storage and uniqueness checks. A vehicle with future active bookings
cannot change category or be deactivated.

## BR-22 --- Slot management and blocking

Slots are ACTIVE or INACTIVE (decommissioned). Slots are never deleted.
A slot cannot become INACTIVE, or change category, while it has future
active bookings or a checked-in vehicle. Time-bounded blocking uses slot
blocks. A block may not be created if it conflicts with a hold,
confirmed booking or checked-in vehicle; the admin must first resolve the
conflict (for example by admin cancellation). A block can be cancelled
(unblocked); its record is kept.

## BR-23 --- Admin booking (counter payment)

An admin may create a booking for an existing active customer and one of
that customer's vehicles. All rules apply (advance time, limits,
operating hours, ownership, availability, one hold per customer, vehicle
overlap, pricing). Payment uses the simulated counter-payment method,
recorded by the admin within the same 5-minute hold. Creation and
payment are audited. Walk-in customers must first have an account and a
vehicle registered.

## BR-24 --- Occupancy

Current occupancy = slots holding a CHECKED_IN booking.
Operational capacity = ACTIVE slots without a slot block covering now.
Occupancy % = occupied ÷ operational capacity. Future bookings are not
occupancy.

## BR-25 --- Revenue

Reports distinguish successful BOOKING payments, successful refunds,
successful OVERSTAY payments, and pending or failed payments. Pending or
failed payments are never revenue. Net revenue = successful payments −
successful refunds. Payments and refunds are attributed to the period
of their `completed_at`.

## BR-26 --- Reassignment when the slot is occupied by an overstay

If, at check-in (including early check-in), the assigned slot is
physically occupied by an overstaying vehicle, the system reassigns the
booking to another compatible slot that is available for
`[now, scheduled_end)`, using the balanced allocation order (BR-09)
under the allocation lock. The original rate snapshot and price are
kept. The reassignment (old slot, new slot, reason) is audited.

If no compatible slot is available, the admin may start a
**reassignment wait** of up to 15 minutes (`reassignment_wait_until =
now + 15 min`). During the wait the booking stays CONFIRMED, the normal
no-show expiry (BR-16) is suspended for it, and check-in may be retried
(original slot or reassignment). If no slot becomes available by the end
of the wait, the booking is cancelled with a full refund (reason
`NO_SLOT_AFTER_OVERSTAY`), by the admin or automatically by the
scheduler. The start of the wait, any successful reassignment and the
cancellation are all audited.

## BR-27 --- Audit

Recorded with actor, action, entity, timestamp and details: slot
create/update/status change, block create/cancel, rate changes, facility
settings changes, admin bookings, counter payments, admin
cancellations, refunds, check-in, check-out, overstay assessment and
collection, and reassignments. System actions (scheduler expiries) are
recorded with actor type SYSTEM.

## BR-28 --- Timezone

Facility rules use the facility timezone (`Asia/Kolkata`). Instants are
stored in UTC (`TIMESTAMPTZ`). User input and display are converted at
the UI/API boundary.

## BR-29 --- Security and data access

Customers access only their own data. Admin operations require ADMIN.
Possessing a reference/QR alone never exposes customer data.

## Booking state machine

| From | To | Trigger |
|---|---|---|
| (new) | PENDING_PAYMENT | Allocation and hold created |
| PENDING_PAYMENT | CONFIRMED | Successful BOOKING payment before expiry |
| PENDING_PAYMENT | PAYMENT_EXPIRED | Hold expired |
| PENDING_PAYMENT | CANCELLED | Customer releases hold / admin cancels |
| CONFIRMED | CHECKED_IN | Admin check-in |
| CONFIRMED | CANCELLED | Customer (≥ 2 h before) or admin cancellation |
| CONFIRMED | NO_SHOW | Grace period elapsed without check-in (not during a reassignment wait) |
| CONFIRMED | CANCELLED | Reassignment wait ended without a slot (full refund) |
| CHECKED_IN | COMPLETED | Admin check-out |

All other transitions are invalid. COMPLETED, CANCELLED,
PAYMENT_EXPIRED and NO_SHOW are terminal.

## Decisions still requiring confirmation

None for business rules. Frontend technology remains provisional pending
the UCS553 rubric (ARCHITECTURE.md §2).

## Change log

- 2.0: Added policy constants, booking limits, effective occupancy
  interval, state machine, vehicle overlap, one hold per customer,
  admin cancellation, counter payment, refund retry, reassignment,
  daily-rate cap, PAYMENT_EXPIRED/NO_SHOW, sort_order tie-break,
  INACTIVE slot status.
- 2.1: Closed pending items: operating-hours semantics, 0-minute
  overstay grace, overstay counted in open hours only, overstay
  collection workflow, 15-minute reassignment wait with automatic
  cancellation and refund, released hold = CANCELLED.
