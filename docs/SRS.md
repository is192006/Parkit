# ParkIt --- Software Requirements Specification (SRS)

**Version:** 2.0 (revised after design review, 2026-11-15)\
**Course:** UCS553 --- Enterprise Web Application\
**Project type:** Java-based web application / mini project\
**Facility model:** One privately managed parking facility\
**Status:** Revised baseline (2.1); implementation has not started.
Only the frontend technology is provisional, awaiting UCS553 rubric
verification.

Business rules (BR-xx) are defined in `BUSINESS_RULES.md`, which is
authoritative.

## 1. Introduction

### 1.1 Purpose

ParkIt is a web-based parking reservation and management system for one
privately managed facility. Customers register, manage vehicles, request
parking for a date-time interval, receive an automatically allocated
compatible slot, pay through a simulated payment flow and manage
bookings. An administrator manages slots, rates, facility settings,
bookings, vehicle entry and exit, and operational reports.

### 1.2 Problem statement

Manual parking operations make it hard for customers to know
availability and charges in advance, and for staff to coordinate
reservations, prevent overlapping allocations, record entry and exit,
and calculate occupancy and revenue. ParkIt centralizes these
operations.

### 1.3 Objectives

-   Customer registration, login and profile management.
-   Multiple vehicles per customer.
-   Parking requests by start and end date-time, including overnight and
    multi-day stays.
-   Automatic balanced allocation of a compatible slot.
-   No overlapping reservations per slot or per vehicle, including under
    concurrent requests.
-   Price shown before payment.
-   Simulated payments, retries, counter payments and full refunds.
-   Unique non-guessable booking reference and QR code.
-   Booking history and cancellation.
-   Admin slot, rate, facility, booking, check-in/check-out, overstay
    and reporting functions.

### 1.4 Scope

Version 1 supports one facility with a customer portal and an admin
dashboard, for CARs and BIKEs. An electric car uses CAR slots; there is
no EV category.

Out of scope: real payment gateways, physical gate/barrier integration,
number-plate recognition, GPS discovery, multiple facilities, native
mobile apps, SMS/WhatsApp/email notifications, password reset by email,
booking modification (cancel and rebook instead), and partial refunds.

## 2. Users and roles

### 2.1 Customer

A customer can register, log in and manage their profile and password;
add, view, update and deactivate vehicles; request a quote; start
payment (which allocates a slot and creates a 5-minute hold); complete
or retry simulated payment within the hold; release their hold; view
booking details, QR code and history; cancel an eligible booking and
view refund status. A customer cannot choose a slot.

### 2.2 Administrator

An administrator can log in to a protected admin area; manage slots,
slot blocks, rates and facility settings; search customers and their
vehicles; view and manage bookings; create bookings for customers with
counter payment; cancel bookings before check-in; verify references/QR
codes; check vehicles in and out; record overstay collection; retry
failed refunds; view occupancy, revenue and audit logs.

## 3. Functional requirements

### 3.1 Authentication and accounts

-   **FR-01:** Customers register with name, email, phone and password.
-   **FR-02:** The system authenticates users with session-based login.
-   **FR-03:** Passwords are stored as BCrypt hashes, never plaintext.
-   **FR-04:** The system distinguishes CUSTOMER and ADMIN roles.
    Registration always creates CUSTOMER. The first ADMIN is provisioned
    from secured deployment configuration.
-   **FR-05:** Customers can update name and phone, and change their
    password (current password required). Email is the login identifier
    and cannot be changed in Version 1.
-   **FR-06:** Customers access only their own vehicles and bookings.
    Admin endpoints require ADMIN.
-   **FR-07:** State-changing requests are protected against CSRF.
-   **FR-08:** Email uniqueness is case-insensitive.

### 3.2 Vehicle management

-   **FR-09:** A customer can register multiple vehicles.
-   **FR-10:** A vehicle has a registration number, category (CAR or
    BIKE), optional electric indicator and optional description.
-   **FR-11:** Registration numbers are normalized (BR-21) and globally
    unique.
-   **FR-12:** A customer can update or deactivate a vehicle, except
    that category changes and deactivation are refused while the vehicle
    has future active bookings.
-   **FR-13:** A booking's vehicle must be active and owned by the
    booking customer.
-   **FR-14:** Electric cars are compatible with CAR slots.

### 3.3 Slot management

-   **FR-15:** Admin creates and updates slots with a unique slot number
    and a unique numeric sort order.
-   **FR-16:** Each slot has a category: CAR or BIKE.
-   **FR-17:** Slot operational status is ACTIVE or INACTIVE and is
    independent of occupancy and reservations. Status/category changes
    follow BR-22.
-   **FR-18:** Admin can block a slot for a period and cancel a block;
    conflicting blocks are refused (BR-22).
-   **FR-19:** Blocked or inactive slots are not allocated for
    conflicting intervals.

### 3.4 Facility settings

-   **FR-20:** Admin can view and update facility name, opening time,
    closing time and the 24-hour flag. Timezone is `Asia/Kolkata`.
-   **FR-21:** Customers can view facility hours and current rates
    without logging in.

### 3.5 Booking and availability

-   **FR-22:** A booking request contains facility-local start and end
    date-times and a vehicle.
-   **FR-23:** The request is validated against BR-05 (30-minute advance)
    and BR-06 (15-minute increments, 1 hour--7 days, 30-day horizon,
    operating hours, including overnight windows and a 24-hour option).
-   **FR-24:** A quote returns availability (yes/no) and the price
    breakdown without allocating a slot.
-   **FR-25:** Starting payment allocates a slot automatically using
    balanced allocation (BR-09) and creates a 5-minute hold (BR-10).
-   **FR-26:** The system prevents overlapping occupancy of a slot
    (BR-07, BR-08) and overlapping active bookings for a vehicle
    (BR-21), including under concurrent requests.
-   **FR-27:** A customer may have at most one active payment hold.
-   **FR-28:** A customer can release their own hold.
-   **FR-29:** A booking is CONFIRMED only after a successful simulated
    payment within the hold.
-   **FR-30:** Customers view their own bookings filtered by upcoming
    (CONFIRMED), active (CHECKED_IN), completed, cancelled and no-show,
    paginated. PENDING_PAYMENT and PAYMENT_EXPIRED records are shown
    only as an in-progress or abandoned payment, not as bookings.
-   **FR-31:** Admin can create a booking for an existing customer and
    their vehicle with the same rules, paid by simulated counter
    payment (BR-23).

### 3.6 Pricing and payment

-   **FR-32:** Admin configures hourly and daily rates per category.
-   **FR-33:** Price follows BR-20, with the partial day capped at the
    daily rate.
-   **FR-34:** Rates are snapshotted on the booking when the hold is
    created.
-   **FR-35:** Every payment attempt is a separate record; failed
    attempts are retained.
-   **FR-36:** Payment requests are idempotent (idempotency key).
-   **FR-37:** Success within the hold confirms the booking; failure or
    success after expiry does not.
-   **FR-38:** Retries are allowed within the hold and never extend it.
-   **FR-39:** Payment methods are simulated ONLINE and simulated
    COUNTER.
-   **FR-40:** Payment processing is behind a `PaymentGateway`
    interface so a real gateway (for example Razorpay) can be added
    without changing booking logic.
-   **FR-41:** For demos and tests, the simulated gateway's outcome
    (success/failure) is selected explicitly in the request.

### 3.7 Cancellation and refunds

-   **FR-42:** A customer can cancel a CONFIRMED booking up to 2 hours
    before start (BR-13).
-   **FR-43:** An admin can cancel a PENDING_PAYMENT or CONFIRMED
    booking before check-in, with a mandatory reason (BR-14).
-   **FR-44:** Eligible cancellations produce a full simulated refund,
    recorded separately from payment and booking status.
-   **FR-45:** Duplicate refunds are impossible (BR-15); an admin can
    retry a FAILED refund.

### 3.8 QR and booking identification

-   **FR-46:** Each booking receives a unique, random, non-sequential
    reference when the hold is created.
-   **FR-47:** A QR code encoding only the reference is available for
    CONFIRMED bookings.
-   **FR-48:** Admin can retrieve and verify a booking by entering or
    pasting its reference. Camera scanning is optional.

### 3.9 Check-in, check-out and overstay

-   **FR-49:** Admin records actual check-in and check-out timestamps
    while the facility is open.
-   **FR-50:** Early check-in is allowed from 30 minutes before start
    when BR-17 conditions hold; scheduled times do not change and no
    extra charge applies.
-   **FR-51:** Check-in is refused at or after start + 15 minutes; such
    bookings become NO_SHOW (BR-16).
-   **FR-52:** If the assigned slot is occupied by an overstaying
    vehicle at check-in, the system reassigns a compatible available
    slot and audits it. If none is free, the admin may wait up to 15
    minutes (no-show expiry suspended); if still none, the booking is
    cancelled with a full refund and audited (BR-26).
-   **FR-53:** Check-out with any open-hours time after scheduled end
    (grace 0 minutes; closed hours excluded) assesses one ₹100 penalty;
    overstay minutes are recorded.
-   **FR-54:** The penalty is recorded as a separate OVERSTAY payment;
    a PENDING record is created at check-out and the admin records
    counter collection later; check-out is never blocked (BR-19).

### 3.10 Admin dashboard and reports

-   **FR-55:** Admin views bookings by status and date range, paginated.
-   **FR-56:** The system shows currently occupied slots (CHECKED_IN).
-   **FR-57:** Occupancy is shown against operational capacity (BR-24).
-   **FR-58:** Revenue for a date range distinguishes booking payments,
    refunds, overstay collections and pending/failed payments (BR-25).
-   **FR-59:** Administrative and system actions in BR-27 are audited;
    admin can view the audit log.

## 4. Non-functional requirements

-   **Security:** Spring Security, session authentication, BCrypt, CSRF
    protection, role-based authorization, ownership checks in services,
    backend validation, error responses that reveal no internals.
-   **Reliability:** Booking, payment, refund and check-in transitions
    run in database transactions. Double booking is prevented by
    pessimistic locking with PostgreSQL exclusion constraints as a
    safety net.
-   **Correctness over time:** Time-based rules are evaluated against the
    current instant, so results are correct even if the scheduler is late.
-   **Testability:** All time-dependent logic uses an injected
    `java.time.Clock`.
-   **Performance:** Index lookup and foreign-key columns; paginate
    lists. Targets to be set once load is known.
-   **Maintainability:** Layered modular monolith with controllers,
    services, repositories, entities, DTOs, enums and exception
    handling. Schema changes only through Flyway migrations.
-   **Usability:** Responsive Bootstrap UI; price and cancellation
    policy shown before payment.
-   **Time handling:** Instants stored in UTC (`TIMESTAMPTZ`), entered
    and displayed in `Asia/Kolkata`.
-   **Deployment assumption:** Single application instance in Version 1
    (in-memory sessions, single scheduler).

## 5. Technology stack

-   Java 21 (LTS)
-   Spring Boot 3.x (latest supported 3.x release at setup)
-   Spring MVC (REST), Spring Security, Spring Data JPA / Hibernate,
    Bean Validation
-   PostgreSQL 16+ (only supported database)
-   Flyway for migrations
-   Maven
-   Static HTML, CSS, JavaScript and Bootstrap (served by Spring Boot)
-   ZXing for QR generation
-   JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL)
-   Postman for API testing; Git and GitHub

## 6. Acceptance criteria

The application is ready for demonstration when tests show that:

1. Customers can register, log in and log out; CSRF is enforced.
2. Customers can manage multiple vehicles.
3. Admin can manage slots, blocks, rates and facility settings.
4. Requests under 30 minutes in advance, outside the 15-minute grid,
   shorter than 1 hour, longer than 7 days or beyond 30 days are
   rejected for customers and admins.
5. Overnight and multi-day bookings are priced with the daily-rate cap.
6. A compatible slot is allocated by balanced allocation.
7. Blocked, inactive and conflicting slots are excluded.
8. Concurrent requests cannot reserve the same slot, or the same
   vehicle, for overlapping periods.
9. A customer cannot hold two active payment holds.
10. Payment success within the hold confirms; failure, or success after
    expiry, does not; retries do not extend the hold.
11. An unpaid hold becomes PAYMENT_EXPIRED after 5 minutes.
12. Eligible customer cancellation and admin cancellation produce
    exactly one full refund.
13. Admin bookings use counter payment and are audited.
14. Arrival at or after start + 15 minutes yields NO_SHOW.
15. Early check-in up to 30 minutes is allowed only if the slot is free.
16. An overstay produces one ₹100 OVERSTAY payment record.
17. A booking whose slot is occupied by an overstay is reassigned and
    audited.
18. Admin can check in/out and view occupancy, revenue and audit logs.
19. Customer data and admin operations are protected by authorization.

## 7. Out of scope

See Section 1.4. Features not listed as Version 1 requirements must not
be added without an explicit scope decision.

## 8. Dependencies and assumptions

-   One facility, timezone `Asia/Kolkata` (no daylight saving).
-   CAR and BIKE categories; EV cars use CAR slots.
-   The initial admin account, facility settings, rates and slot
    inventory are seeded before end-to-end testing; exact values are to
    be supplied by the project owner.
-   Payment is simulated; no real money is collected.
-   Single application instance.
-   **AWAITING RUBRIC VERIFICATION:** The UCS553 evaluation rubric has not been reviewed. If
    it requires server-rendered views (JSP/Thymeleaf) or Servlets/JDBC,
    the frontend decision must be revisited. JSP, Servlets and JDBC are
    not assumed to be mandatory.

## 9. Change log

- 2.0: Java 21 / Spring Boot 3.x / session auth / Flyway fixed; booking
  limits, vehicle overlap, one hold per customer, PAYMENT_EXPIRED and
  NO_SHOW, admin cancellation, counter payment, refund retry, overstay
  payment record, reassignment, facility settings FRs, quote without
  hold, idempotent payments, expanded acceptance criteria.
- 2.1: Closed pending decisions (operating hours, overstay grace and
  open-hours counting, overstay collection, reassignment wait, released
  hold); UCS553 frontend remains provisional.
