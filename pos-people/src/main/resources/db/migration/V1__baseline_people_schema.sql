-- pos-people baseline schema (collapsed 2026-06).
-- Single source of truth, generated from the JPA entities (PostgreSQL dialect) and curated.
-- Supersedes the pre-collapse V1..V10 chain (archived under db/archive/people-collapse-2026-06/).
-- Identity (person) is separate from employment (employee); assignments reference employee;
-- user_person_links is keyed by username; contact_info_json is a derived @Formula (not stored).

create table person (
    id UUID not null,
    first_name varchar(255),
    last_name varchar(255),
    legal_name varchar(255),
    preferred_name varchar(255),
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    primary key (id)
);

create table employee (
    id UUID not null,
    person_id uuid not null unique,
    employee_number varchar(255),
    status varchar(255) check ((status in ('ACTIVE','ON_LEAVE','SUSPENDED','TERMINATED','DISABLED'))),
    hire_date date,
    termination_date date,
    status_effective_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    primary key (id),
    constraint fk_employee_person foreign key (person_id) references person
);

create table person_contact_point (
    id UUID not null,
    person_id uuid not null,
    contact_type varchar(20) not null check ((contact_type in ('EMAIL','PHONE_MOBILE','PHONE_HOME','PHONE_WORK'))),
    "value" varchar(255) not null,
    is_primary boolean not null,
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    primary key (id)
);

create table user_person_links (
    id UUID not null,
    person_id UUID not null,
    username varchar(255) not null unique,
    status varchar(20) not null check ((status in ('ACTIVE','INACTIVE'))),
    link_type varchar(50),
    notes varchar(1000),
    created_by varchar(255),
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone,
    primary key (id),
    constraint fk_user_person_links_person foreign key (person_id) references person
);

create table employee_location_assignment (
    id UUID not null,
    employee_id UUID not null,
    location_id uuid not null,
    role varchar(255) not null,
    is_primary boolean not null,
    effective_from date not null,
    effective_to date,
    status varchar(255) not null check ((status in ('ACTIVE','ENDED'))),
    created_by varchar(255),
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone,
    primary key (id),
    unique (employee_id, location_id, role, effective_from),
    constraint fk_employee_location_assignment_employee foreign key (employee_id) references employee
);

create table employee_offboarding_retry_queue (
    id UUID not null,
    employee_id UUID not null,
    assignment_policy varchar(255) not null check ((assignment_policy in ('IMMEDIATE','GRACE_PERIOD'))),
    disable_reason varchar(255),
    actor_id varchar(255) not null,
    failure_reason varchar(255) not null,
    attempts integer not null,
    next_attempt_at timestamp(6) with time zone not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone,
    primary key (id),
    constraint fk_employee_offboarding_person foreign key (employee_id) references person
);

create table work_session (
    session_id uuid not null,
    person_id UUID not null,
    status varchar(32) not null,
    actor varchar(128),
    started_at timestamp(6) with time zone not null,
    ended_at timestamp(6) with time zone,
    submitted_at timestamp(6) with time zone,
    billable_minutes integer,
    break_minutes integer,
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    primary key (session_id),
    constraint fk_work_session_person foreign key (person_id) references person
);

create table work_session_break (
    break_id uuid not null,
    session_id uuid not null,
    actor varchar(128),
    started_at timestamp(6) with time zone not null,
    ended_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    primary key (break_id),
    constraint fk_work_session_break_session foreign key (session_id) references work_session
);

create table time_period (
    time_period_id UUID not null,
    tenant_id UUID not null,
    start_date date not null,
    end_date date not null,
    status varchar(50) not null check ((status in ('OPEN','SUBMISSION_CLOSED','PAYROLL_CLOSED'))),
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (time_period_id)
);

create table time_entry (
    time_entry_id UUID not null,
    person_id UUID,
    location_id uuid,
    timesheet_id varchar(255),
    status varchar(255) check ((status in ('DRAFT','SUBMITTED','PENDING_APPROVAL','APPROVED','REJECTED'))),
    attendance_start_at timestamp(6) with time zone,
    attendance_end_at timestamp(6) with time zone,
    approved_at timestamp(6) with time zone,
    approved_by varchar(255),
    rejected_at timestamp(6) with time zone,
    rejected_by varchar(255),
    rejection_reason varchar(500),
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    primary key (time_entry_id)
);

create table time_entry_adjustment (
    adjustment_id UUID not null,
    time_entry_id UUID not null,
    status varchar(50) check ((status in ('PROPOSED','PENDING','APPROVED','REJECTED'))),
    reason_code varchar(200),
    minutes_delta integer,
    proposed_start_at timestamp(6) with time zone,
    proposed_end_at timestamp(6) with time zone,
    notes TEXT,
    created_by varchar(255),
    created_at timestamp(6) with time zone,
    decided_by varchar(255),
    decided_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    primary key (adjustment_id),
    constraint fk_time_entry_adjustment_entry foreign key (time_entry_id) references time_entry
);

create table time_entry_audit (
    audit_id UUID not null,
    time_entry_id varchar(255) not null,
    action varchar(100) not null,
    actor_id varchar(255),
    correlation_id varchar(100),
    details TEXT,
    timestamp timestamp(6) with time zone not null,
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    primary key (audit_id)
);

create table time_entry_exception (
    exception_id UUID not null,
    employee_id varchar(255) not null,
    time_entry_id varchar(255),
    exception_code varchar(200),
    severity varchar(50) check ((severity in ('WARNING','BLOCKING'))),
    status varchar(50) check ((status in ('OPEN','ACKNOWLEDGED','RESOLVED','WAIVED'))),
    work_date date,
    detected_at timestamp(6) with time zone,
    resolved_at timestamp(6) with time zone,
    resolved_by varchar(255),
    resolution_notes TEXT,
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    primary key (exception_id)
);

create table timekeeping_entry (
    timekeeping_entry_id UUID not null,
    tenant_id UUID not null,
    employee_id UUID not null,
    source_system varchar(50) not null,
    source_session_id UUID not null,
    original_source_session_id uuid,
    associated_work_order_id UUID,
    session_start_time timestamp(6) with time zone not null,
    session_end_time timestamp(6) with time zone not null,
    approval_status varchar(50) not null check ((approval_status in ('PENDING_APPROVAL','APPROVED','REJECTED'))),
    correction_id uuid,
    correction_reason TEXT,
    created_at timestamp(6) with time zone not null,
    primary key (timekeeping_entry_id),
    unique (tenant_id, source_system, source_session_id)
);

create table timekeeping_policy (
    timekeeping_policy_id UUID not null,
    scope_type varchar(16) not null check ((scope_type in ('GLOBAL','LOCATION'))),
    scope_id uuid,
    job_time_discrepancy_threshold_minutes integer not null,
    effective_start_at timestamp(6) with time zone,
    effective_end_at timestamp(6) with time zone,
    updated_by varchar(255),
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone not null,
    primary key (timekeeping_policy_id)
);

create index idx_employee_status on employee (status);
create index idx_person_contact_point_person on person_contact_point (person_id);
create index idx_person_contact_point_type on person_contact_point (contact_type);
create index idx_user_person_links_person_id on user_person_links (person_id);
create index idx_time_entry_attendance_window on time_entry (attendance_start_at, attendance_end_at);
create index idx_time_entry_location_start on time_entry (location_id, attendance_start_at);
create index idx_time_entry_person_start on time_entry (person_id, attendance_start_at);
create index idx_time_period_tenant_status on time_period (tenant_id, status);
create index idx_time_period_dates on time_period (start_date, end_date);
create index idx_timekeeping_policy_scope on timekeeping_policy (scope_type, scope_id);
create index idx_timekeeping_policy_scope_effective_updated on timekeeping_policy (scope_type, scope_id, effective_start_at, effective_end_at, updated_at);
